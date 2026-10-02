package com.storagesense.app.search

import com.storagesense.app.ai.embedding.TextEmbeddingModel
import com.storagesense.app.data.local.room.DocumentChunkDao
import com.storagesense.app.data.local.room.FileMetadataDao
import com.storagesense.app.domain.model.SearchResult
import com.storagesense.app.domain.model.SearchSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VectorSearchEngine @Inject constructor(
    private val chunkDao: DocumentChunkDao,
    private val fileMetadataDao: FileMetadataDao,
    private val textEmbeddingModel: TextEmbeddingModel
) {
    suspend fun search(queryVector: FloatArray, limit: Int = 50, minScore: Float = 0.25f): List<SearchResult> {
        val allChunks = chunkDao.getAllChunksWithEmbeddings()
        if (allChunks.isEmpty()) return emptyList()

        data class ScoredChunk(
            val fileId: Long,
            val chunkIndex: Int,
            val text: String,
            val pageNumber: Int?,
            val score: Float
        )

        val scored = mutableListOf<ScoredChunk>()
        for (chunk in allChunks) {
            val emb = chunk.embedding ?: continue
            val similarity = textEmbeddingModel.cosineSimilarity(queryVector, emb)
            if (similarity >= minScore) {
                scored.add(
                    ScoredChunk(
                        fileId = chunk.fileId,
                        chunkIndex = chunk.chunkIndex,
                        text = chunk.text,
                        pageNumber = chunk.pageNumber,
                        score = similarity
                    )
                )
            }
        }

        // Deduplicate chunks by fileId so each document only appears once with its highest scoring chunk
        val topChunks = scored
            .groupBy { it.fileId }
            .mapValues { (_, chunks) -> chunks.maxByOrNull { it.score }!! }
            .values
            .sortedByDescending { it.score }
            .take(limit)

        val results = mutableListOf<SearchResult>()
        for (sc in topChunks) {
            val file = fileMetadataDao.getById(sc.fileId) ?: continue
            val snippet = if (sc.text.length > 200) sc.text.substring(0, 200) + "..." else sc.text

            results.add(
                SearchResult(
                    file = file.toDomain(),
                    matchedSnippet = snippet,
                    score = sc.score,
                    source = SearchSource.VECTOR,
                    pageOrSlideNumber = sc.pageNumber
                )
            )
        }

        return results
    }
}
