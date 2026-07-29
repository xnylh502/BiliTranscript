package com.example.bilitranscript

import android.content.Context
import com.example.bilitranscript.data.db.AppDatabase
import com.example.bilitranscript.data.db.HistoryRecordEntity
import com.example.bilitranscript.data.db.toDomain
import com.example.bilitranscript.data.db.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 一条历史文案记录（不可变 / 公共领域模型）。
 *
 * 注意：存储层已经迁移到 Room (`history_records` 表)。
 * 此处保留 `data class` 作为 UI/VM 层使用的纯领域对象。
 *
 * `@Serializable` 仅用于一次性从旧 JSON 文件读取历史（迁移期）。
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
 * 历史仓库（Room 实现）：
 *  - 读：`records` 是冷流式 StateFlow，UI 监听自动刷新
 *  - 写：所有 mutation 走 suspend Room 方法
 *  - 同 BV 号去重：保留收藏状态并置顶
 */
class HistoryRepository(context: Context) {

    private val app = context.applicationContext
    private val db = AppDatabase.get(app)
    private val dao = db.historyDao()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _records = MutableStateFlow<List<HistoryRecord>>(emptyList())
    val records: StateFlow<List<HistoryRecord>> = _records

    init {
        // Room 的 Flow 是冷流，但只要订阅就会持续推送。
        // 这里用进程级 SupervisorJob 保持订阅，UI 也能直接收 Room 的 LiveData/Flow。
        scope.launch {
            dao.observeAll()
                .map { list -> list.map { it.toDomain() } }
                .collect { list -> _records.value = list }
        }
    }

    /**
     * 历史 → Room 迁移期：从旧 JSON 文件导入一次（如果表为空且文件存在）。
     * 之后由 Room 接管，文件不再读取。
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        val existing = dao.count()
        if (existing == 0) {
            val legacy = readLegacyJson()
            if (legacy.isNotEmpty()) {
                legacy.forEach { dao.insert(it) }
            }
        }
        // 立即填一份缓存（init{} 中的 flow 会持续刷新）
        _records.value = dao.getAll().map { it.toDomain() }
    }

    /** 新增一条；同 BV 号已存在则替换（保留收藏状态、移到最前）。 */
    suspend fun add(record: HistoryRecord) = withContext(Dispatchers.IO) {
        val existing = dao.getLatestByBvid(record.bvid)
        val keptFavorite = existing?.favorite == true
        val finalRecord = record.copy(favorite = record.favorite || keptFavorite)
        dao.insert(finalRecord.toEntity())
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
    }

    suspend fun toggleFavorite(id: String) = withContext(Dispatchers.IO) {
        val cur = dao.getById(id) ?: return@withContext
        dao.setFavorite(id, !cur.favorite)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.clearAll()
    }

    fun newId(): String = UUID.randomUUID().toString()

    // ---- 旧 JSON 文件一次性导入（迁移期用） ----

    private fun readLegacyJson(): List<HistoryRecordEntity> {
        val file = java.io.File(app.filesDir, LEGACY_FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val list: List<HistoryRecord> = json.decodeFromString(
                ListSerializer(HistoryRecord.serializer()),
                file.readText()
            )
            // 顺手把旧文件改名存档，避免再次导入；同时给用户一个保留旧数据的备份
            val backup = java.io.File(app.filesDir, "$LEGACY_FILE_NAME.migrated")
            if (!backup.exists()) file.renameTo(backup)
            list.map { it.toEntity() }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "legacy history JSON unreadable: ${e.message}")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "HistoryRepository"
        private const val LEGACY_FILE_NAME = "transcript_history.json"
    }
}
