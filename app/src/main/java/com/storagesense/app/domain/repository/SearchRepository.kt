package com.storagesense.app.domain.repository

import com.storagesense.app.domain.model.SearchResult

interface SearchRepository {
    suspend fun searchBm25(query: String, limit: Int = 50): List<SearchResult>
    suspend fun searchVector(queryVector: FloatArray, limit: Int = 50): List<SearchResult>
    suspend fun searchImagesByClip(queryVector: FloatArray, limit: Int = 50): List<SearchResult>
    suspend fun searchHybrid(query: String, queryVector: FloatArray?, limit: Int = 30): List<SearchResult>
    suspend fun indexDocumentText(fileId: Long, filename: String, textChunks: List<String>, embeddings: List<FloatArray>?)
    suspend fun indexImage(fileId: Long, ocrText: String, clipEmbedding: FloatArray?)
    suspend fun removeIndicesForFile(fileId: Long)
}
