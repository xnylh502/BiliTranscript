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
import kotlinx.coroutines.withContext

/**
 * 首页 ViewModel：链接输入 + 提取 + 结果展示，全部走共享 [TranscriptionPipeline]。
 * 同时把设置/历史 StateFlow 暴露给 Compose 界面。
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

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    /** 最近一次结果（含时间轴，供导出 SRT） */
    var lastOutcome: TranscriptOutcome? = null
        private set

    init {
        viewModelScope.launch {
            historyRepo.load()
            TranscriptionPipeline.sweepCache(app)
            val ready = withContext(Dispatchers.IO) {
                AppGraph.recognizer(app).ensureReady(settingsRepo.settings.value)
            }
            _uiState.value = _uiState.value.copy(
                engineReady = ready,
                engineName = AppGraph.recognizer(app).getEngineName(),
                statusText = if (ready) "引擎已就绪，可以开始提取" else "引擎初始化失败"
            )
        }
    }

    fun onUrlChange(url: String) {
        _uiState.value = _uiState.value.copy(videoUrl = url, error = null)
    }

    /** 预填链接（来自分享/剪贴板），可选自动开提。 */
    fun prefill(url: String, autoStart: Boolean) {
        _uiState.value = _uiState.value.copy(videoUrl = url, error = null)
        if (autoStart && url.isNotBlank()) extractTranscript()
    }

    fun extractTranscript() {
        val url = _uiState.value.videoUrl.trim()
        if (url.isBlank() || _uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                statusText = "正在解析链接...",
                progress = 0.02f,
                progressPhase = "解析中",
                error = null,
                transcript = null
            )

            try {
                val outcome = pipeline.extract(url) { fraction, phase ->
                    _uiState.value = _uiState.value.copy(
                        progress = fraction.coerceIn(0f, 1f),
                        progressPhase = phase
                    )
                }

                lastOutcome = outcome
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    statusText = "提取完成！来源：${outcome.source.label}",
                    videoTitle = outcome.title,
                    transcript = outcome.text,
                    wordCount = outcome.wordCount,
                    sourceLabel = outcome.source.label,
                    hasTimeline = outcome.hasTimeline,
                    error = null
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
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    statusText = "",
                    progress = 0f,
                    progressPhase = "",
                    error = "提取失败: ${e.message}"
                )
            }
        }
    }

    fun copyToClipboard() {
        val text = _uiState.value.transcript ?: return
        clipboard().setPrimaryClip(ClipData.newPlainText("文案", text))
        _uiState.value = _uiState.value.copy(statusText = "已复制到剪贴板")
    }

    fun shareTranscript() {
        val title = _uiState.value.videoTitle ?: ""
        val text = _uiState.value.transcript ?: return
        val shareText = "【$title】\n\n$text"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(Intent.createChooser(intent, "分享文案").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun clearResult() {
        lastOutcome = null
        _uiState.value = _uiState.value.copy(
            transcript = null, wordCount = 0, videoTitle = null,
            statusText = "", progress = 0f, progressPhase = "",
            sourceLabel = null, hasTimeline = false, error = null
        )
    }

    /** 从历史打开一条，直接展示其文案。 */
    fun openHistory(record: HistoryRecord) {
        lastOutcome = null
        _uiState.value = _uiState.value.copy(
            videoTitle = record.title,
            transcript = record.text,
            wordCount = record.wordCount,
            sourceLabel = record.source,
            hasTimeline = false,
            statusText = "来自历史记录",
            error = null
        )
    }

    // ---- 设置 ----
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settingsRepo.update(transform)
        // 引擎相关设置变了 → 后台重配引擎
        viewModelScope.launch {
            val ready = withContext(Dispatchers.IO) {
                AppGraph.recognizer(app).ensureReady(settingsRepo.settings.value)
            }
            _uiState.value = _uiState.value.copy(
                engineReady = ready,
                engineName = AppGraph.recognizer(app).getEngineName()
            )
        }
    }

    // ---- 模型仓库 ----
    fun selectModel(id: String) = updateSettings { it.copy(selectedModelId = id) }

    fun downloadModel(spec: AsrModelSpec) = viewModelScope.launch { modelManager.download(spec) }

    fun importModel(spec: AsrModelSpec, uri: android.net.Uri) = viewModelScope.launch {
        val ok = modelManager.importFromZip(spec, uri)
        _uiState.value = _uiState.value.copy(
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

data class UiState(
    val videoUrl: String = "",
    val isLoading: Boolean = false,
    val statusText: String = "正在初始化...",
    val engineReady: Boolean = false,
    val engineName: String = "SenseVoice",
    val videoTitle: String? = null,
    val transcript: String? = null,
    val wordCount: Int = 0,
    val sourceLabel: String? = null,
    val hasTimeline: Boolean = false,
    val error: String? = null,
    val progress: Float = 0f,
    val progressPhase: String = ""
)
