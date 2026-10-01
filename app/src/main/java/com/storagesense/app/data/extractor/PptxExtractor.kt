package com.storagesense.app.data.extractor

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

class PptxExtractor : DocumentExtractor {

    override fun canHandle(extension: String): Boolean {
        return extension.equals("pptx", ignoreCase = true)
    }

    override suspend fun extractText(file: File): ExtractionResult {
        return try {
            FileInputStream(file).use { fis ->
                extractFromStream(fis)
            }
        } catch (e: Exception) {
            ExtractionResult(fullText = "")
        }
    }

    fun extractFromStream(inputStream: InputStream): ExtractionResult {
        val zip = ZipInputStream(inputStream)
        var entry = zip.nextEntry
        val slidePages = mutableListOf<ExtractedPage>()
        var slideCounter = 1

        while (entry != null) {
            val name = entry.name
            if (name.startsWith("ppt/slides/slide") && name.endsWith(".xml")) {
                val slideText = StringBuilder()
                val parser = Xml.newPullParser()
                parser.setInput(zip, "UTF-8")
                var eventType = parser.eventType

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        val tagName = parser.name
                        if (tagName == "t" || tagName.endsWith(":t")) {
                            slideText.append(parser.nextText()).append(" ")
                        } else if (tagName == "p" || tagName.endsWith(":p")) {
                            slideText.append("\n")
                        }
                    }
                    eventType = parser.next()
                }

                val trimmed = slideText.toString().trim()
                if (trimmed.isNotEmpty()) {
                    slidePages.add(ExtractedPage(slideCounter, trimmed))
                }
                slideCounter++
            }
            entry = zip.nextEntry
        }

        val fullText = slidePages.joinToString("\n\n") { it.text }
        return ExtractionResult(
            fullText = fullText,
            pages = slidePages,
            needsOcrFallback = false
        )
    }
}
