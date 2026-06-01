package com.example.bilitranscript

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 共享提取管线：链接 → 视频信息 → [字幕优先] → 下载(低码率) → 解码 → [人声分离] → 识别。
 *
 * MainViewModel 和 FloatingBallService 都用它，消除了原先两份重复的提取逻辑（DRY）。
 * 缓存音频用 try/finally 保证「无论成败都删」，修复了原来出错时不清缓存的漏洞。
 */
class TranscriptionPipeline(
    private val context: Context,
    private val downloader: BiliDownloader,
    private val recognizer: SpeechRecognizer,
    private val separator: VocalSeparator,
    private val settingsRepo: SettingsRepository
) {
    private val audioDecoder = AudioDecoder()

    /**
     * 执行完整提取。可在主线程安全调用（内部切到 IO）。
     * @throws Exception 解析/下载/识别失败时抛出，由调用方兜底展示。
     */
    suspend fun extract(rawUrl: String, onProgress: ProgressCallback): TranscriptOutcome =
        withContext(Dispatchers.IO) {
            val settings = settingsRepo.settings.value

            onProgress(0.05f, "解析链接")
            val bvid = downloader.extractBvid(rawUrl)
                ?: throw Exception("无法从链接中识别 BV 号，请检查链接格式")

            onProgress(0.12f, "获取视频信息")
            val videoInfo = downloader.getVideoInfo(bvid)

            // ---- 字幕优先：有官方/AI字幕直接秒出，100% 准确 ----
            if (settings.subtitleFirst) {
                onProgress(0.25f, "查找官方字幕")
                val sub = downloader.fetchSubtitle(videoInfo, settings.sessdata)
                if (sub != null && sub.text.isNotBlank()) {
                    onProgress(1f, "已获取官方字幕")
                    return@withContext TranscriptOutcome(
                        title = videoInfo.title,
                        bvid = videoInfo.bvid,
                        durationSec = videoInfo.duration,
                        text = sub.text,
                        segments = sub.segments,
                        source = TranscriptSource.SUBTITLE,
                        usedVocalSeparation = false
                    )
                }
            }

            // ---- 降级：语音识别 ----
            val audioFile = File(context.cacheDir, "bili_audio_${videoInfo.bvid}.m4a")
            try {
                onProgress(0.20f, "下载音频")
                val ok = downloader.downloadAudio(videoInfo, audioFile, settings.lowBitrateAudio) { done, total ->
                    if (total > 0) {
                        val p = 0.20f + (done.toFloat() / total) * 0.40f
                        onProgress(p.coerceIn(0.20f, 0.60f), "下载音频 ${(p * 100).toInt()}%")
                    }
                }
                if (!ok) throw Exception("音频下载失败")

                onProgress(0.62f, "解码音频")
                var samples = audioDecoder.decodeToPcm(audioFile) { p ->
                    onProgress(0.62f + p * 0.16f, "解码音频 ${(p * 100).toInt()}%")
                } ?: throw Exception("音频解码失败，格式可能不支持")

                // ---- 可选人声分离（剥离 BGM）----
                if (settings.vocalSeparation && separator.isAvailable()) {
                    onProgress(0.80f, "分离人声")
                    samples = separator.separate(samples) { p ->
                        onProgress(0.80f + p * 0.05f, "分离人声 ${(p * 100).toInt()}%")
                    }
                }

                onProgress(0.86f, "准备识别引擎")
                if (!recognizer.ensureReady(settings)) {
                    throw Exception("识别引擎初始化失败")
                }

                onProgress(0.88f, "AI 识别中")
                val text = recognizer.recognizePcm(samples) { p ->
                    onProgress(0.88f + p * 0.12f, "AI 识别中 ${(p * 100).toInt()}%")
                }

                onProgress(1f, "完成")
                TranscriptOutcome(
                    title = videoInfo.title,
                    bvid = videoInfo.bvid,
                    durationSec = videoInfo.duration,
                    text = text,
                    segments = emptyList(),
                    source = recognizer.currentSource(),
                    usedVocalSeparation = settings.vocalSeparation && separator.isAvailable()
                )
            } finally {
                // 无论成功失败都清理缓存音频（修复原漏洞）
                if (audioFile.exists() && !audioFile.delete()) {
                    Log.w(TAG, "缓存音频删除失败: ${audioFile.absolutePath}")
                }
            }
        }

    companion object {
        private const val TAG = "TranscriptionPipeline"

        /**
         * 启动时清扫历史遗留的缓存音频（兜底：之前进程被杀/崩溃残留的）。
         */
        fun sweepCache(context: Context) {
            try {
                context.cacheDir.listFiles()?.forEach { f ->
                    if (f.isFile && (f.name.startsWith("bili_audio_") || f.name.startsWith("float_audio_"))) {
                        f.delete()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "清扫缓存失败: ${e.message}")
            }
        }
    }
}
