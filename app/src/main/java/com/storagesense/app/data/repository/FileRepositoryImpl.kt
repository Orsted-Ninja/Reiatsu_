package com.storagesense.app.data.repository

import com.storagesense.app.data.local.room.FileMetadataDao
import com.storagesense.app.data.local.room.entity.FileMetadataEntity
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.domain.repository.FileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileRepositoryImpl @Inject constructor(
    private val dao: FileMetadataDao
) : FileRepository {

    override suspend fun insertOrUpdate(file: FileItem): Long {
        val existing = dao.getByPath(file.path)
        val entity = if (existing != null) {
            FileMetadataEntity.fromDomain(file.copy(id = existing.id))
        } else {
            FileMetadataEntity.fromDomain(file)
        }
        return dao.insertOrUpdate(entity)
    }

    override suspend fun insertAll(files: List<FileItem>): List<Long> {
        if (files.isEmpty()) return emptyList()
        // Bulk-fetch all existing records for paths in the list (single query instead of N queries)
        val paths = files.map { it.path }
        val existingByPath = dao.getByPaths(paths).associateBy { it.path }
        val entities = files.map { file ->
            val existing = existingByPath[file.path]
            if (existing != null) {
                FileMetadataEntity.fromDomain(file.copy(id = existing.id))
            } else {
                FileMetadataEntity.fromDomain(file)
            }
        }
        return dao.insertAll(entities)
    }

    override suspend fun getFileById(id: Long): FileItem? {
        return dao.getById(id)?.toDomain()
    }

    override suspend fun getFileByPath(path: String): FileItem? {
        return dao.getByPath(path)?.toDomain()
    }

    override suspend fun getAllFiles(): List<FileItem> {
        return dao.getAll().map { it.toDomain() }
    }

    override fun observeAllFiles(): Flow<List<FileItem>> {
        return dao.observeAll().map { list -> list.map { it.toDomain() } }
    }

    override suspend fun getFilesByHash(hash: String): List<FileItem> {
        return dao.getByHash(hash).map { it.toDomain() }
    }

    override suspend fun getAllWithHashes(): List<FileItem> {
        return dao.getAllWithHashes().map { it.toDomain() }
    }

    override suspend fun deleteFileRecord(id: Long) {
        dao.deleteById(id)
    }

    override suspend fun getTotalIndexedCount(): Int {
        return dao.getCount()
    }

    override suspend fun getTotalStorageBytes(): Long {
        return dao.getTotalStorageBytes()
    }

    override suspend fun updatePath(id: Long, newPath: String) {
        dao.updatePath(id, newPath)
    }

    override suspend fun getFilesLargerThan(minBytes: Long, limit: Int): List<FileItem> {
        return dao.getFilesLargerThan(minBytes, limit).map { it.toDomain() }
    }

    override suspend fun getLargestFiles(limit: Int): List<FileItem> {
        return dao.getLargestFiles(limit).map { it.toDomain() }
    }

    override suspend fun getRecentFiles(sinceEpochMs: Long, limit: Int): List<FileItem> {
        return dao.getRecentFiles(sinceEpochMs, limit).map { it.toDomain() }
    }

    override suspend fun getFilesByCategory(category: String): List<FileItem> {
        return dao.getByCategory(category).map { it.toDomain() }
    }

    override suspend fun getFilesByFolderKeyword(folderKeyword: String, category: String?, limit: Int): List<FileItem> {
        val entities = if (category != null) {
            dao.getFilesByFolderAndCategory(folderKeyword, category, limit)
        } else {
            dao.getFilesByFolderKeyword(folderKeyword, limit)
        }
        return entities.map { it.toDomain() }
    }

    override suspend fun deleteByPathPrefix(prefix: String): Int {
        return dao.deleteByPathPrefix(prefix)
    }
}
