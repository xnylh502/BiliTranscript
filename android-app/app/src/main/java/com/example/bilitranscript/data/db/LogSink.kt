package com.example.bilitranscript.data.db

import com.example.bilitranscript.HistoryRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// =============================================================================
// Mappers
// =============================================================================

fun HistoryRecordEntity.toDomain(): HistoryRecord = HistoryRecord(
    id = id,
    bvid = bvid,
    title = title,
    text = text,
    wordCount = wordCount,
    durationSec = durationSec,
    source = source,
    createdAt = createdAt,
    favorite = favorite
)

fun HistoryRecord.toEntity(): HistoryRecordEntity = HistoryRecordEntity(
    id = id,
    bvid = bvid,
    title = title,
    text = text,
    wordCount = wordCount,
    durationSec = durationSec,
    source = source,
    createdAt = createdAt,
    favorite = favorite
)

// =============================================================================
// LogSink — fire-and-forget logger to Room
// =============================================================================

/**
 * Singleton helper for writing log rows to Room. Always launches on a
 * background scope so callers from any thread (including UI) won't block.
 */
object LogSink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var db: AppDatabase? = null

    fun bind(database: AppDatabase) { db = database }

    private fun requireDb(): AppDatabase =
        db ?: error("LogSink not bound. Call LogSink.bind(...) from AppGraph.")

    fun logError(
        bvid: String? = null,
        stage: String,
        severity: String = "error",
        message: String,
        throwable: Throwable? = null
    ) {
        val entity = ErrorLogEntity(
            bvid = bvid,
            stage = stage,
            severity = severity,
            message = message,
            stackTrace = throwable?.let {
                buildString {
                    appendLine("${it::class.java.name}: ${it.message}")
                    for (el in it.stackTrace) appendLine("  at $el")
                }.take(4000)
            },
            createdAt = System.currentTimeMillis()
        )
        scope.launch { runCatching { requireDb().errorLogDao().insert(entity) } }
    }

    fun logDownload(
        modelId: String,
        fileName: String,
        mirror: String,
        bytesTotal: Long,
        bytesDone: Long,
        success: Boolean,
        errorMessage: String? = null
    ) {
        val entity = DownloadLogEntity(
            modelId = modelId,
            fileName = fileName,
            mirror = mirror,
            bytesTotal = bytesTotal,
            bytesDone = bytesDone,
            success = success,
            errorMessage = errorMessage,
            createdAt = System.currentTimeMillis()
        )
        scope.launch { runCatching { requireDb().downloadLogDao().insert(entity) } }
    }

    fun logRecognition(
        bvid: String,
        modelId: String,
        modelName: String,
        source: String,
        separationUsed: Boolean,
        subtitleUsed: Boolean,
        audioSizeBytes: Long,
        audioDurationMs: Long,
        segmentsJson: String,
        resultText: String,
        latencyMs: Long,
        historyId: String? = null
    ) {
        val entity = RecognitionLogEntity(
            historyId = historyId,
            bvid = bvid,
            modelId = modelId,
            modelName = modelName,
            source = source,
            separationUsed = separationUsed,
            subtitleUsed = subtitleUsed,
            audioSizeBytes = audioSizeBytes,
            audioDurationMs = audioDurationMs,
            segmentsJson = segmentsJson,
            resultText = resultText,
            latencyMs = latencyMs,
            createdAt = System.currentTimeMillis()
        )
        scope.launch { runCatching { requireDb().recognitionLogDao().insert(entity) } }
    }
}
