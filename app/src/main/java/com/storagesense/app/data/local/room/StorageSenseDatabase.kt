package com.storagesense.app.data.local.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.storagesense.app.data.local.room.entity.ActionLogEntity
import com.storagesense.app.data.local.room.entity.DocumentChunkEntity
import com.storagesense.app.data.local.room.entity.FileMetadataEntity
import com.storagesense.app.data.local.room.entity.ImageIndexEntity

@Database(
    entities = [
        FileMetadataEntity::class,
        DocumentChunkEntity::class,
        ImageIndexEntity::class,
        ActionLogEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class StorageSenseDatabase : RoomDatabase() {
    abstract fun fileMetadataDao(): FileMetadataDao
    abstract fun documentChunkDao(): DocumentChunkDao
    abstract fun imageIndexDao(): ImageIndexDao
    abstract fun actionLogDao(): ActionLogDao

    companion object {
        private const val DB_NAME = "storagesense.db"

        @Volatile
        private var INSTANCE: StorageSenseDatabase? = null

        fun getInstance(context: Context): StorageSenseDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    StorageSenseDatabase::class.java,
                    DB_NAME
                )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Create SQLite FTS4 virtual table for lightning-fast keyword matching
                            db.execSQL(
                                """
                                CREATE VIRTUAL TABLE IF NOT EXISTS file_fts USING fts4(
                                    file_id,
                                    filename,
                                    content,
                                    page_number
                                );
                                """.trimIndent()
                            )
                        }

                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            db.execSQL(
                                """
                                CREATE VIRTUAL TABLE IF NOT EXISTS file_fts USING fts4(
                                    file_id,
                                    filename,
                                    content,
                                    page_number
                                );
                                """.trimIndent()
                            )
                        }
                    })
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
