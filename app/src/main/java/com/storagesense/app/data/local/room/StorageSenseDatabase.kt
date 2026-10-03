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
        ActionLogEntity::class,
        com.storagesense.app.ai.face.FaceClusterEntity::class
    ],
    version = 6,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class StorageSenseDatabase : RoomDatabase() {
    abstract fun fileMetadataDao(): FileMetadataDao
    abstract fun documentChunkDao(): DocumentChunkDao
    abstract fun imageIndexDao(): ImageIndexDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun faceClusterDao(): FaceClusterDao

    companion object {
        private const val DB_NAME = "storagesense.db"

        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE face_clusters ADD COLUMN personName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE face_clusters ADD COLUMN thumbnailPath TEXT DEFAULT NULL")
            }
        }

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
                    .addMigrations(MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
