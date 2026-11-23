package com.openlock.filesystem.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.Executors

@Database(entities = [VaultItemEntity::class], version = 1, exportSchema = false)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao

    companion object {
        @Volatile
        private var INSTANCE: VaultDatabase? = null

        fun getDatabase(context: Context): VaultDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    "openlock_vault_db"
                )
                    .setQueryCallback({ sqlQuery, bindArgs ->
                        Log.d("VaultSQL_Exec", "SQL Exec: $sqlQuery | Args: $bindArgs")
                    }, Executors.newSingleThreadExecutor())
                    .fallbackToDestructiveMigration(false)
                .build()

                INSTANCE = instance
                instance
            }
        }

        fun checkpointDatabase(context: Context) {
            try {
                val instance = INSTANCE ?: getDatabase(context)
                instance.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
                Log.d("VaultDatabase", "WAL Database checkpointed and truncated successfully.")
            } catch (e: Exception) {
                Log.e("VaultDatabase", "Failed to checkpoint WAL database", e)
            }
        }

        fun closeDatabase() {
            synchronized(this) {
                try {
                    INSTANCE?.let { db ->
                        if (db.isOpen) {
                            db.close()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VaultDatabase", "Error closing database", e)
                }
                INSTANCE = null
            }
        }
    }
}
