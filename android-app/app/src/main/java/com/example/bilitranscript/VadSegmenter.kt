package com.example.bilitranscript

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * VAD 语音活动切句（Silero VAD，模型 643KB 已内置 assets）。
 *
 * 作用（针对「有音乐的人声文案」场景）：
 *  - **跳过纯音乐/静音段**：大模型在纯 BGM 段会产生幻觉文本（编歌词/乱码），
 *    切掉后准确度与速度双赢；
 *  - **自然分段**：为识别引擎提供 ≤30s 的语义段，附带时间轴（全引擎可导出 SRT）。
 *
 * 失败安全：模型缺失或推理异常时 [segment] 返回 null，调用方回落整段识别。
 */
class VadSegmenter(private val context: Context) {

    companion object {
        private const val TAG = "VadSegmenter"
        private const val VAD_DIR = "models/vad"
        private const val VAD_ASSET = "$VAD_DIR/silero_vad.onnx"
        private const val SAMPLE_RATE = 16_000

        /** 相邻语音段间隔小于该值时合并（减少碎片）。 */
        private const val MERGE_GAP_SAMPLES = (0.4f * SAMPLE_RATE).toInt()

        /** 单段超长时硬切上限（对齐 Qwen3 30s 识别窗口）。 */
        private const val MAX_CHUNK_SAMPLES = 30 * SAMPLE_RATE

        /** VAD 分块喂入的块长（0.5s）：一次性灌入长音频会导致 sherpa VAD 段产出失效。 */
        private const val FEED_BLOCK_SAMPLES = SAMPLE_RATE / 2
    }

    /** 一段语音的范围（采样点索引，[start, end)）。 */
    data class VadChunk(val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    fun isAvailable(): Boolean = try {
        context.assets.list(VAD_DIR)?.any { it.endsWith(".onnx") } == true
    } catch (e: Exception) {
        false
    }

    /**
     * 对整段 PCM 做 VAD 切句。
     * @return 合并/截断后的语音段列表；无语音或失败返回 null。
     */
    fun segment(samples: FloatArray): List<VadChunk>? {
        if (!isAvailable() || samples.isEmpty()) return null
        return try {
            val raw = runVad(samples) ?: return null
            val merged = mergeChunks(raw)
            val clipped = clipLongChunks(merged)
            if (clipped.isEmpty()) null else {
                val speechSec = clipped.sumOf { it.length } / SAMPLE_RATE
                Log.i(TAG, "VAD 切句: 音频 ${samples.size / SAMPLE_RATE}s → ${clipped.size} 段 / 有效语音 ${speechSec}s")
                clipped
            }
        } catch (e: Exception) {
            Log.e(TAG, "VAD 切句失败，回落整段识别: ${e.message}", e)
            null
        }
    }

    /** 调 silero VAD 提取原始语音段（每次新建实例，无状态残留）。 */
    private fun runVad(samples: FloatArray): List<VadChunk>? {
        val silero = SileroVadModelConfig().apply {
            model = VAD_ASSET
            threshold = 0.5f
            minSpeechDuration = 0.25f
            minSilenceDuration = 0.5f
            maxSpeechDuration = 20f
            windowSize = 512
        }
        val config = VadModelConfig().apply {
            sileroVadModelConfig = silero
            sampleRate = SAMPLE_RATE
            numThreads = 1
            provider = "cpu"
            debug = false
        }
        val vad = try {
            Vad(context.assets, config)
        } catch (e: Exception) {
            Log.e(TAG, "VAD 引擎初始化失败: ${e.message}", e)
            return null
        }
        try {
            // 关键：必须**分块喂入 + 边喂边取段**（官方用法）。
            // 一次性灌入整段长音频时，sherpa VAD 的段产出逻辑失效——
            // 实测 211s 清晰人声只吐出末尾 0.3s；分块喂则切出 27 段/143s。
            val out = mutableListOf<VadChunk>()
            var pos = 0
            while (pos < samples.size) {
                val end = minOf(pos + FEED_BLOCK_SAMPLES, samples.size)
                vad.acceptWaveform(samples.copyOfRange(pos, end))
                while (!vad.empty()) {
                    val seg = vad.front()
                    out += VadChunk(seg.start, seg.start + seg.samples.size)
                    vad.pop()
                }
                pos = end
            }
            vad.flush()
            while (!vad.empty()) {
                val seg = vad.front()
                out += VadChunk(seg.start, seg.start + seg.samples.size)
                vad.pop()
            }
            return out
        } finally {
            try { vad.release() } catch (e: Exception) { Log.w(TAG, "VAD 释放异常: ${e.message}") }
        }
    }

    /** 合并间隔过近的相邻段。 */
    private fun mergeChunks(chunks: List<VadChunk>): List<VadChunk> {
        if (chunks.isEmpty()) return chunks
        val out = mutableListOf(chunks.first())
        for (i in 1 until chunks.size) {
            val last = out.last()
            val cur = chunks[i]
            if (cur.start - last.end < MERGE_GAP_SAMPLES) {
                out[out.lastIndex] = VadChunk(last.start, maxOf(last.end, cur.end))
            } else {
                out += cur
            }
        }
        return out
    }

    /** 超长段按 30s 硬切（保护 LLM 引擎内存峰值）。 */
    private fun clipLongChunks(chunks: List<VadChunk>): List<VadChunk> {
        val out = mutableListOf<VadChunk>()
        for (c in chunks) {
            var pos = c.start
            while (pos < c.end) {
                val end = minOf(pos + MAX_CHUNK_SAMPLES, c.end)
                out += VadChunk(pos, end)
                pos = end
            }
        }
        return out
    }
}
