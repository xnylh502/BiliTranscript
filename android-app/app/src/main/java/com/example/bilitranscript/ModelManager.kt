package com.example.bilitranscript

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** 模型文件（下载用） */
data class ModelFileSpec(val filename: String, val url: String, val sizeBytes: Long)

/** 一个可选识别模型的元信息 */
data class AsrModelSpec(
    val id: String,
    val name: String,
    val engine: AsrEngine,
    /** true = 内置在 APK assets 里（免下载）；false = 需下载到手机存储 */
    val bundled: Boolean,
    val approxSizeMb: Int,
    val description: String,
    val files: List<ModelFileSpec> = emptyList()
)

/** 模型在本机的状态 */
data class ModelStatus(
    val spec: AsrModelSpec,
    val installed: Boolean,
    val downloading: Boolean,
    val progress: Float
)

/**
 * 模型仓库：模型**不打进 APK**，而是下载/推送到手机存储
 * （`getExternalFilesDir/models/<id>/`），App 自动检测、可选用、可删除。
 *
 * 国内 HuggingFace 手机常下不动，所以也支持「电脑下好后 adb push 进同一目录」，
 * App 一样自动识别为已安装。
 */
class ModelManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _statuses = MutableStateFlow(scan())
    val statuses: StateFlow<List<ModelStatus>> = _statuses

    fun catalog(): List<AsrModelSpec> = CATALOG

    fun specById(id: String): AsrModelSpec? = CATALOG.firstOrNull { it.id == id }

    /** 选中的模型若未安装，回落到内置 SenseVoice。 */
    fun resolveUsable(selectedId: String): AsrModelSpec {
        val spec = specById(selectedId) ?: BUNDLED_SENSEVOICE
        return if (isInstalled(spec)) spec else BUNDLED_SENSEVOICE
    }

    // 用内部 filesDir：App 私有、可靠（不受 MIUI 对 Android/data 的访问限制）。
    // App 内下载、删除、以及 adb 经 run-as 投递都落在这里。
    fun modelsRoot(): File = File(context.filesDir, "models")

    fun modelDir(spec: AsrModelSpec): File = File(modelsRoot(), spec.id)

    /**
     * 按「文件类型」判断是否装好，而不是死磕精确文件名——
     * 这样无论是 App 内下载、adb 推送、还是导入压缩包（文件名可能不同），都能正确识别。
     */
    fun isInstalled(spec: AsrModelSpec): Boolean {
        if (spec.bundled) return true
        val dir = modelDir(spec)
        if (!dir.isDirectory) return false
        val names = dir.list()?.toList() ?: return false
        return when (spec.engine) {
            AsrEngine.WHISPER ->
                names.any { it.contains("encoder") && it.endsWith(".onnx") } &&
                    names.any { it.contains("decoder") && it.endsWith(".onnx") } &&
                    names.any { it.endsWith("tokens.txt") }
            AsrEngine.SENSEVOICE ->
                names.any { it.endsWith(".onnx") } &&
                    names.any { it.endsWith("tokens.txt") }
        }
    }

    /** 重新检测磁盘安装状态（保留正在下载中的进度）。 */
    fun refresh() {
        _statuses.value = _statuses.value.map { st -> st.copy(installed = isInstalled(st.spec)) }
    }

    fun delete(spec: AsrModelSpec) {
        if (spec.bundled) return
        modelDir(spec).deleteRecursively()
        refresh()
    }

    /**
     * 在 App 内下载模型（国内可能需要代理）。逐个文件下载到 modelDir。
     * @return 成功 true
     */
    suspend fun download(spec: AsrModelSpec): Boolean = withContext(Dispatchers.IO) {
        if (spec.bundled || spec.files.isEmpty()) return@withContext true
        val dir = modelDir(spec).apply { mkdirs() }
        setDownloading(spec.id, true, 0f)
        val total = spec.files.sumOf { it.sizeBytes }.coerceAtLeast(1L)
        var doneBytes = 0L
        try {
            for (fs in spec.files) {
                val out = File(dir, fs.filename)
                val req = Request.Builder().url(fs.url)
                    .header("User-Agent", "Mozilla/5.0").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} @ ${fs.filename}")
                    val body = resp.body ?: throw RuntimeException("空响应 @ ${fs.filename}")
                    body.byteStream().use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var n: Int
                            while (input.read(buf).also { n = it } != -1) {
                                output.write(buf, 0, n)
                                doneBytes += n
                                setDownloading(spec.id, true, (doneBytes.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            refresh()
            true
        } catch (e: Exception) {
            Log.e(TAG, "下载失败: ${e.message}", e)
            modelDir(spec).deleteRecursively()
            refresh()
            false
        } finally {
            setDownloading(spec.id, false, 0f)
        }
    }

    /**
     * 从用户选择的 .zip 导入模型（解压到 modelDir）。
     * 国内下不动时的主力路径：电脑/浏览器下好模型压缩包 → 手机里「导入」选它 → 自动装好。
     * 压缩包里直接放模型文件（*.onnx / *.txt 即可，子目录会被拍平）。
     * @return 成功 true
     */
    suspend fun importFromZip(spec: AsrModelSpec, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (spec.bundled) return@withContext true
        val dir = modelDir(spec)
        setDownloading(spec.id, true, 0f)
        val totalSize = querySize(uri)
        var done = 0L
        try {
            dir.deleteRecursively()
            dir.mkdirs()
            val resolver = context.contentResolver
            val input = resolver.openInputStream(uri) ?: throw RuntimeException("无法打开所选文件")
            var extracted = 0
            ZipInputStream(BufferedInputStream(input)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // 只取基础文件名，防 zip-slip 路径穿越
                        val name = File(entry.name).name
                        if (name.endsWith(".onnx") || name.endsWith(".txt")) {
                            File(dir, name).outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var n: Int
                                while (zis.read(buf).also { n = it } != -1) {
                                    out.write(buf, 0, n)
                                    done += n
                                    if (totalSize > 0) {
                                        setDownloading(spec.id, true, (done.toFloat() / totalSize).coerceIn(0f, 0.99f))
                                    }
                                }
                            }
                            extracted++
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            if (extracted == 0) throw RuntimeException("压缩包里没有模型文件（.onnx / .txt）")
            if (!isInstalled(spec)) throw RuntimeException("导入后仍缺少必要文件，请确认压缩包内容")
            refresh()
            true
        } catch (e: Exception) {
            Log.e(TAG, "导入失败: ${e.message}", e)
            dir.deleteRecursively()
            refresh()
            false
        } finally {
            setDownloading(spec.id, false, 0f)
        }
    }

    private fun querySize(uri: Uri): Long = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else -1L
        } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    // ---- 私有 ----

    private fun scan(): List<ModelStatus> = CATALOG.map { spec ->
        ModelStatus(spec = spec, installed = isInstalled(spec), downloading = false, progress = 0f)
    }

    private fun setDownloading(id: String, downloading: Boolean, progress: Float) {
        _statuses.value = _statuses.value.map {
            if (it.spec.id == id) it.copy(
                installed = if (!downloading) isInstalled(it.spec) else it.installed,
                downloading = downloading,
                progress = progress
            ) else it
        }
    }

    companion object {
        private const val TAG = "ModelManager"
        private const val HF = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-medium/resolve/main"
        private const val HF_LARGE = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-large-v3/resolve/main"

        val BUNDLED_SENSEVOICE = AsrModelSpec(
            id = "sensevoice",
            name = "SenseVoice（内置）",
            engine = AsrEngine.SENSEVOICE,
            bundled = true,
            approxSizeMb = 230,
            description = "已内置，免下载。快，中/英/日/韩/粤，干净人声很好；有背景音乐时较弱。"
        )

        val WHISPER_MEDIUM = AsrModelSpec(
            id = "whisper-medium",
            name = "Whisper medium",
            engine = AsrEngine.WHISPER,
            bundled = false,
            approxSizeMb = 950,
            description = "更抗噪/音乐、多语种更稳，但更慢。约 950MB，下载到手机存储。",
            files = listOf(
                ModelFileSpec("medium-encoder.int8.onnx", "$HF/medium-encoder.int8.onnx", 374_200_000),
                ModelFileSpec("medium-decoder.int8.onnx", "$HF/medium-decoder.int8.onnx", 571_100_000),
                ModelFileSpec("medium-tokens.txt", "$HF/medium-tokens.txt", 800_000)
            )
        )

        val WHISPER_LARGE_V3 = AsrModelSpec(
            id = "whisper-large-v3",
            name = "Whisper large-v3",
            engine = AsrEngine.WHISPER,
            bundled = false,
            approxSizeMb = 1770,
            description = "最准、最抗造，但很慢、很大（约 1.77GB）。仅在确实需要时安装。",
            files = listOf(
                ModelFileSpec("large-v3-encoder.int8.onnx", "$HF_LARGE/large-v3-encoder.int8.onnx", 766_671_985),
                ModelFileSpec("large-v3-decoder.int8.onnx", "$HF_LARGE/large-v3-decoder.int8.onnx", 1_008_300_000),
                ModelFileSpec("large-v3-tokens.txt", "$HF_LARGE/large-v3-tokens.txt", 800_000)
            )
        )

        val CATALOG = listOf(BUNDLED_SENSEVOICE, WHISPER_MEDIUM, WHISPER_LARGE_V3)
    }
}
