package com.storagesense.app.domain.repository

import com.storagesense.app.domain.model.FileItem
import kotlinx.coroutines.flow.Flow

interface FileRepository {
    suspend fun insertOrUpdate(file: FileItem): Long
    suspend fun insertAll(files: List<FileItem>)
    suspend fun getFileById(id: Long): FileItem?
    suspend fun getFileByPath(path: String): FileItem?
    suspend fun getAllFiles(): List<FileItem>
    fun observeAllFiles(): Flow<List<FileItem>>
    suspend fun getFilesByHash(hash: String): List<FileItem>
    suspend fun getAllWithHashes(): List<FileItem>
    suspend fun deleteFileRecord(id: Long)
    suspend fun getTotalIndexedCount(): Int
    suspend fun getTotalStorageBytes(): Long
    suspend fun updatePath(id: Long, newPath: String)
    suspend fun getFilesLargerThan(minBytes: Long, limit: Int = 50): List<FileItem>
    suspend fun getLargestFiles(limit: Int = 20): List<FileItem>
    suspend fun getRecentFiles(sinceEpochMs: Long, limit: Int = 50): List<FileItem>
    suspend fun getFilesByCategory(category: String): List<FileItem>
}
