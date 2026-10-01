package com.storagesense.app.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.storagesense.app.data.local.room.entity.FileMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(file: FileMetadataEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<FileMetadataEntity>)

    @Query("SELECT * FROM file_metadata WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): FileMetadataEntity?

    @Query("SELECT * FROM file_metadata WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): FileMetadataEntity?

    @Query("SELECT * FROM file_metadata ORDER BY lastModifiedEpochMs DESC")
    suspend fun getAll(): List<FileMetadataEntity>

    @Query("SELECT * FROM file_metadata ORDER BY lastModifiedEpochMs DESC")
    fun observeAll(): Flow<List<FileMetadataEntity>>

    @Query("SELECT * FROM file_metadata WHERE sha256Hash = :hash")
    suspend fun getByHash(hash: String): List<FileMetadataEntity>

    @Query("SELECT * FROM file_metadata WHERE sha256Hash IS NOT NULL AND sha256Hash != ''")
    suspend fun getAllWithHashes(): List<FileMetadataEntity>

    @Query("SELECT * FROM file_metadata WHERE category = :category ORDER BY sizeBytes DESC")
    suspend fun getByCategory(category: String): List<FileMetadataEntity>

    @Query("SELECT COUNT(*) FROM file_metadata")
    suspend fun getCount(): Int

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM file_metadata")
    suspend fun getTotalStorageBytes(): Long

    @Query("UPDATE file_metadata SET path = :newPath WHERE id = :id")
    suspend fun updatePath(id: Long, newPath: String)

    @Query("DELETE FROM file_metadata WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM file_metadata WHERE sizeBytes >= :minBytes ORDER BY sizeBytes DESC LIMIT :limit")
    suspend fun getFilesLargerThan(minBytes: Long, limit: Int = 50): List<FileMetadataEntity>

    @Query("SELECT * FROM file_metadata ORDER BY sizeBytes DESC LIMIT :limit")
    suspend fun getLargestFiles(limit: Int = 20): List<FileMetadataEntity>

    @Query("SELECT * FROM file_metadata WHERE lastModifiedEpochMs >= :sinceEpochMs ORDER BY lastModifiedEpochMs DESC LIMIT :limit")
    suspend fun getRecentFiles(sinceEpochMs: Long, limit: Int = 50): List<FileMetadataEntity>

    @Query("DELETE FROM file_metadata WHERE path = :path")
    suspend fun deleteByPath(path: String)
}
