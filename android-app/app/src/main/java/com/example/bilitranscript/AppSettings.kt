package com.example.bilitranscript

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 识别引擎选择。
 * - SENSEVOICE：内置模型 + Fun-ASR-Nano（同一推理架构，model.onnx + tokens.txt）
 * - WHISPER：旧版 Whisper 大模型（已下架，保留加载代码兼容老用户已装模型）
 * - PARAFORMER：阿里 Paraformer 工业级中文 ASR（model.int8.onnx + tokens.txt，自带标点）
 * - QWEN3：Qwen3-ASR LLM 大模型（conv_frontend + encoder + decoder + tokenizer 目录）
 */
enum class AsrEngine { SENSEVOICE, WHISPER, PARAFORMER, QWEN3 }

/** 识别语言（SenseVoice / Whisper 通用） */
enum class RecognizeLanguage(val code: String, val label: String) {
    AUTO("auto", "自动检测"),
    ZH("zh", "中文"),
    EN("en", "英文"),
    JA("ja", "日文"),
    KO("ko", "韩文"),
    YUE("yue", "粤语");

    companion object {
        fun fromCode(code: String): RecognizeLanguage =
            entries.firstOrNull { it.code == code } ?: AUTO
    }
}

/**
 * 默认识别线程数：按设备 CPU 核数自适应。
 * 取 min(核数 - 1, 8)，下限 2、上限 8——留 1 核给系统/界面，多核机用满但不卡 UI。
 */
private fun defaultNumThreads(): Int =
    (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 8)

/**
 * 应用设置（不可变值对象）。
 * 全部用 SharedPreferences 持久化，零额外依赖。
 */
data class AppSettings(
    /** 当前选用的模型 id（见 ModelManager.CATALOG）。默认内置 SenseVoice。 */
    val selectedModelId: String = "sensevoice",
    /** 是否在识别前做人声分离（GT-CRN 语音增强，剥离背景噪声/BGM）。已内置，开箱即用 */
    val vocalSeparation: Boolean = false,
    /**
     * VAD 智能切句（Silero，内置 643KB 模型）：识别前按语音活动切段，
     * 跳过纯音乐/静音段（避免大模型在 BGM 段产生幻觉歌词），
     * 并产出逐句时间轴（全引擎可导出 SRT）。默认开启。
     */
    val vadSegment: Boolean = true,
    /** 识别语言。默认 zh：B站文案场景绝大多数为中文，明确指定比 auto 更稳。 */
    val language: String = "zh",
    /**
     * 识别线程数。默认见 [defaultNumThreads]——按设备 CPU 核数自适应
     * （取 min(核数-1, 8) 且 ≥2，留 1 核给系统/界面），比写死 4 更快。
     */
    val numThreads: Int = defaultNumThreads(),
    /** 尝试用 NNAPI 硬件加速（部分机型反而更慢，默认关） */
    val useNnapi: Boolean = false,
    /** 识别完成自动复制到剪贴板 */
    val autoCopy: Boolean = false,
    /**
     * 优先用视频自带字幕（不做语音识别）。
     * 开启后：视频有现成官方/AI字幕时直接拿来用，秒出且准，但内容取决于UP主的字幕。
     * 关闭：始终走「下载音频 → 本地AI听写」的纯语音识别。
     */
    val subtitleFirst: Boolean = true,
    /** 下载最低码率音频流（识别只需 16kHz，省流量更快、精度无损） */
    val lowBitrateAudio: Boolean = true,
    /** 自动入库历史 */
    val saveHistory: Boolean = true,
    /** 可选 B站 SESSDATA Cookie：用于获取需要登录的字幕/更高音质 */
    val sessdata: String = "",
    
    // 云端 API ASR 配置
    val useCloudModel: Boolean = false,
    val cloudApiUrl: String = "https://api.openai.com/v1/audio/transcriptions",
    val cloudApiKey: String = "",
    val cloudModelName: String = "whisper-1"
)

/**
 * 设置仓库：单一可信源，暴露 StateFlow，更新即持久化。
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings

    private fun load(): AppSettings = AppSettings(
        selectedModelId = prefs.getString(KEY_SELECTED_MODEL, "sensevoice") ?: "sensevoice",
        vocalSeparation = prefs.getBoolean(KEY_SEPARATION, false),
        vadSegment = prefs.getBoolean(KEY_VAD_SEGMENT, true),
        language = prefs.getString(KEY_LANGUAGE, "zh") ?: "zh",
        numThreads = prefs.getInt(KEY_THREADS, defaultNumThreads()),
        useNnapi = prefs.getBoolean(KEY_NNAPI, false),
        autoCopy = prefs.getBoolean(KEY_AUTO_COPY, false),
        subtitleFirst = prefs.getBoolean(KEY_SUBTITLE_FIRST, true),
        lowBitrateAudio = prefs.getBoolean(KEY_LOW_BITRATE, true),
        saveHistory = prefs.getBoolean(KEY_SAVE_HISTORY, true),
        sessdata = prefs.getString(KEY_SESSDATA, "") ?: "",
        
        useCloudModel = prefs.getBoolean(KEY_USE_CLOUD_MODEL, false),
        cloudApiUrl = prefs.getString(KEY_CLOUD_API_URL, "https://api.openai.com/v1/audio/transcriptions") ?: "https://api.openai.com/v1/audio/transcriptions",
        cloudApiKey = prefs.getString(KEY_CLOUD_API_KEY, "") ?: "",
        cloudModelName = prefs.getString(KEY_CLOUD_MODEL_NAME, "whisper-1") ?: "whisper-1"
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString(KEY_SELECTED_MODEL, next.selectedModelId)
            .putBoolean(KEY_SEPARATION, next.vocalSeparation)
            .putBoolean(KEY_VAD_SEGMENT, next.vadSegment)
            .putString(KEY_LANGUAGE, next.language)
            .putInt(KEY_THREADS, next.numThreads)
            .putBoolean(KEY_NNAPI, next.useNnapi)
            .putBoolean(KEY_AUTO_COPY, next.autoCopy)
            .putBoolean(KEY_SUBTITLE_FIRST, next.subtitleFirst)
            .putBoolean(KEY_LOW_BITRATE, next.lowBitrateAudio)
            .putBoolean(KEY_SAVE_HISTORY, next.saveHistory)
            .putString(KEY_SESSDATA, next.sessdata)
            
            .putBoolean(KEY_USE_CLOUD_MODEL, next.useCloudModel)
            .putString(KEY_CLOUD_API_URL, next.cloudApiUrl)
            .putString(KEY_CLOUD_API_KEY, next.cloudApiKey)
            .putString(KEY_CLOUD_MODEL_NAME, next.cloudModelName)
            .apply()
        _settings.value = next
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_SELECTED_MODEL = "selected_model_id"
        private const val KEY_SEPARATION = "vocal_separation"
        private const val KEY_VAD_SEGMENT = "vad_segment"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_THREADS = "num_threads"
        private const val KEY_NNAPI = "use_nnapi"
        private const val KEY_AUTO_COPY = "auto_copy"
        private const val KEY_SUBTITLE_FIRST = "subtitle_first"
        private const val KEY_LOW_BITRATE = "low_bitrate"
        private const val KEY_SAVE_HISTORY = "save_history"
        private const val KEY_SESSDATA = "sessdata"
        
        private const val KEY_USE_CLOUD_MODEL = "use_cloud_model"
        private const val KEY_CLOUD_API_URL = "cloud_api_url"
        private const val KEY_CLOUD_API_KEY = "cloud_api_key"
        private const val KEY_CLOUD_MODEL_NAME = "cloud_model_name"
    }
}
