package com.example.bilitranscript.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a saved transcript (history list, search, favorite).
 * One-to-one mapping with the public [com.example.bilitranscript.HistoryRecord].
 */
@Entity(
    tableName = "history_records",
    indices = [
        Index(value = ["bvid"], unique = false),
        Index(value = ["created_at"], unique = false)
    ]
)
data class HistoryRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "bvid") val bvid: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "word_count") val wordCount: Int,
    @ColumnInfo(name = "duration_sec") val durationSec: Int,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "favorite") val favorite: Boolean = false
)

/**
 * Recognition chain log: traceability row.
 * Records the full ASR pipeline metadata per extraction (what was
 * actually run vs the original source audio).
 *
 *  - modelId: sensevoice / funasr-nano / paraformer-trilingual / qwen3-asr-0.6b / cloud-xxx
 *  - source: 官方字幕 / 离线引擎 / 云端API
 *  - separationUsed: was GT-CRN applied first?
 *  - subtitleUsed: was the video's own subtitle used (skipped recognition)?
 *  - audioSizeBytes: bytes of the audio actually fed into the engine
 *  - audioDurationMs: length of that audio
 *  - segmentsJson: serialized segment list (for SRT re-export)
 *  - createdAt: ms epoch
 *  - latencyMs: wall-clock time spent in the recognition step
 */
@Entity(
    tableName = "recognition_logs",
    indices = [
        Index(value = ["bvid"], unique = false),
        Index(value = ["history_id"], unique = false),
        Index(value = ["created_at"], unique = false)
    ]
)
data class RecognitionLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "history_id") val historyId: String?,
    @ColumnInfo(name = "bvid") val bvid: String,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "model_name") val modelName: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "separation_used") val separationUsed: Boolean,
    @ColumnInfo(name = "subtitle_used") val subtitleUsed: Boolean,
    @ColumnInfo(name = "audio_size_bytes") val audioSizeBytes: Long,
    @ColumnInfo(name = "audio_duration_ms") val audioDurationMs: Long,
    @ColumnInfo(name = "segments_json") val segmentsJson: String,
    @ColumnInfo(name = "result_text") val resultText: String,
    @ColumnInfo(name = "latency_ms") val latencyMs: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * Error log: captures every non-fatal / fatal error during pipeline
 * (download failed, recognition crashed, OOM, etc.) for post-mortem.
 */
@Entity(
    tableName = "error_logs",
    indices = [Index(value = ["created_at"], unique = false)]
)
data class ErrorLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "bvid") val bvid: String?,
    @ColumnInfo(name = "stage") val stage: String,   // download / separation / recognition / export
    @ColumnInfo(name = "severity") val severity: String,  // warn / error / fatal
    @ColumnInfo(name = "message") val message: String,
    @ColumnInfo(name = "stack_trace") val stackTrace: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * Download log: tracks each download attempt for any model file.
 * (Replaces the need for ad-hoc logcat scanning.)
 */
@Entity(
    tableName = "download_logs",
    indices = [
        Index(value = ["model_id"], unique = false),
        Index(value = ["created_at"], unique = false)
    ]
)
data class DownloadLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "mirror") val mirror: String,
    @ColumnInfo(name = "bytes_total") val bytesTotal: Long,
    @ColumnInfo(name = "bytes_done") val bytesDone: Long,
    @ColumnInfo(name = "success") val success: Boolean,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
