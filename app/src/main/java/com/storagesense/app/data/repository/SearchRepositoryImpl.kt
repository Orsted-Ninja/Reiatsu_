package com.storagesense.app.data.repository

import com.storagesense.app.data.local.room.DocumentChunkDao
import com.storagesense.app.data.local.room.ImageIndexDao
import com.storagesense.app.data.local.room.SearchDao
import com.storagesense.app.data.local.room.entity.DocumentChunkEntity
import com.storagesense.app.data.local.room.entity.ImageIndexEntity
import com.storagesense.app.domain.model.SearchResult
import com.storagesense.app.domain.repository.SearchRepository
import com.storagesense.app.search.HybridSearchEngine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepositoryImpl @Inject constructor(
    private val hybridSearchEngine: HybridSearchEngine,
    private val searchDao: SearchDao,
    private val chunkDao: DocumentChunkDao,
    private val imageIndexDao: ImageIndexDao
) : SearchRepository {

    override suspend fun searchBm25(query: String, limit: Int): List<SearchResult> {
        return hybridSearchEngine.searchHybrid(query, queryVector = null, limit = limit)
    }

    override suspend fun searchVector(queryVector: FloatArray, limit: Int): List<SearchResult> {
        return hybridSearchEngine.searchHybrid("", queryVector = queryVector, limit = limit)
    }

    override suspend fun searchImagesByClip(queryVector: FloatArray, limit: Int): List<SearchResult> {
        return hybridSearchEngine.searchImages("", limit = limit)
    }

    override suspend fun searchHybrid(query: String, queryVector: FloatArray?, limit: Int): List<SearchResult> {
        return hybridSearchEngine.searchHybrid(query, queryVector = queryVector, limit = limit)
    }

    override suspend fun indexDocumentText(
        fileId: Long,
        filename: String,
        textChunks: List<String>,
        embeddings: List<FloatArray>?
    ) {
        // 1. Index in FTS5 virtual table
        searchDao.indexDocumentText(fileId, filename, textChunks)

        // 2. Index in chunk table with embeddings
        chunkDao.deleteForFile(fileId)
        val entities = textChunks.mapIndexed { idx, chunk ->
            val emb = embeddings?.getOrNull(idx)
            DocumentChunkEntity(
                fileId = fileId,
                chunkIndex = idx,
                text = chunk,
                embedding = emb,
                pageNumber = idx + 1
            )
        }
        chunkDao.insertAll(entities)
    }

    override suspend fun indexImage(fileId: Long, ocrText: String, clipEmbedding: FloatArray?) {
        imageIndexDao.deleteForFile(fileId)
        imageIndexDao.insertOrUpdate(
            ImageIndexEntity(
                fileId = fileId,
                ocrText = ocrText,
                clipEmbedding = clipEmbedding
            )
        )
    }

    override suspend fun removeIndicesForFile(fileId: Long) {
        searchDao.removeIndicesForFile(fileId)
        chunkDao.deleteForFile(fileId)
        imageIndexDao.deleteForFile(fileId)
    }
}
