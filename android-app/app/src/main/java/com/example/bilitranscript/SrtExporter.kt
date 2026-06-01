package com.example.bilitranscript

/**
 * 把带时间轴的文案导出为 SRT 字幕。
 * 仅当 outcome.hasTimeline 为真（字幕来源，或将来 VAD 分段）时可用。
 */
object SrtExporter {

    fun toSrt(segments: List<TranscriptSegment>): String {
        val sb = StringBuilder()
        segments.forEachIndexed { i, seg ->
            sb.append(i + 1).append('\n')
            sb.append(timestamp(seg.startMs)).append(" --> ").append(timestamp(seg.endMs)).append('\n')
            sb.append(seg.text.trim()).append('\n').append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** 毫秒 → SRT 时间戳 HH:MM:SS,mmm */
    private fun timestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val h = safe / 3_600_000
        val m = (safe % 3_600_000) / 60_000
        val s = (safe % 60_000) / 1000
        val millis = safe % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, millis)
    }
}
