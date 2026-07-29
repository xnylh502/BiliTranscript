package com.example.bilitranscript

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.bilitranscript.data.db.LogSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream
import kotlin.math.min

/** 段文件后缀：`<basename><PART_SUFFIX><index>`，例：`model.onnx.part0`。 */
private const val PART_SUFFIX = ".part"

/** 模型文件（下载用）：单文件可对应多个镜像源，逐个尝试直到成功。 */
data class ModelFileSpec(val filename: String, val urls: List<String>, val sizeBytes: Long) {
    /** 单 URL 的兼容构造。 */
    constructor(filename: String, url: String, sizeBytes: Long) : this(filename, listOf(url), sizeBytes)
}

/** 模型档位：用于下拉分组与详情展示。 */
enum class ModelTier(val label: String) {
    BUNDLED("内置"),
    LIGHT("轻档"),
    MID("中档"),
    HEAVY("重档")
}

/** 一个可选识别模型的元信息 */
data class AsrModelSpec(
    val id: String,
    val name: String,
    val engine: AsrEngine,
    /** true = 内置在 APK assets 里（免下载）；false = 需下载到手机存储 */
    val bundled: Boolean,
    val approxSizeMb: Int,
    val description: String,
    val files: List<ModelFileSpec> = emptyList(),
    /** 档位（轻/中/重/内置）。 */
    val tier: ModelTier = ModelTier.MID,
    /** 约 50 字的完整介绍（长按详情弹窗显示）。 */
    val longDescription: String = "",
    /**
     * 安装成功率评估（0-100）：衡量「从镜像源把模型拉下来」的难度，
     * 依据镜像数量、国内可达性、实测速度、是否需要解压等静态评估；与识别准确率无关。
     */
    val downloadReliability: Int = 0,
    /** 成功率依据的一句话说明（如「双 ModelScope 国内镜像互备」）。 */
    val reliabilityNote: String = "",
    /** true = files 里唯一文件是 tar.bz2 整包（k2-fsa 官方发布格式），下载完成后自动解压。 */
    val extractTarBz2: Boolean = false
)

/** 实时下载统计（用于 UI 显示）。 */
data class DownloadStats(
    /** 当前下载速度（字节/秒），滑动平均。0 表示未知/启动期。 */
    val bytesPerSec: Long = 0L,
    /** 预计剩余秒数。-1 表示未知。 */
    val etaSec: Long = -1L,
    /** 当前正在使用的镜像域名。 */
    val mirror: String = "",
    /** 完整文件总字节数。 */
    val totalBytes: Long = 0L,
    /** 已下载字节数。 */
    val doneBytes: Long = 0L
)

/** 模型在本机的状态 */
data class ModelStatus(
    val spec: AsrModelSpec,
    val installed: Boolean,
    val downloading: Boolean,
    val progress: Float,
    /** 当前下载的实时统计；未在下载时为 null。 */
    val stats: DownloadStats? = null
)

// =============================================================================
// ModelManager
// =============================================================================

/**
 * 模型仓库：模型**不打进 APK**，而是下载/推送到手机存储
 * (`filesDir/models/<id>/`)，App 自动检测、可选用、可删除。
 *
 * ## 下载通道升级要点
 *  - **多镜像测速**：首次下载前对每个 URL 发 Range 探测，按延迟升序排序后逐个尝试。
 *    段下载始终使用**原始镜像 URL**（不缓存 302 后的 CDN 直链），每次请求由 OkHttp
 *    重新跟随重定向 —— HF/hf-mirror 的签名直链仅 1 小时有效，长下载中途会过期 403。
 *  - **分段并行**：单文件拆成最多 [MAX_CHUNKS_PER_FILE] 个 Range 段，多个协程并发拉取，
 *    每段写入独立的 `.part` 文件。常见拉满手机 4G/Wi-Fi 峰值带宽。
 *  - **断点续传**：每段独立偏移，未完成段用 `.part` 文件持久化；跨进程重启后可继续。
 *  - **每段重试**：每段都有独立的指数退避（200ms→4s），整段失败切镜像；不会整文件重来。
 *  - **200 兜底**：服务器忽略 Range 返回全量流时，仅接受 chunk0 首下的头部截断；
 *    其余段判定该镜像不支持 Range，整文件降级单流连续下载。
 *  - **连接池调优**：[OkHttpClient] 配置 keep-alive + pingInterval，
 *    避免 NAT 静默断连后等 TCP 超时。
 */
class ModelManager(private val context: Context) {

    private val maxChunksPerFile = MAX_CHUNKS_PER_FILE
    private val minChunkBytes = MIN_CHUNK_BYTES
    private val maxRetriesPerChunk = MAX_RETRIES_PER_CHUNK

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .connectionPool(
            ConnectionPool(
                maxIdleConnections = 16,
                keepAliveDuration = 30,
                timeUnit = TimeUnit.SECONDS
            )
        )
        .retryOnConnectionFailure(true)
        .pingInterval(20, TimeUnit.SECONDS) // HTTP/2 PING：保持长连接，防 NAT 杀链
        .build()

    // 初始状态不含文件 IO（构造可能在主线程，避免启动卡顿）；
    // 真实安装状态由首次 refresh()（ViewModel init 的 IO 协程里调用）填充。
    private val _statuses = MutableStateFlow(CATALOG.map {
        ModelStatus(spec = it, installed = false, downloading = false, progress = 0f)
    })
    val statuses: StateFlow<List<ModelStatus>> = _statuses

    // === 公开 API（接口与旧版一致）===

    fun catalog(): List<AsrModelSpec> = CATALOG

    fun specById(id: String): AsrModelSpec? = CATALOG.firstOrNull { it.id == id }

