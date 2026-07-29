package com.example.bilitranscript

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 视频画面硬字幕提取器（OCR 视觉路线）。
 *
 * 管线：抽帧（每 0.5s）→ 裁剪**底部 35% 字幕区**（天然排除四角水印与画面主体表情包）
 *   → 缩略图哈希去重（同一句字幕只识别一次，省 80%+ API 调用）
 *   → 百度 OCR → **位置过滤**（只保留水平中央带的行，排除角落水印/贴纸）
 *   + **行高一致性过滤**（字幕字号一致，表情包大字/小字被剔除）
 *   → 相邻帧文本去重合并 → 带时间轴的字幕文案。
 *
 * 结果为空/过少时返回 null，由管线回落语音识别（ASR）。
 */
class VideoOcrExtractor(context: android.content.Context) {

    data class FrameOcrResult(
        val text: String,
        val segments: List<TranscriptSegment>,
        /** 统计：总帧数 / 去重后识别帧数（用于日志与 UI 展示）。 */
        val totalFrames: Int,
        val ocrFrames: Int
    )

    private val ocrClient = BaiduOcrClient(context)

    /**
     * 对视频文件执行画面字幕提取。
     * @return 字幕结果；识别失败或无字幕时返回 null。
     */
    suspend fun extract(
        videoFile: File,
        apiKey: String,
        secretKey: String,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): FrameOcrResult? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (durationMs <= 0L || width <= 0 || height <= 0) {
                Log.w(TAG, "视频元数据读取失败 (duration=$durationMs, ${width}x$height)")
                return@withContext null
            }

            val cropTop = (height * (1f - BOTTOM_CROP_RATIO)).toInt()
            val cropHeight = height - cropTop

