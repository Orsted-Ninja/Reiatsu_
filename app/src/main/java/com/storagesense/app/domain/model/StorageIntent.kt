package com.storagesense.app.domain.model

sealed interface StorageIntent {
    /**
     * Search files by keyword or semantic meaning
     */
    data class Search(
        val query: String,
        val fileTypes: List<String> = emptyList(),
        val folder: String? = null,
        val isImageSearch: Boolean = false,
        val naturalLanguageExplanation: String? = null
    ) : StorageIntent

    /**
     * Deduplicate files (exact hashes or semantic content)
     */
    data class Deduplicate(
        val targetQuery: String? = null,
        val keepStrategy: KeepStrategy = KeepStrategy.KEEP_LATEST,
        val fileTypes: List<String> = emptyList()
    ) : StorageIntent

    /**
     * Reclaim storage space up to a target size
     */
    data class Cleanup(
        val targetBytes: Long,
        val protectImportant: Boolean = true,
        val folder: String? = null
    ) : StorageIntent

    /**
     * Delete files matching specific criteria (always stages to trash first)
     */
    data class Delete(
        val query: String,
        val folder: String? = null,
        val fileTypes: List<String> = emptyList(),
        val permanent: Boolean = false
    ) : StorageIntent

    /**
     * Summarize or ask about storage stats or specific document
     */
    data class Summarize(
        val targetPath: String? = null,
        val query: String? = null
    ) : StorageIntent

    /**
     * Direct informational or general question
     */
    data class ChatOnly(
        val message: String
    ) : StorageIntent

    /**
     * Storage audit: breakdown of space, categories, and largest items
     */
    data class Audit(
        val category: FileCategory? = null,
        val showLargest: Boolean = true
    ) : StorageIntent

    /**
     * Filter files by metadata (size, category, or recency)
     */
    data class Filter(
        val minSizeBytes: Long? = null,
        val category: FileCategory? = null,
        val recentDays: Int? = null,
        val label: String
    ) : StorageIntent
}

enum class KeepStrategy {
    KEEP_LATEST,
    KEEP_LARGEST,
    KEEP_SHORTEST_PATH
}
