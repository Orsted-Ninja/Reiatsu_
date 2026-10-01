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
                val pages = mutableListOf<ExtractedPage>()
                val stripper = PDFTextStripper()
                val fullTextSb = StringBuilder()

                for (page in 1..numPages) {
                    stripper.startPage = page
                    stripper.endPage = page
                    val pageText = stripper.getText(document).trim()
                    if (pageText.isNotEmpty()) {
                        pages.add(ExtractedPage(page, pageText))
                        fullTextSb.append(pageText).append("\n\n")
                    }
                }

                val fullText = fullTextSb.toString().trim()
                val avgCharsPerPage = if (numPages > 0) fullText.length / numPages else 0
                // If average extracted chars per page < 30, it is likely a scanned PDF needing OCR
                val needsOcr = numPages > 0 && avgCharsPerPage < 30

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
