package com.example.bilitranscript

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 识别引擎选择。
 * - SENSEVOICE：当前内置模型，快、对干净人声很好（assets/models/model.onnx）
 * - WHISPER：大模型高精度模式，需把 Whisper 模型放进 assets/models/whisper/（见 UPGRADE.md）
 */
enum class AsrEngine { SENSEVOICE, WHISPER }

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
 * 应用设置（不可变值对象）。
 * 全部用 SharedPreferences 持久化，零额外依赖。
 */
data class AppSettings(
    /** 当前选用的模型 id（见 ModelManager.CATALOG）。默认内置 SenseVoice。 */
    val selectedModelId: String = "sensevoice",
    /** 是否在识别前做人声分离（剥离背景音乐）。需分离模型，详见 UPGRADE.md */
    val vocalSeparation: Boolean = false,
    val language: String = "auto",
    val numThreads: Int = 4,
    /** 尝试用 NNAPI 硬件加速（部分机型反而更慢，默认关） */
    val useNnapi: Boolean = false,
    /** 识别完成自动复制到剪贴板 */
    val autoCopy: Boolean = false,
    /**
     * 优先用视频自带字幕（不做语音识别）。
     * 默认关闭：始终走「下载音频 → 本地AI听写」的纯语音识别。
     * 开启后：视频有现成官方/AI字幕时直接拿来用，秒出且准，但内容取决于UP主的字幕。
     */
    val subtitleFirst: Boolean = false,
    /** 下载最低码率音频流（识别只需 16kHz，省流量更快、精度无损） */
    val lowBitrateAudio: Boolean = true,
    /** 自动入库历史 */
    val saveHistory: Boolean = true,
    /** 可选 B站 SESSDATA Cookie：用于获取需要登录的字幕/更高音质 */
    val sessdata: String = ""
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
        language = prefs.getString(KEY_LANGUAGE, "auto") ?: "auto",
        numThreads = prefs.getInt(KEY_THREADS, 4),
        useNnapi = prefs.getBoolean(KEY_NNAPI, false),
        autoCopy = prefs.getBoolean(KEY_AUTO_COPY, false),
        subtitleFirst = prefs.getBoolean(KEY_SUBTITLE_FIRST, true),
        lowBitrateAudio = prefs.getBoolean(KEY_LOW_BITRATE, true),
        saveHistory = prefs.getBoolean(KEY_SAVE_HISTORY, true),
        sessdata = prefs.getString(KEY_SESSDATA, "") ?: ""
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString(KEY_SELECTED_MODEL, next.selectedModelId)
            .putBoolean(KEY_SEPARATION, next.vocalSeparation)
            .putString(KEY_LANGUAGE, next.language)
            .putInt(KEY_THREADS, next.numThreads)
            .putBoolean(KEY_NNAPI, next.useNnapi)
            .putBoolean(KEY_AUTO_COPY, next.autoCopy)
            .putBoolean(KEY_SUBTITLE_FIRST, next.subtitleFirst)
            .putBoolean(KEY_LOW_BITRATE, next.lowBitrateAudio)
            .putBoolean(KEY_SAVE_HISTORY, next.saveHistory)
            .putString(KEY_SESSDATA, next.sessdata)
            .apply()
        _settings.value = next
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_SELECTED_MODEL = "selected_model_id"
        private const val KEY_SEPARATION = "vocal_separation"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_THREADS = "num_threads"
        private const val KEY_NNAPI = "use_nnapi"
        private const val KEY_AUTO_COPY = "auto_copy"
        private const val KEY_SUBTITLE_FIRST = "subtitle_first"
        private const val KEY_LOW_BITRATE = "low_bitrate"
        private const val KEY_SAVE_HISTORY = "save_history"
        private const val KEY_SESSDATA = "sessdata"
    }
}
