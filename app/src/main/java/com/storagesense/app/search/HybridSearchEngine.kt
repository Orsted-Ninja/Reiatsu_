package com.storagesense.app.search

import com.storagesense.app.ai.clip.MobileCLIPModel
import com.storagesense.app.ai.embedding.TextEmbeddingModel
import com.storagesense.app.data.local.room.FileMetadataDao
import com.storagesense.app.data.local.room.ImageIndexDao
import com.storagesense.app.domain.model.SearchResult
import com.storagesense.app.domain.model.SearchSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridSearchEngine @Inject constructor(
    private val bm25Engine: Bm25SearchEngine,
    private val vectorEngine: VectorSearchEngine,
    private val textEmbeddingModel: TextEmbeddingModel,
    private val mobileClipModel: MobileCLIPModel,
    private val imageIndexDao: ImageIndexDao,
    private val fileMetadataDao: FileMetadataDao
) {
    /**
     * Executes parallel BM25 and Vector searches, fusing them with RRF (k=60).
     */
    suspend fun searchHybrid(
        query: String,
        queryVector: FloatArray? = null,
        limit: Int = 30
    ): List<SearchResult> = coroutineScope {
        val vectorInput = queryVector ?: textEmbeddingModel.embed(query)

        val bm25Deferred = async { bm25Engine.search(query, limit = 50) }
        val vectorDeferred = async { vectorEngine.search(vectorInput, limit = 50) }

        val bm25Results = bm25Deferred.await()
        val vectorResults = vectorDeferred.await()

        if (bm25Results.isEmpty() && vectorResults.isEmpty()) {
            return@coroutineScope emptyList()
        }

        if (bm25Results.isEmpty()) return@coroutineScope vectorResults.take(limit)
        if (vectorResults.isEmpty()) return@coroutineScope bm25Results.take(limit)

        RrfFusion.fuse(listOf(bm25Results, vectorResults), limit = limit)
    }

    /**
     * Searches images using MobileCLIP embeddings and OCR text
     */
    suspend fun searchImages(query: String, limit: Int = 30): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // 1. Text embedding in CLIP space or OCR keyword matching
        val ocrMatches = imageIndexDao.searchOcrText(query)
        for (match in ocrMatches) {
            val file = fileMetadataDao.getById(match.fileId) ?: continue
            results.add(
                SearchResult(
                    file = file.toDomain(),
                    matchedSnippet = match.ocrText.take(150),
                    score = 0.85f,
                    source = SearchSource.IMAGE_OCR
                )
            )
        }

        // 2. Visual embedding search against all indexed images
        val imagesWithEmbeddings = imageIndexDao.getAllWithEmbeddings()
        if (imagesWithEmbeddings.isNotEmpty()) {
            val queryVec = textEmbeddingModel.embed(query)
            for (img in imagesWithEmbeddings) {
                val emb = img.clipEmbedding ?: continue
                // If dimensions match, compute cosine similarity
                val sim = if (emb.size == queryVec.size) {
                    textEmbeddingModel.cosineSimilarity(queryVec, emb)
                } else 0f

                if (sim >= 0.30f) {
                    val file = fileMetadataDao.getById(img.fileId) ?: continue
                    results.add(
                        SearchResult(
                            file = file.toDomain(),
                            matchedSnippet = "Visual match: ${img.visualTags ?: "Image content"}",
                            score = sim,
                            source = SearchSource.IMAGE_CLIP
                        )
                    )
                }
            }
        }

        results.sortByDescending { it.score }
        return results.distinctBy { it.file.id }.take(limit)
    }
}
