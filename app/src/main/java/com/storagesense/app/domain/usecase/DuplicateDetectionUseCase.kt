package com.storagesense.app.domain.usecase

import com.storagesense.app.domain.model.DuplicateGroup
import com.storagesense.app.search.DuplicateDetector
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DuplicateDetectionUseCase @Inject constructor(
    private val duplicateDetector: DuplicateDetector
) {
    suspend fun findExactDuplicates(): List<DuplicateGroup> {
        return duplicateDetector.findExactDuplicates()
    }

    suspend fun findNearDuplicates(): List<DuplicateGroup> {
        val nearDocs = duplicateDetector.findNearDuplicateDocuments()
        val nearImages = duplicateDetector.findNearDuplicateImages()
        return nearDocs + nearImages
    }

    suspend fun findAllDuplicates(): List<DuplicateGroup> {
        return findExactDuplicates() + findNearDuplicates()
    }
}