    /** 选中的模型若未安装，回落到内置 SenseVoice。 */
    fun resolveUsable(selectedId: String): AsrModelSpec {
        val spec = specById(selectedId) ?: BUNDLED_SENSEVOICE
        return if (isInstalled(spec)) spec else BUNDLED_SENSEVOICE
    }

    fun modelsRoot(): File = File(context.filesDir, "models")

    fun modelDir(spec: AsrModelSpec): File = File(modelsRoot(), spec.id)

    /** 按「文件类型」及「合法文件大小」判断是否真实装好（防止把几 KB 的 LFS/错误网页误认为已装好）。 */
    fun isInstalled(spec: AsrModelSpec): Boolean {
        if (spec.bundled) return true
        val dir = modelDir(spec)
        if (!dir.isDirectory) return false
        val allFiles = dir.walkTopDown().filter { it.isFile && !it.name.contains(PART_SUFFIX) && !it.name.endsWith(".tmp") }.toList()
        if (allFiles.isEmpty()) return false
        return when (spec.engine) {
            AsrEngine.WHISPER -> {
                val encoder = allFiles.firstOrNull { it.name.contains("encoder") && it.name.endsWith(".onnx") }
                val decoder = allFiles.firstOrNull { it.name.contains("decoder") && it.name.endsWith(".onnx") }
                val tokens = allFiles.firstOrNull { it.name.endsWith("tokens.txt") }
                encoder != null && encoder.length() > 50_000_000L &&
                        decoder != null && decoder.length() > 50_000_000L &&
                        tokens != null && tokens.length() > 10_000L
            }
            AsrEngine.SENSEVOICE -> {
                val model = allFiles.firstOrNull { it.name.endsWith(".onnx") }
                val tokens = allFiles.firstOrNull { it.name.endsWith("tokens.txt") }
                model != null && model.length() > 50_000_000L &&
                        tokens != null && tokens.length() > 10_000L
            }
            AsrEngine.PARAFORMER -> {
                // model.int8.onnx（或 fp32 model.onnx）+ tokens.txt
                val model = allFiles.firstOrNull { it.name.endsWith(".onnx") }
                val tokens = allFiles.firstOrNull { it.name.endsWith("tokens.txt") }
                model != null && model.length() > 50_000_000L &&
                        tokens != null && tokens.length() > 10_000L
            }
            AsrEngine.QWEN3 -> {
                // conv_frontend.onnx + encoder.int8.onnx + decoder.int8.onnx + tokenizer/ 目录
                val conv = allFiles.firstOrNull { it.name == "conv_frontend.onnx" && it.length() > 1_000_000L }
                val encoder = allFiles.firstOrNull {
                    it.name.contains("encoder") && it.name.endsWith(".onnx") && it.length() > 10_000_000L
                }
                val decoder = allFiles.firstOrNull {
                    it.name.contains("decoder") && it.name.endsWith(".onnx") && it.length() > 50_000_000L
                }
                val merges = File(dir, "tokenizer/merges.txt")
                val vocab = File(dir, "tokenizer/vocab.json")
                conv != null && encoder != null && decoder != null &&
                        merges.length() > 100_000L && vocab.length() > 100_000L
            }
        }
    }

    fun refresh() {
        _statuses.value = _statuses.value.map { st -> st.copy(installed = isInstalled(st.spec)) }
    }

    fun delete(spec: AsrModelSpec) {
        if (spec.bundled) return
        modelDir(spec).deleteRecursively()
        refresh()
    }

    /**
     * App 内下载模型（国内可能需要代理）。逐文件并行 + 单文件多段并行（带单流降级兜底）。
     * @return 成功 true
     */
    suspend fun download(spec: AsrModelSpec): Boolean = withContext(Dispatchers.IO) {
        if (spec.bundled || spec.files.isEmpty()) return@withContext true
        val dir = modelDir(spec).apply { mkdirs() }
        setDownloading(spec.id, true, 0f, DownloadStats())

        val total = spec.files.sumOf { it.sizeBytes }.coerceAtLeast(1L)
        // ticker 是全局 done 字节的唯一真相（跨文件 + 跨段累加）
        val ticker = DownloadTicker(spec.id, total)

        try {
            for (fs in spec.files) {
                // 1) 探测与解析：按延迟排序镜像 + 探测精确字节数
                //    （返回的是原始镜像 URL：段下载时由 OkHttp 重新跟随 302，避免签名直链过期）
                val (resolvedUrls, actualSize) = probeMirrorsAndResolve(fs.urls, fs.sizeBytes)
                val mirror = resolvedUrls.firstOrNull()?.let { hostOf(it) } ?: ""
                updateStats(spec.id) { it.copy(totalBytes = total, mirror = mirror) }
                // 各镜像实际文件大小可能与声明值有出入（如 ModelScope 重打包版本），
                // 用探测到的真实大小修正总进度，避免进度条卡在 99%。
                if (actualSize > 0 && actualSize != fs.sizeBytes) {
                    ticker.adjustTotal(actualSize - fs.sizeBytes)
                }

                // 2) 拉取文件；多段并行优先，失败降级到单流
                var ok = downloadFileParallel(
                    spec = spec,
                    fs = fs,
                    dir = dir,
                    urls = resolvedUrls,
                    actualSize = actualSize,
                    total = total,
                    ticker = ticker
                )
                if (!ok) {
                    Log.w(TAG, "${fs.filename} 多段 Range 下载失败，尝试单流连续下载兜底...")
                    ok = downloadSingleStream(spec, fs, dir, resolvedUrls, ticker)
                }
                if (!ok) {
                    throw RuntimeException("下载文件 ${fs.filename} 失败（所有镜像与下载通道均失败）")
                }
                LogSink.logDownload(
                    modelId = spec.id,
                    fileName = fs.filename,
                    mirror = mirror,
                    bytesTotal = fs.sizeBytes,
                    bytesDone = fs.sizeBytes,
                    success = true
                )
            }

            // 3) tar.bz2 整包模型：全部下载完成后自动解压到模型目录
            if (spec.extractTarBz2) {
                val fs = spec.files.first()
                val archive = File(dir, fs.filename)
                if (!archive.exists()) throw RuntimeException("模型包缺失: ${fs.filename}")
                Log.i(TAG, "下载完成，开始解压模型包 ${fs.filename} ...")
                setDownloading(
                    spec.id, true, 0.99f,
                    DownloadStats(mirror = "正在解压模型包…", totalBytes = total, doneBytes = total)
                )
                extractTarBz2(archive, dir)
                archive.delete()
                pruneRedundantFp32(dir)
                Log.i(TAG, "模型包解压完成")
            }
            refresh()
            true
        } catch (e: Exception) {
            Log.e(TAG, "下载失败: ${e.message}", e)
            LogSink.logError(
                bvid = null,
                stage = "download",
                message = "${spec.id}: ${e.message}",
                throwable = e
            )
            modelDir(spec).listFiles()?.forEach { f ->
                // 清理半成品 final / tmp 文件，但保留 .part* 用于下次续传
                if (!f.name.contains(PART_SUFFIX) && f.name.endsWith(".tmp")) f.delete()
            }
            refresh()
            false
        } finally {
            setDownloading(spec.id, false, 0f, null)
        }
    }

