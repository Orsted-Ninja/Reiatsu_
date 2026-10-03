package com.storagesense.app.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.storagesense.app.data.local.room.entity.DocumentChunkEntity

@Dao
interface DocumentChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(chunk: DocumentChunkEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<DocumentChunkEntity>)

    @Query("SELECT * FROM document_chunks WHERE fileId = :fileId ORDER BY chunkIndex ASC")
    suspend fun getChunksForFile(fileId: Long): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks WHERE embedding IS NOT NULL LIMIT 500")
    suspend fun getAllChunksWithEmbeddings(): List<DocumentChunkEntity>

    @Query("DELETE FROM document_chunks WHERE fileId = :fileId")
    suspend fun deleteForFile(fileId: Long)

    @Query("SELECT COUNT(*) FROM document_chunks")
    suspend fun getTotalChunkCount(): Int

    @Query("SELECT DISTINCT fileId FROM document_chunks")
    suspend fun getChunkedFileIds(): List<Long>
}
