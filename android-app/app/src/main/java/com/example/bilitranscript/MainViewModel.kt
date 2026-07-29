package com.example.bilitranscript

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel：链接输入 + 提取 + 结果展示，全部走共享 [TranscriptionPipeline]。
 *
 * ## UI 状态分区（Compose 重组性能关键）
 * 不按「单一 UiState」暴露，而是按**更新频率**拆成三条独立 StateFlow：
 *  - [videoUrl]：打字高频 → 只有输入卡重组；
 *  - [progressUi]：识别进度/引擎状态高频 → 只有进度区与按钮重组；
 *  - [resultUi]：结果大块低频 → 千字文本不参与上述任何重组。
 * 这样打字、识别进度刷新、结果展示互不拖慢，按钮交互保持流畅。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val pipeline = AppGraph.pipeline(app)
    private val historyRepo = AppGraph.history(app)
    private val settingsRepo = AppGraph.settings(app)
    private val modelManager = AppGraph.models(app)

    val settings: StateFlow<AppSettings> = settingsRepo.settings
    val history: StateFlow<List<HistoryRecord>> = historyRepo.records
    val modelStatuses: StateFlow<List<ModelStatus>> = modelManager.statuses

    /** 输入框链接（打字高频，独立 flow）。 */
    private val _videoUrl = MutableStateFlow("")
    val videoUrl: StateFlow<String> = _videoUrl

    /** 进度/引擎区（识别中高频更新）。 */
    private val _progressUi = MutableStateFlow(ProgressUi())
    val progressUi: StateFlow<ProgressUi> = _progressUi

    /** 结果区（低频大块文本；null = 无结果）。 */
    private val _resultUi = MutableStateFlow<ResultUi?>(null)
    val resultUi: StateFlow<ResultUi?> = _resultUi

    /** 最近一次结果（含时间轴，供导出 SRT） */
    var lastOutcome: TranscriptOutcome? = null
        private set

    init {
        // 全部重活（DB 加载、缓存清扫、模型目录扫描、引擎加载）放 IO，启动不卡主线程
        viewModelScope.launch(Dispatchers.IO) {
            historyRepo.load()
            TranscriptionPipeline.sweepCache(app)
            modelManager.refresh()
            val ready = AppGraph.recognizer(app).ensureReady(settingsRepo.settings.value)
            val engineName = AppGraph.recognizer(app).getEngineName()
            if (engineName.contains("已安全降级")) {
                settingsRepo.update { it.copy(selectedModelId = ModelManager.BUNDLED_SENSEVOICE.id) }
            }
            _progressUi.value = _progressUi.value.copy(
                engineReady = ready,
                engineName = engineName,
                statusText = if (ready) "引擎已就绪，可以开始提取" else "引擎初始化失败"
            )
        }
    }

    fun onUrlChange(url: String) {
        _videoUrl.value = url
        if (_progressUi.value.error != null) {
            _progressUi.value = _progressUi.value.copy(error = null)
        }
    }

    /** 预填链接（来自分享/剪贴板），可选自动开提。 */
    fun prefill(url: String, autoStart: Boolean) {
        _videoUrl.value = url
        if (_progressUi.value.error != null) {
            _progressUi.value = _progressUi.value.copy(error = null)
        }
        if (autoStart && url.isNotBlank()) extractTranscript()
    }

    fun extractTranscript() {
        val url = _videoUrl.value.trim()
        if (url.isBlank() || _progressUi.value.isLoading) return

        viewModelScope.launch {
            _progressUi.value = _progressUi.value.copy(
                isLoading = true,
                statusText = "正在解析链接...",
                progress = 0.02f,
                phase = "解析中",
                error = null
            )
            _resultUi.value = null

            try {
                val outcome = pipeline.extract(url) { fraction, phase ->
                    _progressUi.value = _progressUi.value.copy(
                        progress = fraction.coerceIn(0f, 1f),
                        phase = phase
                    )
                }

                lastOutcome = outcome
                _resultUi.value = ResultUi(
                    title = outcome.title,
                    transcript = outcome.text,
                    wordCount = outcome.wordCount,
                    sourceLabel = outcome.source.label,
                    hasTimeline = outcome.hasTimeline
                )
                _progressUi.value = _progressUi.value.copy(
                    isLoading = false,
                    statusText = "提取完成！来源：${outcome.source.label}"
                )

                // 自动入库历史
                if (settingsRepo.settings.value.saveHistory) {
                    historyRepo.add(
                        HistoryRecord(
                            id = historyRepo.newId(),
                            bvid = outcome.bvid,
                            title = outcome.title,
                            text = outcome.text,
                            wordCount = outcome.wordCount,
                            durationSec = outcome.durationSec,
                            source = outcome.source.label,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }

                // 自动复制
                if (settingsRepo.settings.value.autoCopy) copyToClipboard()

            } catch (e: Exception) {
                _progressUi.value = _progressUi.value.copy(
                    isLoading = false,
                    statusText = "",
                    progress = 0f,
                    phase = "",
                    error = "提取失败: ${e.message}"
                )
            }
        }
    }

    fun copyToClipboard() {
        val text = _resultUi.value?.transcript ?: return
        clipboard().setPrimaryClip(ClipData.newPlainText("文案", text))
        _progressUi.value = _progressUi.value.copy(statusText = "已复制到剪贴板")
    }

    fun shareTranscript() {
        val r = _resultUi.value ?: return
        val shareText = "【${r.title}】\n\n${r.transcript}"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(Intent.createChooser(intent, "分享文案").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun clearResult() {
        lastOutcome = null
        _resultUi.value = null
        _progressUi.value = _progressUi.value.copy(
            statusText = "", progress = 0f, phase = "", error = null
        )
    }

    /** 从历史打开一条，直接展示其文案。 */
    fun openHistory(record: HistoryRecord) {
        lastOutcome = null
        _resultUi.value = ResultUi(
            title = record.title,
            transcript = record.text,
            wordCount = record.wordCount,
            sourceLabel = record.source,
            hasTimeline = false
        )
        _progressUi.value = _progressUi.value.copy(statusText = "来自历史记录", error = null)
    }

    // ---- 设置 ----
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            val current = settingsRepo.settings.value
            val next = transform(current)

            // 设置**先落库**：开关/语言的视觉反馈立即生效，UI 零等待。
            settingsRepo.update { next }

            // 只有「引擎相关」字段变化才需要重载引擎（239MB 模型，IO 秒级）。
            // 重载放后台异步追赶——引擎状态条显示「加载中」直到就绪，
            // 不再出现「点一下语言/NNAPI 要等模型重载完才看到反馈」的卡死感。
            val engineChanged = current.selectedModelId != next.selectedModelId ||
                    current.language != next.language ||
                    current.numThreads != next.numThreads ||
                    current.useNnapi != next.useNnapi ||
                    current.useCloudModel != next.useCloudModel ||
                    current.cloudApiUrl != next.cloudApiUrl ||
                    current.cloudModelName != next.cloudModelName
            if (!engineChanged) return@launch

            _progressUi.value = _progressUi.value.copy(engineReady = false)
            val ready = kotlinx.coroutines.withContext(Dispatchers.IO) {
                AppGraph.recognizer(app).ensureReady(next)
            }
            val engineName = AppGraph.recognizer(app).getEngineName()
            if (engineName.contains("已安全降级")) {
                settingsRepo.update { next.copy(selectedModelId = ModelManager.BUNDLED_SENSEVOICE.id) }
            }
            _progressUi.value = _progressUi.value.copy(
                engineReady = ready,
                engineName = engineName
            )
        }
    }

    // ---- 模型仓库 ----
    fun selectModel(id: String) = updateSettings { it.copy(selectedModelId = id) }

    fun downloadModel(spec: AsrModelSpec) = viewModelScope.launch {
        val ok = modelManager.download(spec)
        _progressUi.value = _progressUi.value.copy(
            statusText = if (ok) "${spec.name} 下载完成并已自动选用" else "${spec.name} 下载失败，请重试"
        )
        if (ok) {
            selectModel(spec.id)
        }
    }

    fun importModel(spec: AsrModelSpec, uri: android.net.Uri) = viewModelScope.launch {
        val ok = modelManager.importFromArchive(spec, uri)
        _progressUi.value = _progressUi.value.copy(
            statusText = if (ok) "${spec.name} 导入成功" else "${spec.name} 导入失败"
        )
        if (ok) selectModel(spec.id)
    }

    fun deleteModel(spec: AsrModelSpec) = viewModelScope.launch {
        modelManager.delete(spec)
        if (settingsRepo.settings.value.selectedModelId == spec.id) {
            updateSettings { it.copy(selectedModelId = "sensevoice") }
        }
    }

    fun refreshModels() = modelManager.refresh()

    // ---- 历史 ----
    fun deleteHistory(id: String) = viewModelScope.launch { historyRepo.delete(id) }
    fun toggleFavorite(id: String) = viewModelScope.launch { historyRepo.toggleFavorite(id) }
    fun clearHistory() = viewModelScope.launch { historyRepo.clearAll() }

    private fun clipboard(): ClipboardManager =
        app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
}

/** 进度/引擎区 UI 状态（识别中高频更新）。 */
data class ProgressUi(
    val isLoading: Boolean = false,
    val progress: Float = 0f,
    val phase: String = "",
    val statusText: String = "正在初始化...",
    val engineReady: Boolean = false,
    val engineName: String = "SenseVoice",
    val error: String? = null
)

/** 结果区 UI 状态（低频大块文本）。 */
data class ResultUi(
    val title: String,
    val transcript: String,
    val wordCount: Int,
    val sourceLabel: String?,
    val hasTimeline: Boolean
)