    // ---- 导入压缩包（支持 .zip 与 .tar.bz2 / k2-fsa 官方整包）----

    suspend fun importFromArchive(spec: AsrModelSpec, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (spec.bundled) return@withContext true
        val dir = modelDir(spec)
        setDownloading(spec.id, true, 0f, DownloadStats())
        val totalSize = querySize(uri)
        try {
            dir.deleteRecursively()
            dir.mkdirs()
            val resolver = context.contentResolver
            val displayName = queryDisplayName(uri)
            val input = resolver.openInputStream(uri) ?: throw RuntimeException("无法打开所选文件")
            var extracted = 0
            val isTarBz2 = displayName.endsWith(".tar.bz2", true) || displayName.endsWith(".tbz2", true) ||
                    displayName.endsWith(".bz2", true)
            if (isTarBz2) {
                extracted = extractTarBz2Stream(input, dir, totalSize) { done ->
                    reportImportProgress(spec.id, done, totalSize)
                }
            } else {
                extracted = extractZipStream(input, dir, totalSize) { done ->
                    reportImportProgress(spec.id, done, totalSize)
                }
            }
            if (extracted == 0) throw RuntimeException("压缩包里没有可用的模型文件")
            pruneRedundantFp32(dir)
            if (!isInstalled(spec)) throw RuntimeException("导入后仍缺少必要文件，请确认压缩包内容与所选模型一致")
            refresh()
            true
        } catch (e: Exception) {
            Log.e(TAG, "导入失败: ${e.message}", e)
            dir.deleteRecursively()
            refresh()
            false
        } finally {
            setDownloading(spec.id, false, 0f, null)
        }
    }

    /** 导入进度：按「已解压字节 / 压缩包大小」近似估算（解压率 > 1 时钳到 99%）。 */
    private fun reportImportProgress(specId: String, done: Long, totalSize: Long) {
        if (totalSize <= 0) return
        setDownloading(
            specId, true,
            (done.toFloat() / totalSize).coerceIn(0f, 0.99f),
            DownloadStats(totalBytes = totalSize, doneBytes = done.coerceAtMost(totalSize))
        )
    }

