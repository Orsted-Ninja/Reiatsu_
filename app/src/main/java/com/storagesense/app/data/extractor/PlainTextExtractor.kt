package com.storagesense.app.data.extractor

import java.io.File
import java.nio.charset.StandardCharsets

class PlainTextExtractor : DocumentExtractor {

    private val supported = setOf("txt", "md", "csv", "json", "xml", "log", "kt", "java", "py", "sh")

    override fun canHandle(extension: String): Boolean {
        return supported.contains(extension.lowercase())
    }

    override suspend fun extractText(file: File): ExtractionResult {
        return try {
            val text = file.readText(StandardCharsets.UTF_8).trim()
            ExtractionResult(
                fullText = text,
                pages = listOf(ExtractedPage(1, text)),
                needsOcrFallback = false
            )
        } catch (e: Exception) {
            ExtractionResult(fullText = "")
        }
    }
}
