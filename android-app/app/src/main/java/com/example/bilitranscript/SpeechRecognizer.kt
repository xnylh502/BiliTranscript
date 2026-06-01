package com.example.bilitranscript

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

/**
 * 语音识别引擎 - sherpa-onnx 离线识别。
 *
 * 模型来源两种，由 [ModelManager] 解析当前选中的模型决定：
 *  - 内置（bundled）：SenseVoice，从 APK assets 加载（OfflineRecognizer(assets, ...)）
 *  - 下载（downloaded）：Whisper 等，从手机存储文件路径加载（OfflineRecognizer(config=...)，AssetManager 为 null）
 *
 * 选中的模型若未安装，自动回落到内置 SenseVoice，绝不崩。
 * 通过 [ensureReady] 按「模型id/语言/线程/provider」签名惰性重建，避免每次提取都重载。
 */
class SpeechRecognizer(
    private val context: Context,
    private val modelManager: ModelManager
) {

    companion object {
        private const val TAG = "SpeechRecognizer"
        private const val SAMPLE_RATE = 16000
    }

    private var recognizer: OfflineRecognizer? = null
    private var activeSource: TranscriptSource = TranscriptSource.SENSEVOICE
    private var activeName: String = "SenseVoice"
    private var configSignature: String? = null
    private val audioDecoder = AudioDecoder()

    fun getEngineName(): String = activeName
    fun currentSource(): TranscriptSource = activeSource
    fun isReady(): Boolean = recognizer != null

    fun initEngine(): Boolean = ensureReady(AppSettings())

    @Synchronized
    fun ensureReady(settings: AppSettings): Boolean {
        val spec = modelManager.resolveUsable(settings.selectedModelId)
        val threads = settings.numThreads.coerceIn(1, 8)
        val provider = if (settings.useNnapi) "nnapi" else "cpu"
        val signature = "${spec.id}|${settings.language}|$threads|$provider"

        if (recognizer != null && signature == configSignature) return true

        return try {
            release()
            recognizer = build(spec, settings.language, threads, provider)
            activeSource = if (spec.engine == AsrEngine.WHISPER) TranscriptSource.WHISPER else TranscriptSource.SENSEVOICE
            activeName = spec.name
            configSignature = signature
            Log.i(TAG, "引擎就绪: $signature")
            true
        } catch (e: Exception) {
            Log.e(TAG, "引擎初始化失败: ${e.message}", e)
            recognizer = null
            configSignature = null
            false
        }
    }

    /** 识别一段已解码的 16kHz 单声道 PCM。 */
    fun recognizePcm(samples: FloatArray, onProgress: ((Float) -> Unit)? = null): String {
        val rec = recognizer ?: throw IllegalStateException("语音识别引擎未初始化")
        if (samples.isEmpty()) return "（未能识别到语音内容）"
        onProgress?.invoke(0.2f)
        val stream = rec.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        onProgress?.invoke(0.4f)
        rec.decode(stream)
        onProgress?.invoke(0.9f)
        val text = rec.getResult(stream).text
        stream.release()
        onProgress?.invoke(1f)
        return text.ifBlank { "（未能识别到语音内容）" }
    }

    /** 兼容旧调用：解码文件 + 识别。 */
    fun recognize(audioFile: File, onProgress: ((RecognizePhase, Float) -> Unit)? = null): String {
        if (recognizer == null) ensureReady(AppSettings())
        onProgress?.invoke(RecognizePhase.DECODING, 0f)
        val samples = audioDecoder.decodeToPcm(audioFile) { p ->
            onProgress?.invoke(RecognizePhase.DECODING, p)
        } ?: throw Exception("音频解码失败，格式可能不支持")
        return recognizePcm(samples) { p -> onProgress?.invoke(RecognizePhase.RECOGNIZING, p) }
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
            buildBundledSenseVoice(language, threads, provider)
        } else {
            val dir = modelManager.modelDir(spec)
            val files = dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
            require(files.isNotEmpty()) { "模型目录为空: ${dir.absolutePath}" }
            when (spec.engine) {
                AsrEngine.WHISPER -> buildWhisperFromFiles(dir, files, language, threads, provider)
                AsrEngine.SENSEVOICE -> buildSenseVoiceFromFiles(dir, files, language, threads, provider)
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

    private fun buildWhisperFromFiles(dir: File, files: Set<String>, language: String, threads: Int, provider: String): OfflineRecognizer {
        val encoder = File(dir, files.first { it.contains("encoder") && it.endsWith(".onnx") }).absolutePath
        val decoder = File(dir, files.first { it.contains("decoder") && it.endsWith(".onnx") }).absolutePath
        val tokens = File(dir, files.first { it.endsWith("tokens.txt") }).absolutePath
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

    private fun buildSenseVoiceFromFiles(dir: File, files: Set<String>, language: String, threads: Int, provider: String): OfflineRecognizer {
        val model = File(dir, files.first { it.endsWith(".onnx") }).absolutePath
        val tokens = File(dir, files.first { it.endsWith("tokens.txt") }).absolutePath
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

enum class RecognizePhase {
    DECODING, RECOGNIZING
}