    /**
     * 流式解压 zip。路径规则与 [extractTarBz2Stream] 一致：剥掉单层顶层目录、保留子目录结构、
     * 跳过 macOS 元数据（._*）与 test_wavs。返回提取的文件数。
     */
    private fun extractZipStream(input: java.io.InputStream, destDir: File, totalSize: Long, onProgress: (Long) -> Unit): Int {
        var extracted = 0
        var done = 0L
        ZipInputStream(BufferedInputStream(input)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val rel = archiveEntryRelPath(entry.name)
                if (!entry.isDirectory && rel != null) {
                    val outFile = File(destDir, rel)
                    if (outFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var n: Int
                            while (zis.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n)
                                done += n
                                onProgress(done)
                            }
                        }
                        extracted++
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return extracted
    }

    /**
     * 流式解压 tar.bz2（k2-fsa 官方模型整包格式）。返回提取的文件数。
     * 规则：剥掉单层顶层目录（如 `sherpa-onnx-xxx/`）、保留 tokenizer/ 等子目录结构、
     * 跳过 macOS 元数据（._*）与 test_wavs。
     */
    private fun extractTarBz2Stream(input: java.io.InputStream, destDir: File, totalSize: Long, onProgress: (Long) -> Unit): Int {
        var extracted = 0
        var done = 0L
        var lastReport = 0L
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(input))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val rel = archiveEntryRelPath(entry.name)
                if (!entry.isDirectory && rel != null) {
                    val outFile = File(destDir, rel)
                    // 防路径穿越
                    if (outFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out ->
                            val buf = ByteArray(128 * 1024)
                            var n: Int
                            while (tar.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n)
                                done += n
                                if (done - lastReport >= 8 * 1024 * 1024) {
                                    lastReport = done
                                    onProgress(done)
                                }
                            }
                        }
                        extracted++
                    }
                }
                entry = tar.nextEntry
            }
        }
        onProgress(done)
        return extracted
    }

    /**
     * 归档内路径 → 模型目录内相对路径：
     * 剥掉单层顶层包名目录（k2-fsa 整包约定）；`tokenizer/` 开头的子目录路径与平铺文件原样保留；
     * 跳过目录项、macOS 元数据（._*）、test_wavs 与隐藏文件。不可用时返回 null。
     */
    private fun archiveEntryRelPath(rawName: String): String? {
        val name = rawName.trimStart('/')
        if (name.isEmpty() || name.endsWith("/")) return null
        val rel = when {
            !name.contains('/') -> name
            name.startsWith("tokenizer/") -> name  // 已是目标子目录结构（平铺 zip），原样保留
            else -> name.substringAfter('/')       // k2-fsa 单层顶层包名目录，剥掉
        }
        if (rel.isEmpty()) return null
        val firstSeg = rel.substringBefore('/')
        if (firstSeg == "test_wavs") return null
        val base = File(rel).name
        if (base.startsWith("._") || base.startsWith(".")) return null
        return rel
    }

    /** 解压/导入后清理：同目录若有 `X.int8.onnx` 又有 fp32 的 `X.onnx`，删 fp32 版省空间。 */
    private fun pruneRedundantFp32(dir: File) {
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".int8.onnx") }.forEach { int8 ->
            val fp32 = File(int8.parentFile, int8.name.removeSuffix(".int8.onnx") + ".onnx")
            if (fp32.exists() && fp32.length() > int8.length()) {
                Log.i(TAG, "删除冗余 fp32 模型（保留 int8）: ${fp32.name} (${fp32.length() / 1024 / 1024}MB)")
                fp32.delete()
            }
        }
    }

    /** 就地解压模型目录里的 tar.bz2 整包（下载通道的解压步骤）。 */
    private fun extractTarBz2(archive: File, destDir: File) {
        archive.inputStream().use { input ->
            val n = extractTarBz2Stream(input, destDir, archive.length()) { }
            if (n == 0) throw RuntimeException("模型包解压失败：未提取到文件")
        }
    }

    private fun queryDisplayName(uri: Uri): String = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else ""
        } ?: ""
    } catch (e: Exception) { "" }

    // =============================================================================
    // 关键：单文件多段并行下载
    // =============================================================================

    /** 进度快照：封装每段的本段字节进度 → 通过 ticker 安全合并到全局进度。 */
    private class DownloadTicker(val specId: String, initialTotalBytes: Long) {
        @Volatile var lastEmitAt: Long = 0
        @Volatile var wallStart: Long = System.currentTimeMillis()
        /** 全局总字节数（探测到真实大小后可修正）。 */
        @Volatile var totalBytes: Long = initialTotalBytes
        /** 全局 done bytes 视图。 */
        val globalDone = AtomicLong(0L)

        /** 段完成后增量计数。 */
        fun add(deltaBytes: Long) = globalDone.addAndGet(deltaBytes)

        /** 探测到文件真实大小与声明不符时修正总量。 */
        fun adjustTotal(deltaBytes: Long) {
            totalBytes = (totalBytes + deltaBytes).coerceAtLeast(1L)
        }

        /**
         * 每 200ms 才会更新 UI。force=true 跳过节流（用于状态切换点：文件开始/结束、合并完成等）。
         * 通过 [onTick] 回调把当前累计进度推给外层（最终会调用 setDownloading）。
         */
        fun maybeTick(force: Boolean = false, onTick: (doneBytes: Long, speedBps: Long, etaSec: Long) -> Unit) {
            val now = System.currentTimeMillis()
            if (!force && now - lastEmitAt < 200) return
            val done = globalDone.get().coerceAtMost(totalBytes)
            val elapsed = (now - wallStart).coerceAtLeast(1)
            val speedBps = (done.toDouble() / elapsed * 1000.0).toLong()
            val remaining = (totalBytes - done).coerceAtLeast(0L)
            val eta = if (speedBps > 1) (remaining / speedBps) else -1L
            lastEmitAt = now
            onTick(done, speedBps, eta)
        }
    }

    private suspend fun downloadFileParallel(
        spec: AsrModelSpec,
        fs: ModelFileSpec,
        dir: File,
        urls: List<String>,
        actualSize: Long,
        total: Long,
        ticker: DownloadTicker
    ): Boolean {
        val finalFile = File(dir, fs.filename)
        // 文件名可能带子目录（如 Qwen3 的 tokenizer/merges.txt），先建父目录
        finalFile.parentFile?.mkdirs()

        // 已有完整文件（按可接受大小判定）就直接跳过
        if (finalFile.exists() && isSizeAcceptable(finalFile.length(), fs.sizeBytes)) {
            Log.i(TAG, "${fs.filename} 已存在且大小可接受，跳过")
            ticker.add(finalFile.length())
            ticker.maybeTick(force = true) { done, speed, eta ->
                pushDownloadProgressFromValues(spec.id, ticker.totalBytes, done, speed, eta)
            }
            return true
        }

        // 任何已有段文件长度都视作「段续传」起点
        val chunks = planChunks(actualSize)
        Log.i(TAG, "${fs.filename} 切分为 ${chunks.size} 段 (size=$actualSize)")

        // 已有段字节总和（只在调用 downloadChunk* 之前加一次，避免段内部再加一次重复）
        var alreadyDone = 0L
        for (chunk in chunks) {
            val part = chunkFile(dir, fs.filename, chunk.index)
            if (part.exists()) alreadyDone += part.length().coerceAtMost(chunk.length)
        }
        if (alreadyDone > 0) ticker.add(alreadyDone)

        val results: List<Boolean> = coroutineScope {
            chunks.map { chunk ->
                async(Dispatchers.IO) {
                    downloadChunkWithFallback(
                        spec = spec,
                        fs = fs,
                        dir = dir,
                        urls = urls,
                        chunk = chunk,
                        ticker = ticker,
                        tellTickerOnEntry = false
                    )
                }
            }.awaitAll()
        }

        if (results.all { it }) {
            // 合并所有段文件 → finalFile
            mergeChunks(dir, fs.filename, chunks.size)
            if (!isSizeAcceptable(finalFile.length(), fs.sizeBytes)) {
                Log.w(TAG, "${fs.filename} 合并后大小不符 (got ${finalFile.length()} expected≈${fs.sizeBytes})")
                return false
            }
            Log.i(TAG, "${fs.filename} 下载完成 (${finalFile.length()} bytes)")
            // 注意：下载过程中的字节增量已实时累加进 ticker，此处不再补加，避免进度虚高。
            ticker.maybeTick(force = true) { done, speed, eta ->
                pushDownloadProgressFromValues(spec.id, ticker.totalBytes, done, speed, eta)
            }
            return true
        }
        return false
    }

    /** 单段下载：按镜像顺序重试，每镜像用指数退避（200ms→4s）。 */
    private suspend fun downloadChunkWithFallback(
        spec: AsrModelSpec,
        fs: ModelFileSpec,
        dir: File,
        urls: List<String>,
        chunk: ChunkRange,
        ticker: DownloadTicker,
        tellTickerOnEntry: Boolean = true
    ): Boolean {
        var lastError: Exception? = null
        val chunkDone = AtomicLong(
            if (chunkFile(dir, fs.filename, chunk.index).exists())
                chunkFile(dir, fs.filename, chunk.index).length().coerceAtMost(chunk.length)
            else 0L
        )
        if (tellTickerOnEntry && chunkDone.get() > 0) {
            ticker.add(chunkDone.get())
        }
        for (url in urls) {
            for (attempt in 1..maxRetriesPerChunk) {
                try {
                    downloadChunkOnce(
                        url = url,
                        fs = fs,
                        dir = dir,
                        chunk = chunk,
                        ticker = ticker,
                        chunkDone = chunkDone
                    )
                    return true
                } catch (e: Exception) {
                    lastError = e
                    val backoffMs = (200L * (1L shl (attempt - 1))).coerceAtMost(4_000L)
                    Log.w(TAG, "chunk[${chunk.index}] retry attempt=$attempt mirror=${hostOf(url)}: ${e.message}; sleep ${backoffMs}ms")
                    delay(backoffMs)
                }
            }
            Log.w(TAG, "chunk[${chunk.index}] mirror=${hostOf(url)} exhausted, trying next mirror")
        }
        Log.e(TAG, "chunk[${chunk.index}] all mirrors failed: ${lastError?.message}")
        return false
    }

    /**
     * 一次段下载：发 Range 请求 → RandomAccessFile seek 到已有偏移 → 写 → 增量更新进度/速度。
     *
     * 响应处理：
     *  - **206**：正常切片，校验 Content-Range 起点与请求一致后写入。
     *  - **200**：服务器忽略了 Range 头返回全量流。仅当本段是 chunk0 且从零开始时才接受
     *    （内容本来就是文件头），写满本段长度即断开；其余情况说明该镜像不支持分段，
     *    抛异常让上层切换镜像，全部镜像都不行则整文件降级到单流下载。
     */
    private fun downloadChunkOnce(
        url: String,
        fs: ModelFileSpec,
        dir: File,
        chunk: ChunkRange,
        ticker: DownloadTicker,
        chunkDone: AtomicLong
    ) {
        val partFile = chunkFile(dir, fs.filename, chunk.index)
        val existing = if (partFile.exists()) partFile.length() else 0L
        if (existing >= chunk.length) return  // 段已经下完

        val startByte = chunk.start + existing
        val endByte = chunk.start + chunk.length - 1

        // 无条件发 Range 头：即使 chunk0 也要发，否则服务器返回 200 全量流时
        // 无法区分「应写本段」还是「误写整个文件」（旧 bug：chunk0 不发 Range，
        // 导致 part0 被写满整个文件、合并出损坏模型）。
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 BiliTranscript/2")
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=$startByte-$endByte")

        client.newCall(reqBuilder.build()).execute().use { resp ->
            val fullStreamFromZero: Boolean
            when (resp.code) {
                206 -> {
                    fullStreamFromZero = false
                    // 校验切片起点，防止异常 CDN 返回错位数据污染 .part 文件
                    val rangeStart = resp.header("Content-Range")
                        ?.substringAfter("bytes ")
                        ?.substringBefore("-")
                        ?.toLongOrNull()
                    if (rangeStart != null && rangeStart != startByte) {
                        throw RuntimeException("Content-Range 起点不符: got $rangeStart, want $startByte")
                    }
                }
                200 -> {
                    if (chunk.start != 0L || existing != 0L) {
                        throw RuntimeException("HTTP 200: 服务器不支持 Range（忽略分段/续传请求）")
                    }
                    fullStreamFromZero = true
                }
                else -> throw RuntimeException("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw RuntimeException("empty body")

            RandomAccessFile(partFile, "rw").use { raf ->
                raf.seek(existing)
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    var totalReadForChunk = existing
                    val targetChunkLength = chunk.length
                    while (input.read(buf).also { n = it } != -1) {
                        val toWrite = if (fullStreamFromZero) {
                            // 全量流：只取本段应有的头部字节，写满即停
                            val remainingInChunk = targetChunkLength - totalReadForChunk
                            if (remainingInChunk <= 0) break
                            n.toLong().coerceAtMost(remainingInChunk).toInt()
                        } else n

                        raf.write(buf, 0, toWrite)
                        val delta = toWrite.toLong()
                        totalReadForChunk += delta
                        chunkDone.addAndGet(delta)
                        ticker.add(delta)
                        ticker.maybeTick(force = false) { done, speed, eta ->
                            pushDownloadProgressFromValues(
                                specId = ticker.specId,
                                total = ticker.totalBytes,
                                done = done,
                                speedBps = speed,
                                etaSec = eta
                            )
                        }
                        if (fullStreamFromZero && totalReadForChunk >= targetChunkLength) {
                            break
                        }
                    }
                }
            }
        }
    }

    /**
     * 单流连续下载兜底（当服务器/镜像源不支持 HTTP Range 206 时使用）。
     */
    private suspend fun downloadSingleStream(
        spec: AsrModelSpec,
        fs: ModelFileSpec,
        dir: File,
        urls: List<String>,
        ticker: DownloadTicker
    ): Boolean {
        val finalFile = File(dir, fs.filename)
        var lastErr: Exception? = null
        for (url in urls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 BiliTranscript/2")
                    .header("Accept-Encoding", "identity")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 206) throw RuntimeException("HTTP ${resp.code}")
                    val body = resp.body ?: throw RuntimeException("empty body")
                    val tempFile = File(dir, "${fs.filename}.tmp")
                    if (tempFile.exists()) tempFile.delete()
                    tempFile.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            var n: Int
                            while (input.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n)
                                ticker.add(n.toLong())
                                ticker.maybeTick(force = false) { done, speed, eta ->
                                    pushDownloadProgressFromValues(spec.id, ticker.totalBytes, done, speed, eta)
                                }
                            }
                        }
                    }
                    // 校验完整性：防止运营商劫持/错误页（200 + HTML）被当作模型文件
                    val downloadedSize = tempFile.length()
                    if (!isSizeAcceptable(downloadedSize, fs.sizeBytes)) {
                        tempFile.delete()
                        throw RuntimeException("单流下载大小不符 (got $downloadedSize, expected≈${fs.sizeBytes})")
                    }
                    if (tempFile.renameTo(finalFile) || (finalFile.delete() && tempFile.renameTo(finalFile))) {
                        Log.i(TAG, "单流下载成功: ${fs.filename}")
                        return true
                    }
                }
            } catch (e: Exception) {
                lastErr = e
                Log.w(TAG, "镜像 ${hostOf(url)} 单流下载失败: ${e.message}")
            }
        }
        Log.e(TAG, "所有镜像单流下载均失败: ${lastErr?.message}")
        return false
    }

    private fun pushDownloadProgressFromValues(
        specId: String,
        total: Long,
        done: Long,
        speedBps: Long,
        etaSec: Long
    ) {
        val progress = (done.toFloat() / total).coerceIn(0f, 1f)
        val mirror = _statuses.value.firstOrNull { it.spec.id == specId }?.stats?.mirror ?: ""
        setDownloading(
            specId, true, progress,
            DownloadStats(
                bytesPerSec = speedBps,
                etaSec = etaSec,
                mirror = mirror,
                totalBytes = total,
                doneBytes = done
            )
        )
    }

    // =============================================================================
    // 镜像直链解析与测速
    // =============================================================================

    private data class MirrorProbe(
        val originalUrl: String,
        val latencyMs: Long,
        val totalSizeBytes: Long,
        val ok: Boolean
    )

    /**
     * 发送 Range: bytes=0-0 探测：
     * 1. 确认镜像可达（跟随 302 验证整条链路），并测量延迟用于排序
     * 2. 从 Content-Range / Content-Length 提取文件精准字节数
     * 3. 返回按延迟升序的**原始镜像 URL** 列表 —— 刻意不使用 302 后的 CDN 直链：
     *    HF/hf-mirror 的签名直链（X-Amz-Expires=3600）仅 1 小时有效，大文件慢速下载
     *    中途重试会 403 导致全盘失败；用原始 URL 让每次请求都重新跟随重定向拿新签名。
     */
    private suspend fun probeMirrorsAndResolve(urls: List<String>, fallbackSize: Long): Pair<List<String>, Long> = coroutineScope {
        val probes: List<Deferred<MirrorProbe>> = urls.map { url ->
            async(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                var latency = Long.MAX_VALUE
                var size = -1L
                var ok = false
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 BiliTranscript/2")
                        .header("Range", "bytes=0-0")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful || resp.code == 206) {
                            ok = true
                            latency = System.currentTimeMillis() - t0
                            val rangeHeader = resp.header("Content-Range")
                            if (rangeHeader != null && rangeHeader.contains("/")) {
                                size = rangeHeader.substringAfter("/").toLongOrNull() ?: -1L
                            }
                            if (size <= 0) {
                                size = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "镜像探测失败 ${hostOf(url)}: ${e.message}")
                }
                MirrorProbe(url, latency, size, ok)
            }
        }
        val results = probes.awaitAll()
        val okProbes = results.filter { it.ok }.sortedBy { it.latencyMs }
        val failedProbes = results.filter { !it.ok }

        // 可用镜像按延迟升序，不可用镜像排在最后兜底；全部使用原始 URL（见上方注释）
        val sortedUrls = (okProbes.map { it.originalUrl } + failedProbes.map { it.originalUrl }).distinct()
        val detectedSize = okProbes.firstOrNull { it.totalSizeBytes > 0 }?.totalSizeBytes ?: fallbackSize

        Log.i(TAG, "镜像解析探测完成: 发现 ${okProbes.size}/${results.size} 个可用通道, 精确大小=$detectedSize bytes")
        Pair(sortedUrls, detectedSize)
    }

    // =============================================================================
    // 切分与合并
    // =============================================================================

    /** 一段下载的字节范围。 */
    private data class ChunkRange(
        val index: Int,
        val start: Long,           // 包含
        val length: Long           // 字节数
    )

    /** 段文件命名：`<basename>.part<N>`。 */
    private fun chunkFile(dir: File, filename: String, index: Int): File {
        val name = "$filename$PART_SUFFIX$index"
        return File(dir, name)
    }

    /** 按文件大小切分。 */
    private fun planChunks(fileSize: Long): List<ChunkRange> {
        if (fileSize <= 0) {
            return listOf(ChunkRange(0, 0, 0))
        }
        val wantCount = maxChunksPerFile.coerceAtLeast(1)
        val chunkLen = (fileSize + wantCount - 1) / wantCount
        val targetCount = if (chunkLen < minChunkBytes) 1 else wantCount.coerceAtMost(
            (fileSize / minChunkBytes).toInt().coerceAtLeast(1)
        )
        val realChunkLen = (fileSize + targetCount - 1) / targetCount
        val out = mutableListOf<ChunkRange>()
        var pos = 0L
        var idx = 0
        while (pos < fileSize) {
            val len = min(realChunkLen, fileSize - pos)
            out += ChunkRange(idx, pos, len)
            pos += len
            idx++
        }
        return out
    }

    /**
     * 把所有 `.part<N>` 顺序合并到 finalFile。段命名约定：basename + .part + 0..N-1。
     */
    private suspend fun mergeChunks(dir: File, filename: String, expectedChunks: Int) {
        val finalFile = File(dir, filename)
        if (finalFile.exists()) finalFile.delete()
        finalFile.outputStream().use { out ->
            val buf = ByteArray(256 * 1024)
            for (i in 0 until expectedChunks) {
                val part = chunkFile(dir, filename, i)
                if (!part.exists()) {
                    Log.w(TAG, "merge: missing chunk $i for $filename")
                    continue
                }
                part.inputStream().use { input ->
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        for (i in 0 until expectedChunks) {
            chunkFile(dir, filename, i).delete()
        }
    }

    // =============================================================================
    // 私有工具
    // =============================================================================

    /** 宽松大小校验（双向）：过小是残件，过大是写入串扰/全量流误写产生的坏文件。 */
    private fun isSizeAcceptable(actual: Long, declared: Long): Boolean {
        if (actual <= 0) return false
        if (declared <= 0) return true
        return if (declared < 2_000_000) actual >= declared / 2 && actual <= declared * 2
        else actual >= declared * 9 / 10 && actual <= declared * 11 / 10
    }

    private fun querySize(uri: Uri): Long = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else -1L
        } ?: -1L
    } catch (e: Exception) { -1L }

    /** 进度更新入口。 */
    private fun setDownloading(id: String, downloading: Boolean, progress: Float, stats: DownloadStats?) {
        _statuses.value = _statuses.value.map {
            if (it.spec.id == id) it.copy(
                installed = if (!downloading) isInstalled(it.spec) else it.installed,
                downloading = downloading,
                progress = progress,
                stats = stats
            ) else it
        }
    }

    /** 更新当前下载的统计字段（速度/ETA/镜像）而不重置 downloading flag。 */
    private fun updateStats(id: String, transform: (DownloadStats) -> DownloadStats) {
        _statuses.value = _statuses.value.map {
            if (it.spec.id == id) {
                val cur = it.stats ?: DownloadStats()
                it.copy(stats = transform(cur))
            } else it
        }
    }

    private fun hostOf(url: String): String =
        try { java.net.URI(url).host ?: url } catch (e: Exception) { url }

    companion object {
        private const val TAG = "ModelManager"

        /** 单文件最多并行段数。4 段可在大带宽下保持稳定下载且不触发镜像源限流。 */
        private const val MAX_CHUNKS_PER_FILE = 4

        /** 每段最小大小；小文件就不要分了。 */
        private const val MIN_CHUNK_BYTES = 6L * 1024 * 1024  // 6 MB

        /** 每段在切到下一镜像前最多重试次数。 */
        private const val MAX_RETRIES_PER_CHUNK = 3

        private const val HF_MIRROR = "https://hf-mirror.com/csukuangfj"
        private const val HF_ORIGIN = "https://huggingface.co/csukuangfj"

        /** ModelScope（魔搭）zhaochaoqun 的 sherpa-onnx 官方模型国内镜像仓。 */
        private const val MS_ZHAO = "https://modelscope.cn/models/zhaochaoqun/sherpa-onnx-asr-models/resolve/master"

        /** ModelScope wuyangwang 的 Fun-ASR-Nano 单文件仓（与 zhaochaoqun 互为备份）。 */
        private const val MS_WUYANG_NANO = "https://modelscope.cn/models/wuyangwang/funasr-nano/resolve/master/funasr"

        /** ModelScope jkman2023 的 Qwen3-ASR 0.6B sherpa-onnx 官方拆包单文件仓。 */
        private const val MS_QWEN3 = "https://modelscope.cn/models/jkman2023/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/master"
        private const val HF_QWEN3 = "$HF_ORIGIN/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main"
        private const val HFM_QWEN3 = "$HF_MIRROR/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main"

        val BUNDLED_SENSEVOICE = AsrModelSpec(
            id = "sensevoice",
            name = "SenseVoice（内置保底）",
            engine = AsrEngine.SENSEVOICE,
            bundled = true,
            approxSizeMb = 230,
            description = "已内置，免下载。快，中/英/日/韩/粤，干净人声很好；有背景音乐时较弱。",
            tier = ModelTier.BUNDLED,
            longDescription = "SenseVoice Small（2024），内置免下载、开箱即用。识别速度极快，适合干净人声的日常视频；遇背景音乐/远场时精度下降，作为所有模型的安全保底。",
            downloadReliability = 100,
            reliabilityNote = "APK 内置，免下载、即装即有"
        )

        /** 轻档：Fun-ASR-Nano（SenseVoice 架构新一代），双 ModelScope 源。 */
        val FUNASR_NANO = AsrModelSpec(
            id = "funasr-nano",
            name = "Fun-ASR-Nano（轻档·推荐）",
            engine = AsrEngine.SENSEVOICE,
            bundled = false,
            approxSizeMb = 252,
            description = "阿里 2025 新模型。远场/高噪/口音大幅增强，速度依旧极快。",
            tier = ModelTier.LIGHT,
            longDescription = "阿里 Fun-ASR-Nano（2025-12），SenseVoice 架构新一代：针对远场、高噪、背景音乐深度优化，支持 7 大方言与 26 种地方口音，中英混说更稳，速度极快。",
            downloadReliability = 98,
            reliabilityNote = "双 ModelScope 国内镜像互备，实测 4MB/s+ 高速",
            files = listOf(
                ModelFileSpec(
                    "model.int8.onnx",
                    listOf(
                        "$MS_ZHAO/sherpa-onnx-sense-voice-funasr-nano-int8-2025-12-17/model.int8.onnx",
                        "$MS_WUYANG_NANO/model.int8.onnx"
                    ),
                    263_531_902
                ),
                ModelFileSpec(
                    "tokens.txt",
                    listOf(
                        "$MS_ZHAO/sherpa-onnx-sense-voice-funasr-nano-int8-2025-12-17/tokens.txt",
                        "$MS_WUYANG_NANO/tokens.txt"
                    ),
                    939_815
                )
            )
        )

        /** 中档：Paraformer-large 三语（中英粤），ModelScope 整包 + 自动解压。 */
        val PARAFORMER_TRI = AsrModelSpec(
            id = "paraformer-trilingual",
            name = "Paraformer 三语（中档）",
            engine = AsrEngine.PARAFORMER,
            bundled = false,
            approxSizeMb = 1010,
            description = "阿里工业级中文 ASR，自带标点，中英粤混合识别强。",
            tier = ModelTier.MID,
            longDescription = "阿里 Paraformer-large 三语版（中/英/粤），工业级中文识别精度，输出自带标点，方言口音覆盖好，速度远快于同精度大模型。整包 1GB 下载后自动解压（实际占用约 245MB）。",
            downloadReliability = 92,
            reliabilityNote = "ModelScope 单镜像整包，下载后自动解压",
            extractTarBz2 = true,
            files = listOf(
                ModelFileSpec(
                    "paraformer-trilingual.tar.bz2",
                    listOf("$MS_ZHAO/sherpa-onnx-paraformer-trilingual-zh-cantonese-en.tar.bz2"),
                    1_059_453_702
                )
            )
        )

        /** 重档：Qwen3-ASR 0.6B（LLM 听写，当前精度天花板），单文件多段下载。 */
        val QWEN3_0_6B = AsrModelSpec(
            id = "qwen3-asr-0.6b",
            name = "Qwen3-ASR 0.6B（重档·最准）",
            engine = AsrEngine.QWEN3,
            bundled = false,
            approxSizeMb = 941,
            description = "通义 2026 LLM 听写模型，52 语言+22 方言，精度天花板。",
            tier = ModelTier.HEAVY,
            longDescription = "通义 Qwen3-ASR 0.6B（2026-03），基于大语言模型的听写模型：支持 52 种语言与 22 种中国方言，上下文理解强、噪声鲁棒，当前精度天花板；速度较慢、占用最高。",
            downloadReliability = 90,
            reliabilityNote = "ModelScope 单文件镜像 + HF 备用，分段断点续传",
            files = listOf(
                ModelFileSpec(
                    "conv_frontend.onnx",
                    listOf(
                        "$MS_QWEN3/conv_frontend.onnx",
                        "$HF_QWEN3/conv_frontend.onnx?download=true",
                        "$HFM_QWEN3/conv_frontend.onnx?download=true"
                    ),
                    44_148_281
                ),
                ModelFileSpec(
                    "encoder.int8.onnx",
                    listOf(
                        "$MS_QWEN3/encoder.int8.onnx",
                        "$HF_QWEN3/encoder.int8.onnx?download=true",
                        "$HFM_QWEN3/encoder.int8.onnx?download=true"
                    ),
                    182_491_662
                ),
                ModelFileSpec(
                    "decoder.int8.onnx",
                    listOf(
                        "$MS_QWEN3/decoder.int8.onnx",
                        "$HF_QWEN3/decoder.int8.onnx?download=true",
                        "$HFM_QWEN3/decoder.int8.onnx?download=true"
                    ),
                    755_914_231
                ),
                ModelFileSpec(
                    "tokenizer/merges.txt",
                    listOf(
                        "$MS_QWEN3/tokenizer/merges.txt",
                        "$HF_QWEN3/tokenizer/merges.txt?download=true",
                        "$HFM_QWEN3/tokenizer/merges.txt?download=true"
                    ),
                    1_671_853
                ),
                ModelFileSpec(
                    "tokenizer/vocab.json",
                    listOf(
                        "$MS_QWEN3/tokenizer/vocab.json",
                        "$HF_QWEN3/tokenizer/vocab.json?download=true",
                        "$HFM_QWEN3/tokenizer/vocab.json?download=true"
                    ),
                    2_776_833
                ),
                ModelFileSpec(
                    "tokenizer/tokenizer_config.json",
                    listOf(
                        "$MS_QWEN3/tokenizer/tokenizer_config.json",
                        "$HF_QWEN3/tokenizer/tokenizer_config.json?download=true",
                        "$HFM_QWEN3/tokenizer/tokenizer_config.json?download=true"
                    ),
                    12_487
                )
            )
        )

        val CATALOG = listOf(BUNDLED_SENSEVOICE, FUNASR_NANO, PARAFORMER_TRI, QWEN3_0_6B)
    }
}
