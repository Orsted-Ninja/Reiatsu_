package com.storagesense.app.domain.model

enum class SearchSource {
    BM25,
    VECTOR,
    HYBRID_RRF,
    IMAGE_CLIP,
    IMAGE_OCR
}

data class SearchResult(
    val file: FileItem,
    val matchedSnippet: String? = null,
    val score: Float = 0f,
    val source: SearchSource = SearchSource.HYBRID_RRF,
    val pageOrSlideNumber: Int? = null,
    val similarityPercent: Int = (score * 100).toInt().coerceIn(0, 100)
)
