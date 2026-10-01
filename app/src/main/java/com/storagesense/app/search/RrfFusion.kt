package com.storagesense.app.search

import com.storagesense.app.domain.model.SearchResult
import com.storagesense.app.domain.model.SearchSource

object RrfFusion {

    const val DEFAULT_K = 60

    /**
     * Merges multiple ranked lists of SearchResults using Reciprocal Rank Fusion (RRF).
     * Score for each document: sum(1 / (k + rank))
     */
    fun fuse(
        rankedLists: List<List<SearchResult>>,
        k: Int = DEFAULT_K,
        limit: Int = 30
    ): List<SearchResult> {
        val rrfScores = mutableMapOf<Long, Float>()
        val fileMap = mutableMapOf<Long, SearchResult>()

        for (list in rankedLists) {
            for ((rankIndex, item) in list.withIndex()) {
                val fileId = item.file.id
                val rank = rankIndex + 1 // 1-indexed
                val reciprocalScore = 1.0f / (k + rank)

                rrfScores[fileId] = (rrfScores[fileId] ?: 0f) + reciprocalScore

                // Keep snippet with highest individual confidence
                val existing = fileMap[fileId]
                if (existing == null || item.score > existing.score) {
                    fileMap[fileId] = item
                }
            }
        }

        val sortedFileIds = rrfScores.entries
            .sortedByDescending { it.value }
            .take(limit)

        val maxRrf = sortedFileIds.firstOrNull()?.value ?: 1f

        return sortedFileIds.mapNotNull { entry ->
            val baseResult = fileMap[entry.key] ?: return@mapNotNull null
            val normalizedConfidence = (entry.value / maxRrf).coerceIn(0.1f, 1.0f)

            baseResult.copy(
                score = normalizedConfidence,
                source = SearchSource.HYBRID_RRF
            )
        }
    }
}
