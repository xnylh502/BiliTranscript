package com.example.bilitranscript

/** 文案来源 */
enum class TranscriptSource(val label: String) {
    SUBTITLE("官方字幕"),
    SENSEVOICE("SenseVoice"),
    WHISPER("Whisper"),
    PARAFORMER("Paraformer"),
    QWEN3("Qwen3-ASR")
}

/** 带时间轴的一句（用于导出 SRT）。毫秒。 */
data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * 提取结果（不可变）。
 * segments 可能为空（SenseVoice 单段识别时没有逐句时间轴；字幕和 VAD 分段时有）。
 */
data class TranscriptOutcome(
    val title: String,
    val bvid: String,
    val durationSec: Int,
    val text: String,
    val segments: List<TranscriptSegment>,
    val source: TranscriptSource,
    val usedVocalSeparation: Boolean
) {
    val wordCount: Int get() = text.length
    val hasTimeline: Boolean get() = segments.isNotEmpty()
}

/** 管线进度回调：fraction 0..1，phase 是中文阶段名 */
typealias ProgressCallback = (fraction: Float, phase: String) -> Unit

/**
 * 识别器级输出：文本 + 可选逐句时间轴。
 * VAD 切句识别时 segments 非空（每段带起止毫秒）；整段识别/云端为空。
 */
data class SpeechRecognition(
    val text: String,
    val segments: List<TranscriptSegment> = emptyList()
)
