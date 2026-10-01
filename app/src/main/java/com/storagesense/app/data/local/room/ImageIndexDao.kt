package com.storagesense.app.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.storagesense.app.data.local.room.entity.ImageIndexEntity

@Dao
interface ImageIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(imageIndex: ImageIndexEntity): Long

    @Query("SELECT * FROM image_indices WHERE fileId = :fileId LIMIT 1")
    suspend fun getByFileId(fileId: Long): ImageIndexEntity?

    @Query("SELECT * FROM image_indices WHERE clipEmbedding IS NOT NULL")
    suspend fun getAllWithEmbeddings(): List<ImageIndexEntity>

    @Query("SELECT * FROM image_indices WHERE ocrText LIKE '%' || :query || '%'")
    suspend fun searchOcrText(query: String): List<ImageIndexEntity>

    @Query("SELECT * FROM image_indices WHERE isScreenshot = 1")
    suspend fun getScreenshots(): List<ImageIndexEntity>

    @Query("SELECT * FROM image_indices WHERE hasHandwrittenNotes = 1")
    suspend fun getHandwrittenNotes(): List<ImageIndexEntity>

    @Query("DELETE FROM image_indices WHERE fileId = :fileId")
    suspend fun deleteForFile(fileId: Long)
}
