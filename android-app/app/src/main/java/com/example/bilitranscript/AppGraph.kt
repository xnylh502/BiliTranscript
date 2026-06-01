package com.example.bilitranscript

import android.content.Context

/**
 * 极简依赖容器（手写 Service Locator）。
 * 让 Compose 界面、ViewModel、悬浮球 Service 共用同一批单例，
 * 避免引入 Hilt/Koin。所有获取方法都用 applicationContext，安全防泄漏。
 */
object AppGraph {

    @Volatile private var settingsRepo: SettingsRepository? = null
    @Volatile private var historyRepo: HistoryRepository? = null
    @Volatile private var recognizer: SpeechRecognizer? = null
    @Volatile private var separator: VocalSeparator? = null
    @Volatile private var modelManager: ModelManager? = null

    fun settings(context: Context): SettingsRepository =
        settingsRepo ?: synchronized(this) {
            settingsRepo ?: SettingsRepository(context.applicationContext).also { settingsRepo = it }
        }

    fun history(context: Context): HistoryRepository =
        historyRepo ?: synchronized(this) {
            historyRepo ?: HistoryRepository(context.applicationContext).also { historyRepo = it }
        }

    fun models(context: Context): ModelManager =
        modelManager ?: synchronized(this) {
            modelManager ?: ModelManager(context.applicationContext).also { modelManager = it }
        }

    /** 识别器是重对象（要加载模型），全进程单例，按设置惰性重配置。 */
    fun recognizer(context: Context): SpeechRecognizer =
        recognizer ?: synchronized(this) {
            recognizer ?: SpeechRecognizer(context.applicationContext, models(context)).also { recognizer = it }
        }

    fun separator(context: Context): VocalSeparator =
        separator ?: synchronized(this) {
            separator ?: VocalSeparator(context.applicationContext).also { separator = it }
        }

    /** 每次提取新建一个管线（轻对象），复用上面的单例。 */
    fun pipeline(context: Context): TranscriptionPipeline {
        val app = context.applicationContext
        return TranscriptionPipeline(
            context = app,
            downloader = BiliDownloader(),
            recognizer = recognizer(app),
            separator = separator(app),
            settingsRepo = settings(app)
        )
    }
}
