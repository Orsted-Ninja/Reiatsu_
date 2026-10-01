package com.storagesense.app.data.extractor

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExtractorFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val pdfExtractor by lazy { PdfExtractor(context) }
    private val docxExtractor by lazy { DocxExtractor() }
    private val pptxExtractor by lazy { PptxExtractor() }
    private val plainTextExtractor by lazy { PlainTextExtractor() }

    private val extractors: List<DocumentExtractor> by lazy {
        listOf(pdfExtractor, docxExtractor, pptxExtractor, plainTextExtractor)
    }

    fun getExtractor(extension: String): DocumentExtractor? {
        return extractors.firstOrNull { it.canHandle(extension) }
    }

    fun isSupported(extension: String): Boolean {
        return getExtractor(extension) != null
    }
}
