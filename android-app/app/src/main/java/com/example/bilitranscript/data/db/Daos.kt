package com.example.bilitranscript.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// =============================================================================
// History DAO
// =============================================================================

@Dao
interface HistoryDao {

    /** Live-observed list, sorted newest first. */
    @Query("SELECT * FROM history_records ORDER BY created_at DESC")
    fun observeAll(): Flow<List<HistoryRecordEntity>>

    /** Initial one-shot read for [com.example.bilitranscript.HistoryRepository.load]. */
    @Query("SELECT * FROM history_records ORDER BY created_at DESC")
    suspend fun getAll(): List<HistoryRecordEntity>

    @Query("SELECT * FROM history_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): HistoryRecordEntity?

    @Query("SELECT * FROM history_records WHERE bvid = :bvid ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestByBvid(bvid: String): HistoryRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: HistoryRecordEntity)

    @Update
    suspend fun update(record: HistoryRecordEntity)

    @Query("DELETE FROM history_records WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM history_records WHERE bvid = :bvid")
    suspend fun deleteByBvid(bvid: String)

    @Query("DELETE FROM history_records")
    suspend fun clearAll()

    @Query("UPDATE history_records SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("SELECT COUNT(*) FROM history_records")
    suspend fun count(): Int
}

// =============================================================================
// Recognition log DAO
// =============================================================================

@Dao
interface RecognitionLogDao {

    @Query("SELECT * FROM recognition_logs ORDER BY created_at DESC")
    fun observeAll(): Flow<List<RecognitionLogEntity>>

    @Query("SELECT * FROM recognition_logs WHERE history_id = :historyId ORDER BY created_at DESC")
    fun observeByHistoryId(historyId: String): Flow<List<RecognitionLogEntity>>

    @Query("SELECT * FROM recognition_logs WHERE bvid = :bvid ORDER BY created_at DESC")
    suspend fun getByBvid(bvid: String): List<RecognitionLogEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(log: RecognitionLogEntity): Long

    @Query("DELETE FROM recognition_logs WHERE created_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM recognition_logs")
    suspend fun count(): Int
}

// =============================================================================
// Error log DAO
// =============================================================================

@Dao
interface ErrorLogDao {

    @Query("SELECT * FROM error_logs ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<ErrorLogEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(log: ErrorLogEntity): Long

    @Query("DELETE FROM error_logs WHERE created_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM error_logs")
    suspend fun clearAll()
}

// =============================================================================
// Download log DAO
// =============================================================================

@Dao
interface DownloadLogDao {

    @Query("SELECT * FROM download_logs ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<DownloadLogEntity>>

    @Query("SELECT * FROM download_logs WHERE model_id = :modelId ORDER BY created_at DESC LIMIT :limit")
    fun observeByModel(modelId: String, limit: Int = 50): Flow<List<DownloadLogEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(log: DownloadLogEntity): Long

    @Query("DELETE FROM download_logs WHERE created_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM download_logs WHERE model_id = :modelId")
    suspend fun clearByModel(modelId: String)
}
