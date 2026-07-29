package com.example.bilitranscript

import android.content.Context
import com.example.bilitranscript.data.db.AppDatabase
import com.example.bilitranscript.data.db.LogSink

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
    @Volatile private var database: AppDatabase? = null

    /**
     * Application.onCreate 调用一次，启动 DB 单例 + 绑定 LogSink。
     * 任何依赖 LogSink 的位置都得在 init 之后才安全（实际上 LogSink.bind
     * 是惰性的，没 bind 之前调用只会立即崩出明确错误，方便排查）。
     */
    fun init(context: Context) {
        val app = context.applicationContext
        database = AppDatabase.get(app)
        LogSink.bind(database!!)
    }

    fun database(context: Context): AppDatabase =
        database ?: synchronized(this) {
            database ?: AppDatabase.get(context.applicationContext).also { database = it }
        }

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
