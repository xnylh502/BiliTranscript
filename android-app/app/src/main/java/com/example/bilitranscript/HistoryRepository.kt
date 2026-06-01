package com.example.bilitranscript

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 一条历史文案记录（不可变）。
 */
@Serializable
data class HistoryRecord(
    val id: String,
    val bvid: String,
    val title: String,
    val text: String,
    val wordCount: Int,
    val durationSec: Int,
    /** 来源标签：官方字幕 / SenseVoice / Whisper */
    val source: String,
    val createdAt: Long,
    val favorite: Boolean = false
)

/**
 * 历史仓库：用 kotlinx.serialization 把列表存成单个 JSON 文件。
 * 零额外依赖（不引入 Room/KSP），文本量小，足够快。
 */
class HistoryRepository(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val mutex = Mutex()

    private val _records = MutableStateFlow<List<HistoryRecord>>(emptyList())
    val records: StateFlow<List<HistoryRecord>> = _records

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _records.value = readFromDisk()
        }
    }

    /** 新增一条；同 BV 号已存在则替换（保留收藏状态、移到最前）。 */
    suspend fun add(record: HistoryRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = _records.value
            val keptFavorite = existing.firstOrNull { it.bvid == record.bvid }?.favorite ?: false
            val deduped = existing.filterNot { it.bvid == record.bvid }
            val next = listOf(record.copy(favorite = record.favorite || keptFavorite)) + deduped
            persist(next)
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock { persist(_records.value.filterNot { it.id == id }) }
    }

    suspend fun toggleFavorite(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            persist(_records.value.map { if (it.id == id) it.copy(favorite = !it.favorite) else it })
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock { persist(emptyList()) }
    }

    fun newId(): String = UUID.randomUUID().toString()

    // ---- 私有 ----

    private fun persist(next: List<HistoryRecord>) {
        _records.value = next
        try {
            file.writeText(json.encodeToString(next))
        } catch (e: Exception) {
            Log.e(TAG, "写入历史失败: ${e.message}", e)
        }
    }

    private fun readFromDisk(): List<HistoryRecord> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "读取历史失败: ${e.message}", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "HistoryRepository"
        private const val FILE_NAME = "transcript_history.json"
    }
}
