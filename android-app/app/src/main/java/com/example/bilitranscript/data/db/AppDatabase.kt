package com.example.bilitranscript.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * App-wide Room (SQLite) database.
 *
 * Schema v1 contains:
 *  - history_records  : saved transcripts (replaces the old JSON file)
 *  - recognition_logs : per-extraction ASR pipeline metadata (traceability)
 *  - error_logs       : every caught exception during pipeline execution
 *  - download_logs    : per-attempt model download events
 *
 * NOTE: When adding a new entity, bump [version] and write a migration
 * (or set [fallbackToDestructiveMigration] = true in dev to nuke the DB).
 */
@Database(
    entities = [
        HistoryRecordEntity::class,
        RecognitionLogEntity::class,
        ErrorLogEntity::class,
        DownloadLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun historyDao(): HistoryDao
    abstract fun recognitionLogDao(): RecognitionLogDao
    abstract fun errorLogDao(): ErrorLogDao
    abstract fun downloadLogDao(): DownloadLogDao

    companion object {
        private const val DB_NAME = "bilitranscript.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    // Dev: nuke DB on schema mismatch. Replace with real
                    // migrations once we ship v2+.
                    .fallbackToDestructiveMigration(false)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
