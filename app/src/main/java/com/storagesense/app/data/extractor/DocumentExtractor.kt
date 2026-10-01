package com.storagesense.app.data.extractor

import java.io.File

data class ExtractedPage(
    val pageNumber: Int,
    val text: String
)

data class ExtractionResult(
    val fullText: String,
    val pages: List<ExtractedPage> = emptyList(),
    val needsOcrFallback: Boolean = false
)

interface DocumentExtractor {
    fun canHandle(extension: String): Boolean
    suspend fun extractText(file: File): ExtractionResult
}
