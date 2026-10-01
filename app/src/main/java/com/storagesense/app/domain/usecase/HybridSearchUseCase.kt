package com.storagesense.app.domain.usecase

import com.storagesense.app.domain.model.SearchResult
import com.storagesense.app.domain.repository.SearchRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridSearchUseCase @Inject constructor(
    private val searchRepository: SearchRepository
) {
    suspend operator fun invoke(
        query: String,
        isImageSearch: Boolean = false,
        limit: Int = 30
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        return if (isImageSearch) {
            searchRepository.searchImagesByClip(FloatArray(0), limit = limit)
        } else {
            searchRepository.searchHybrid(query, queryVector = null, limit = limit)
        }
    }
}
