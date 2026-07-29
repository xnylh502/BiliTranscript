package com.example.bilitranscript

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 语音识别引擎 - sherpa-onnx 离线识别 与 云端 API ASR。
 *
 * 模型来源两种，由 [ModelManager] 解析当前选中的模型决定：
 *  - 内置（bundled）：SenseVoice，从 APK assets 加载（OfflineRecognizer(assets, ...)）
 *  - 下载（downloaded）：Whisper 等，从手机存储文件路径加载（OfflineRecognizer(config=...)，AssetManager 为 null）
 *
 * 选中的模型若未安装，自动回落到内置 SenseVoice，绝不崩。
 * 通过 [ensureReady] 按「模型id/语言/线程/provider/云端设置」签名惰性重建，避免每次提取都重载。
 */
class SpeechRecognizer(
    private val context: Context,
    private val modelManager: ModelManager
) {

    companion object {
        private const val TAG = "SpeechRecognizer"
        private const val SAMPLE_RATE = 16000

        /** Qwen3-ASR 分段窗口（采样点数）：30 秒。见 [recognizePcm] 中关于 KV cache OOM 的说明。 */
        private const val QWEN3_WINDOW_SAMPLES = 30 * SAMPLE_RATE

        /** VAD 分段识别的并行路数（非 QWEN3 引擎）。onnxruntime session 可并发 Run；2 路防内存翻倍。 */
        private const val VAD_PARALLEL_WORKERS = 2

        /** 高内存设备上 QWEN3 的 VAD 并行路数（每路一份 KV cache workspace，需 RAM 兜底）。 */
        private const val QWEN3_PARALLEL_WORKERS_HIGH_RAM = 2
    }

    private var recognizer: OfflineRecognizer? = null
    private var activeSource: TranscriptSource = TranscriptSource.SENSEVOICE
    private var activeName: String = "SenseVoice"
    private var configSignature: String? = null
    private var lastSettings: AppSettings? = null

    /** VAD 切句器（silero 模型内置 assets，懒加载、每次识别独立实例）。 */
    private val vadSegmenter = VadSegmenter(context)

    /**
     * 总 RAM ≥ 8GB 视为高内存设备（如 REDMI Turbo 5 Max 12/16GB 机型）：
     * 允许 Qwen3-ASR 双路并行分段识别（每路数百 MB KV cache workspace 有 RAM 兜底）。
     */
    private val highRamDevice: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem >= 8L * 1024 * 1024 * 1024
        } catch (e: Exception) {
            false
        }
    }

    private fun isHighRamDevice(): Boolean = highRamDevice

    fun getEngineName(): String = activeName
    fun currentSource(): TranscriptSource = activeSource
    fun isReady(): Boolean = recognizer != null || lastSettings?.useCloudModel == true

    fun initEngine(): Boolean = ensureReady(AppSettings())

    @Synchronized
    fun ensureReady(settings: AppSettings): Boolean {
        lastSettings = settings
        if (settings.useCloudModel) {
            val signature = "cloud|${settings.cloudApiUrl}|${settings.cloudModelName}"
            if (signature == configSignature) return true
            release()
            activeSource = TranscriptSource.WHISPER // 云端 API 默认为高精 ASR
            activeName = "云端 API (${settings.cloudModelName})"
            configSignature = signature
            Log.i(TAG, "云端 ASR 引擎就绪: $signature")
            return true
        }

        val spec = modelManager.resolveUsable(settings.selectedModelId)
        val baseThreads = when (spec.engine) {
            AsrEngine.WHISPER -> settings.numThreads.coerceIn(1, 2)
            AsrEngine.QWEN3 -> settings.numThreads.coerceIn(1, 4)   // LLM 解码串行为主，多线程收益有限
            else -> settings.numThreads.coerceIn(1, 8)
        }
        // 线程预算协调：VAD 分段并行识别时，总并发 = 并行路数 × 引擎线程。
        // 引擎线程按路数降配，使「路数 × 线程 ≈ 预算」，避免 oversubscription（争核反而更慢）。
        // 例：8 核全大核（天玑 9500s）预算 7 → 每路 ceil(7/2)=4 × 2 路 = 8，刚好打满。
        val threads = when {
            !settings.vadSegment -> baseThreads
            spec.engine == AsrEngine.QWEN3 ->
                if (isHighRamDevice()) (baseThreads + QWEN3_PARALLEL_WORKERS_HIGH_RAM - 1) / QWEN3_PARALLEL_WORKERS_HIGH_RAM
                else baseThreads
            else -> ((baseThreads + VAD_PARALLEL_WORKERS - 1) / VAD_PARALLEL_WORKERS).coerceAtLeast(2)
        }
        val provider = if (settings.useNnapi) "nnapi" else "cpu"
        val signature = "${spec.id}|${settings.language}|$threads|$provider"

        if (recognizer != null && signature == configSignature) return true

        return try {
            release()
            recognizer = build(spec, settings.language, threads, provider)
            activeSource = when (spec.engine) {
                AsrEngine.WHISPER -> TranscriptSource.WHISPER
                AsrEngine.QWEN3 -> TranscriptSource.QWEN3
                AsrEngine.PARAFORMER -> TranscriptSource.PARAFORMER
                else -> TranscriptSource.SENSEVOICE
            }
            activeName = spec.name
            configSignature = signature
            Log.i(TAG, "引擎就绪: $signature")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "模型 ${spec.id} 初始化失败（内存不足或文件异常）: ${t.message}，自动回落到内置 SenseVoice", t)
            release()
            try {
                val fallbackSpec = ModelManager.BUNDLED_SENSEVOICE
                recognizer = build(fallbackSpec, settings.language, 2, provider)
                activeSource = TranscriptSource.SENSEVOICE
                activeName = "${fallbackSpec.name} (已安全降级)"
                configSignature = "fallback|${fallbackSpec.id}"
                Log.i(TAG, "已安全降级到内置 SenseVoice 引擎")
                true
            } catch (fallbackErr: Throwable) {
                Log.e(TAG, "降级内置引擎也失败: ${fallbackErr.message}", fallbackErr)
                recognizer = null
                configSignature = null
                false
            }
        }
    }

    /** 识别一段已解码的 16kHz 单声道 PCM。 */
    suspend fun recognizePcm(samples: FloatArray, onProgress: ((Float) -> Unit)? = null): SpeechRecognition {
        val settings = lastSettings ?: AppSettings()
        if (samples.isEmpty()) return SpeechRecognition("（未能识别到语音内容）")

        if (settings.useCloudModel) {
            return SpeechRecognition(recognizePcmCloud(samples, settings, onProgress))
        }

        val rec = recognizer ?: throw IllegalStateException("语音识别引擎未初始化")

        // ---- VAD 智能切句（默认开）：跳过纯音乐/静音段，按语义段识别并产出时间轴 ----
        // 纯 BGM 段会让大模型产生幻觉文本（编歌词），切掉是「有音乐的人声文案」最大的准确度来源。
        if (settings.vadSegment && vadSegmenter.isAvailable()) {
            val chunks = vadSegmenter.segment(samples)
            if (!chunks.isNullOrEmpty()) {
                return recognizeVadChunks(rec, samples, chunks, onProgress)
            }
            Log.i(TAG, "VAD 未切出语音段（或失败），回落整段识别")
        }

        // Qwen3-ASR（LLM 自回归解码）：长音频必须分段。
        // 整段喂入时 decoder 的 KV cache 随生成文本无限增长，识别末尾内存峰值触发 OOM，
        // 在内存有限的设备（如模拟器）上直接 native 闪退。30s 硬窗逐段识别、文本拼接，
        // 把内存峰值固定在单窗口水平。
        if (activeSource == TranscriptSource.QWEN3 && samples.size > QWEN3_WINDOW_SAMPLES) {
            return SpeechRecognition(recognizePcmChunked(rec, samples, onProgress))
        }

        onProgress?.invoke(0.2f)
        val stream = rec.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        onProgress?.invoke(0.4f)
        rec.decode(stream)
        onProgress?.invoke(0.9f)
        val text = rec.getResult(stream).text
        stream.release()
        onProgress?.invoke(1f)
        return SpeechRecognition(text.ifBlank { "（未能识别到语音内容）" })
    }

    /**
     * VAD 分段识别：逐段 decode、段间换行拼接、产出逐句时间轴。
     * - 非 QWEN3 引擎：最多 [VAD_PARALLEL_WORKERS] 路并行（onnxruntime session 可并发 Run），提速 ~2x；
     * - QWEN3（LLM 解码内存高）：单路顺序执行，段长已被 VAD 限制在 30s 内（等价于分段防 OOM）。
     */
    private suspend fun recognizeVadChunks(
        rec: OfflineRecognizer,
        samples: FloatArray,
        chunks: List<VadSegmenter.VadChunk>,
        onProgress: ((Float) -> Unit)?
    ): SpeechRecognition = coroutineScope {
        val isQwen3 = activeSource == TranscriptSource.QWEN3
        // 并行路数：普通引擎固定 2 路；QWEN3 仅在高 RAM 设备（≥8GB）放开 2 路，低内存保持单路防 OOM
        val maxWorkers = when {
            !isQwen3 -> VAD_PARALLEL_WORKERS
            isHighRamDevice() -> QWEN3_PARALLEL_WORKERS_HIGH_RAM
            else -> 1
        }
        Log.i(TAG, "VAD 分段识别: ${chunks.size} 段 / 并行路数=$maxWorkers / 引擎=$activeName / 高内存设备=$highRamDevice")

        val texts = arrayOfNulls<String>(chunks.size)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = kotlinx.coroutines.sync.Semaphore(maxWorkers)
        val jobs = chunks.mapIndexed { idx, chunk ->
            async(Dispatchers.Default) {
                gate.withPermit {
                    val stream = rec.createStream()
                    try {
                        stream.acceptWaveform(samples.copyOfRange(chunk.start, chunk.end), SAMPLE_RATE)
                        rec.decode(stream)
                        texts[idx] = rec.getResult(stream).text.trim()
                    } finally {
                        stream.release()
                    }
                    val n = done.incrementAndGet()
                    onProgress?.invoke(n.toFloat() / chunks.size)
                }
            }
        }
        jobs.awaitAll()

        val segments = mutableListOf<TranscriptSegment>()
        val sb = StringBuilder()
        chunks.forEachIndexed { idx, chunk ->
            val t = texts[idx].orEmpty()
            if (t.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(t)
                segments += TranscriptSegment(
                    startMs = chunk.start * 1000L / SAMPLE_RATE,
                    endMs = chunk.end * 1000L / SAMPLE_RATE,
                    text = t
                )
            }
        }
        SpeechRecognition(sb.toString().ifBlank { "（未能识别到语音内容）" }, segments)
    }

    /** Qwen3-ASR 专用（无 VAD 时的回落）：30s 硬窗分段识别 + 文本顺序拼接。 */
    private fun recognizePcmChunked(rec: OfflineRecognizer, samples: FloatArray, onProgress: ((Float) -> Unit)?): String {
        val totalChunks = (samples.size + QWEN3_WINDOW_SAMPLES - 1) / QWEN3_WINDOW_SAMPLES
        Log.i(TAG, "Qwen3 长音频分段识别: ${samples.size / SAMPLE_RATE}s → $totalChunks 段 × ${QWEN3_WINDOW_SAMPLES / SAMPLE_RATE}s")
        val sb = StringBuilder()
        for (i in 0 until totalChunks) {
            val start = i * QWEN3_WINDOW_SAMPLES
            val end = minOf(start + QWEN3_WINDOW_SAMPLES, samples.size)
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples.copyOfRange(start, end), SAMPLE_RATE)
                rec.decode(stream)
                sb.append(rec.getResult(stream).text)
            } finally {
                stream.release()
            }
            onProgress?.invoke((i + 1).toFloat() / totalChunks)
        }
        return sb.toString().ifBlank { "（未能识别到语音内容）" }
    }

    private fun recognizePcmCloud(samples: FloatArray, settings: AppSettings, onProgress: ((Float) -> Unit)?): String {
        onProgress?.invoke(0.1f)
        val wavBytes = encodeWav(samples)
        onProgress?.invoke(0.3f)

        val requestBody = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("model", settings.cloudModelName)
            .addFormDataPart(
                "file",
                "audio.wav",
                wavBytes.toRequestBody("audio/wav".toMediaTypeOrNull())
            )
            .build()

        val requestBuilder = okhttp3.Request.Builder()
            .url(settings.cloudApiUrl)
            .post(requestBody)
        if (settings.cloudApiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${settings.cloudApiKey}")
        }

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val response = client.newCall(requestBuilder.build()).execute()
        onProgress?.invoke(0.8f)

        if (!response.isSuccessful) {
            throw Exception("云端 ASR 请求失败: HTTP ${response.code} ${response.message}")
        }

        val responseBody = response.body?.string() ?: throw Exception("云端 ASR 响应为空")
        val textMatch = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(responseBody)
        val text = if (textMatch != null) {
            textMatch.groupValues[1]
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        } else {
            responseBody
        }
        onProgress?.invoke(1.0f)
        return text.ifBlank { "（未能识别到语音内容）" }
    }

    private fun encodeWav(samples: FloatArray): ByteArray {
        val sampleRate = 16000
        val bitsPerSample = 16
        val channels = 1
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val subChunk2Size = samples.size * 2
        val chunkSize = 36 + subChunk2Size

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(chunkSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16) // SubChunk1Size
        header.putShort(1.toShort()) // AudioFormat (1 = PCM)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(subChunk2Size)

        val wavBytes = ByteArray(44 + subChunk2Size)
        System.arraycopy(header.array(), 0, wavBytes, 0, 44)

        var offset = 44
        for (sample in samples) {
            val s = (sample.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
            wavBytes[offset] = (s.toInt() and 0xFF).toByte()
            wavBytes[offset + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
            offset += 2
        }
        return wavBytes
    }

    @Synchronized
    fun release() {
        recognizer?.release()
        recognizer = null
        configSignature = null
    }

    // ============ 私有：按模型来源构建 ============

    private fun build(spec: AsrModelSpec, language: String, threads: Int, provider: String): OfflineRecognizer {
        return if (spec.bundled) {
            // 内置模型：从 APK assets 加载。目前仅 SenseVoice 内置。
            when (spec.engine) {
                AsrEngine.SENSEVOICE -> buildBundledSenseVoice(language, threads, provider)
                AsrEngine.WHISPER -> buildBundledWhisper(spec.id, language, threads, provider)
                else -> throw IllegalStateException("${spec.engine} 引擎无内置版本")
            }
        } else {
            // 下载模型：从手机存储文件系统绝对路径加载（支持递归子文件夹）。
            val dir = modelManager.modelDir(spec)
            val allFiles = dir.walkTopDown().filter { it.isFile && !it.name.contains(".part") && !it.name.endsWith(".tmp") }.toList()
            require(allFiles.isNotEmpty()) { "模型目录为空: ${dir.absolutePath}" }
            when (spec.engine) {
                AsrEngine.WHISPER -> buildWhisperFromFiles(allFiles, language, threads, provider)
                AsrEngine.SENSEVOICE -> buildSenseVoiceFromFiles(allFiles, language, threads, provider)
                AsrEngine.PARAFORMER -> buildParaformerFromFiles(allFiles, threads, provider)
                AsrEngine.QWEN3 -> buildQwen3FromFiles(dir, allFiles, threads, provider)
            }
        }
    }

    private fun buildBundledSenseVoice(language: String, threads: Int, provider: String): OfflineRecognizer {
        val names = context.assets.list("models")?.toSet() ?: emptySet()
        require(names.contains("model.onnx") && names.contains("tokens.txt")) {
            "内置 SenseVoice 模型缺失（assets/models/model.onnx）"
        }
        val modelConfig = OfflineModelConfig().apply {
            senseVoice = OfflineSenseVoiceModelConfig().apply {
                model = "models/model.onnx"
                this.language = language
                useInverseTextNormalization = true
            }
            tokens = "models/tokens.txt"
            numThreads = threads
            debug = false
            this.provider = provider
        }
        return OfflineRecognizer(context.assets, recognizerConfig(modelConfig))
    }

    /**
     * 内置 Whisper（从 APK assets 加载）。assets 目录约定：
     *   models/<id>/<前缀>-encoder.int8.onnx
     *   models/<id>/<前缀>-decoder.int8.onnx
     *   models/<id>/<前缀>-tokens.txt
     * 文件名不固定，按 encoder/decoder/tokens.txt 关键字识别。
     */
    private fun buildBundledWhisper(specId: String, language: String, threads: Int, provider: String): OfflineRecognizer {
        val dir = "models/$specId"
        val names = context.assets.list(dir)?.toSet()
            ?: throw IllegalStateException("内置 Whisper 资源目录缺失: $dir")
        val encoder = names.first { it.contains("encoder") && it.endsWith(".onnx") }
        val decoder = names.first { it.contains("decoder") && it.endsWith(".onnx") }
        val tokens = names.first { it.endsWith("tokens.txt") }
        val modelConfig = OfflineModelConfig().apply {
            whisper = OfflineWhisperModelConfig().apply {
                this.encoder = "$dir/$encoder"
                this.decoder = "$dir/$decoder"
                this.language = if (language == "auto") "" else language
                task = "transcribe"
            }
            this.tokens = "$dir/$tokens"
            numThreads = threads
            debug = false
            this.provider = provider
        }
        // 用 AssetManager 构造 → 从 assets 相对路径加载
        return OfflineRecognizer(context.assets, recognizerConfig(modelConfig))
    }

    private fun buildWhisperFromFiles(files: List<File>, language: String, threads: Int, provider: String): OfflineRecognizer {
        val encoder = files.first { it.name.contains("encoder") && it.name.endsWith(".onnx") }.absolutePath
        val decoder = files.first { it.name.contains("decoder") && it.name.endsWith(".onnx") }.absolutePath
        val tokens = files.first { it.name.endsWith("tokens.txt") }.absolutePath
        val modelConfig = OfflineModelConfig().apply {
            whisper = OfflineWhisperModelConfig().apply {
                this.encoder = encoder
                this.decoder = decoder
                this.language = if (language == "auto") "" else language
                task = "transcribe"
            }
            this.tokens = tokens
            numThreads = threads
            debug = false
            this.provider = provider
        }
        // AssetManager 为 null → 从文件系统绝对路径加载
        return OfflineRecognizer(config = recognizerConfig(modelConfig))
    }

    private fun buildSenseVoiceFromFiles(files: List<File>, language: String, threads: Int, provider: String): OfflineRecognizer {
        val model = files.first { it.name.endsWith(".onnx") }.absolutePath
        val tokens = files.first { it.name.endsWith("tokens.txt") }.absolutePath
        val modelConfig = OfflineModelConfig().apply {
            senseVoice = OfflineSenseVoiceModelConfig().apply {
                this.model = model
                this.language = language
                useInverseTextNormalization = true
            }
            this.tokens = tokens
            numThreads = threads
            debug = false
            this.provider = provider
        }
        return OfflineRecognizer(config = recognizerConfig(modelConfig))
    }

    /** Paraformer（中英粤三语）：model.int8.onnx（优先 int8）+ tokens.txt，自带标点。 */
    private fun buildParaformerFromFiles(files: List<File>, threads: Int, provider: String): OfflineRecognizer {
        val model = (files.firstOrNull { it.name.endsWith(".int8.onnx") }
            ?: files.first { it.name.endsWith(".onnx") }).absolutePath
        val tokens = files.first { it.name.endsWith("tokens.txt") }.absolutePath
        val modelConfig = OfflineModelConfig().apply {
            paraformer = OfflineParaformerModelConfig().apply {
                this.model = model
            }
            this.tokens = tokens
            numThreads = threads
            debug = false
            this.provider = provider
        }
        return OfflineRecognizer(config = recognizerConfig(modelConfig))
    }

    /** Qwen3-ASR（LLM 听写）：conv_frontend + encoder + decoder + tokenizer 目录。 */
    private fun buildQwen3FromFiles(modelDir: File, files: List<File>, threads: Int, provider: String): OfflineRecognizer {
        val conv = files.first { it.name == "conv_frontend.onnx" }.absolutePath
        val encoder = files.first {
            it.name.contains("encoder") && !it.name.contains("conv") && it.name.endsWith(".onnx")
        }.absolutePath
        val decoder = files.first { it.name.contains("decoder") && it.name.endsWith(".onnx") }.absolutePath
        val tokenizerDir = File(modelDir, "tokenizer")
        require(tokenizerDir.isDirectory) { "Qwen3 tokenizer 目录缺失: ${tokenizerDir.absolutePath}" }
        val modelConfig = OfflineModelConfig().apply {
            qwen3Asr = OfflineQwen3AsrModelConfig().apply {
                convFrontend = conv
                this.encoder = encoder
                this.decoder = decoder
                tokenizer = tokenizerDir.absolutePath
            }
            numThreads = threads
            debug = false
            this.provider = provider
        }
        return OfflineRecognizer(config = recognizerConfig(modelConfig))
    }

    private fun recognizerConfig(modelConfig: OfflineModelConfig): OfflineRecognizerConfig {
        val featConfig = FeatureConfig().apply {
            sampleRate = SAMPLE_RATE
            featureDim = 80
        }
        return OfflineRecognizerConfig().apply {
            this.featConfig = featConfig
            this.modelConfig = modelConfig
            decodingMethod = "greedy_search"
            maxActivePaths = 4
        }
    }
}
