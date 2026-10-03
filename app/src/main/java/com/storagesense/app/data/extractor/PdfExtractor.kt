package com.storagesense.app.data.extractor

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

class PdfExtractor(context: Context) : DocumentExtractor {

    init {
        try {
            PDFBoxResourceLoader.init(context.applicationContext)
        } catch (e: Exception) {
            // Handled or already initialized
        }
    }

    override fun canHandle(extension: String): Boolean {
        return extension.equals("pdf", ignoreCase = true)
    }

    override suspend fun extractText(file: File): ExtractionResult {
        return try {
            PDDocument.load(file).use { document ->
                val numPages = document.numberOfPages
                if (numPages <= 0) return ExtractionResult(fullText = "", needsOcrFallback = true)

                val maxPages = minOf(numPages, 35)
                val stripper = PDFTextStripper().apply {
                    startPage = 1
                    endPage = maxPages
                }
                val fullText = stripper.getText(document).trim()
                val pageTexts = fullText.split("\u000c").map { it.trim() }.filter { it.isNotEmpty() }
                val pages = if (pageTexts.isNotEmpty()) {
                    pageTexts.mapIndexed { idx, txt -> ExtractedPage(idx + 1, txt) }
                } else {
                    listOf(ExtractedPage(1, fullText))
                }

                val avgCharsPerPage = if (maxPages > 0) fullText.length / maxPages else 0
                // If average extracted chars per page < 30, it is likely a scanned PDF needing OCR
                val needsOcr = maxPages > 0 && avgCharsPerPage < 30

                ExtractionResult(
                    fullText = fullText,
                    pages = pages,
                    needsOcrFallback = needsOcr
                )
            }
        } catch (e: Exception) {
            ExtractionResult(fullText = "", needsOcrFallback = true)
        }
    }
}
