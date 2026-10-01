package com.storagesense.app.search

import com.storagesense.app.data.local.room.FileMetadataDao
import com.storagesense.app.data.local.room.SearchDao
import com.storagesense.app.domain.model.SearchResult
import com.storagesense.app.domain.model.SearchSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Bm25SearchEngine @Inject constructor(
    private val searchDao: SearchDao,
    private val fileMetadataDao: FileMetadataDao
) {
    suspend fun search(query: String, limit: Int = 50): List<SearchResult> {
        val sanitized = searchDao.sanitizeQuery(query)
        if (sanitized.isBlank()) return emptyList()

        val matches = searchDao.searchBm25(sanitized, limit)
        val results = mutableListOf<SearchResult>()

        for (match in matches) {
            val fileEntity = fileMetadataDao.getById(match.fileId) ?: continue
            // In FTS5 BM25, more negative score is better. Normalize to positive confidence [0, 1]
            val normalizedScore = (1.0f / (1.0f + Math.abs(match.bm25Score))).coerceIn(0.1f, 1.0f)

            results.add(
                SearchResult(
                    file = fileEntity.toDomain(),
                    matchedSnippet = match.snippet,
                    score = normalizedScore,
                    source = SearchSource.BM25,
                    pageOrSlideNumber = match.pageNumber
                )
            )
        }

        return results
    }
}