            // ---- 阶段 1：抽帧 + 裁剪 + 哈希去重 ----
            data class Cand(val timeMs: Long, val hash: IntArray, val bmp: Bitmap)
            val candidates = mutableListOf<Cand>()
            var lastHash: IntArray? = null
            var t = 0L
            var frameCount = 0
            while (t < durationMs) {
                val frame = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    val crop = Bitmap.createBitmap(frame, 0, cropTop, width, cropHeight)
                    if (crop.width != width || crop.height != cropHeight) {
                        // 防御：crop 尺寸不符（理论不会触发）
                        if (crop != frame) crop.recycle()
                        frame.recycle()
                    } else {
                        val h = thumbHash(crop)
                        val dup = lastHash?.let { similarEnough(it, h) } == true
                        if (!dup) {
                            candidates += Cand(t, h, crop)
                            lastHash = h
                        } else {
                            crop.recycle()
                        }
                    }
                    if (frame != crop) frame.recycle()
                }
                frameCount++
                t += FRAME_STEP_MS
                if (frameCount % 20 == 0) {
                    onProgress(0.05f + 0.35f * t / durationMs, "分析视频画面 ${t / 1000}s/${durationMs / 1000}s")
                }
            }

            Log.i(TAG, "抽帧完成: 共 $frameCount 帧, 去重后待识别 ${candidates.size} 帧")
            if (candidates.isEmpty()) return@withContext null

            // ---- 阶段 2：逐帧 OCR + 行过滤 ----
            data class Line(val timeMs: Long, val text: String)
            val lines = mutableListOf<Line>()
            candidates.forEachIndexed { idx, cand ->
                onProgress(
                    0.45f + 0.5f * idx / candidates.size,
                    "画面字幕识别 ${idx + 1}/${candidates.size}"
                )
                try {
                    val jpeg = ByteArrayOutputStream().also { out ->
                        cand.bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }.toByteArray()
                    val ocrLines = ocrClient.recognize(jpeg, apiKey, secretKey)
                    val text = filterSubtitleLines(ocrLines, cand.bmp.width)
                    if (text.isNotBlank()) {
                        lines += Line(cand.timeMs, text)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "帧 ${cand.timeMs}ms OCR 失败（跳过）: ${e.message}")
                } finally {
                    cand.bmp.recycle()
                }
                delay(OCR_INTERVAL_MS) // 限速，防 QPS 超限
            }

            Log.i(TAG, "OCR 完成: ${candidates.size} 帧 → ${lines.size} 帧有字幕")
            if (lines.size < MIN_SUBTITLE_LINES) {
                return@withContext null
            }

            // ---- 阶段 3：相邻去重合并 + 时间轴 ----
            val segments = mergeLines(lines)
            if (segments.size < MIN_SUBTITLE_LINES) return@withContext null

            val text = segments.joinToString("\n") { it.text }
            FrameOcrResult(text, segments, frameCount, candidates.size)
        } catch (e: Exception) {
            Log.e(TAG, "画面字幕提取失败: ${e.message}", e)
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    // =============================================================================
    // 过滤与合并
    // =============================================================================

    /**
     * 字幕行过滤（对抗水印/表情包/贴纸）：
     *  1. **水平中央带**：只保留行中心 ∈ [12%W, 88%W] 的行（角落水印/台标被排除）；
     *  2. **行高一致性**：保留 height ∈ [0.5×中位数, 2.2×中位数] 的行
     *    （字幕字号高度一致；表情包大字、弹幕小字被剔除）。
     * 行按纵向位置排序后拼接。
     */
    private fun filterSubtitleLines(lines: List<BaiduOcrClient.OcrLine>, frameWidth: Int): String {
        if (lines.isEmpty()) return ""
        val central = lines.filter {
            it.centerX >= frameWidth * 0.12f && it.centerX <= frameWidth * 0.88f && it.height > 0
        }
        if (central.isEmpty()) return ""
        val heights = central.map { it.height }.sorted()
        val median = heights[heights.size / 2]
        val kept = central.filter { it.height >= median * 0.5f && it.height <= median * 2.2f }
        return kept.sortedBy { it.top }.joinToString(" ") { it.text }.trim()
    }

    /** 相邻帧文本去重合并：完全相同/互相包含的合并为一句，时间轴取首次出现到最后出现。 */
    private fun mergeLines(lines: List<Pair<Long, String>>): List<TranscriptSegment> {
        if (lines.isEmpty()) return emptyList()
        data class Acc(val startMs: Long, var endMs: Long, var text: String)
        val out = mutableListOf<Acc>()
        for ((timeMs, raw) in lines) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null) {
                val a = last.text
                when {
                    a == text -> { last.endMs = timeMs + FRAME_STEP_MS; continue }
                    a.length >= text.length && a.contains(text) -> { last.endMs = timeMs + FRAME_STEP_MS; continue }
                    text.length > a.length && text.contains(a) -> {
                        last.text = text
                        last.endMs = timeMs + FRAME_STEP_MS
                        continue
                    }
                }
            }
            out += Acc(timeMs, timeMs + FRAME_STEP_MS, text)
        }
        return out.map { TranscriptSegment(it.startMs, it.endMs, it.text) }
    }

    // =============================================================================
    // 缩略图哈希（帧去重）
    // =============================================================================

    /** 32×8 亮度缩略指纹（256 维，每维 0..255000）。 */
    private fun thumbHash(bmp: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(bmp, 32, 8, true)
        val px = IntArray(256)
        small.getPixels(px, 0, 32, 0, 0, 32, 8)
        if (small != bmp) small.recycle()
        return IntArray(256) { i ->
            val p = px[i]
            ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114)
        }
    }

    /** 平均亮度差 < 阈值视为同一句字幕（字幕停留期间画面主体在小动）。 */
    private fun similarEnough(a: IntArray, b: IntArray): Boolean {
        var diff = 0L
        for (i in a.indices) diff += abs(a[i] - b[i])
        return diff.toFloat() / a.size / 1000f < HASH_DIFF_THRESHOLD
    }

    private val List<Pair<Long, String>>.size get() = this.size

    companion object {
        private const val TAG = "VideoOcrExtractor"

        /** 抽帧间隔：0.5s（一句字幕一般停留 1~3s，足够命中且控制调用量）。 */
        private const val FRAME_STEP_MS = 500L

        /** 底部字幕区裁剪比例（画面高度的底部 35%）。 */
        private const val BOTTOM_CROP_RATIO = 0.35f

        /** OCR 调用间隔（限速防 QPS 超限）。 */
        private const val OCR_INTERVAL_MS = 250L

        /** 帧哈希相似阈值（平均亮度差，0..255）。 */
        private const val HASH_DIFF_THRESHOLD = 14f

        /** 最少字幕行数：低于此判定「无硬字幕」，回落语音识别。 */
        private const val MIN_SUBTITLE_LINES = 3
    }
}
