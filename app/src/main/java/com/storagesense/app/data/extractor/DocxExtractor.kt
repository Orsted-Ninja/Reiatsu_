package com.storagesense.app.data.extractor

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

class DocxExtractor : DocumentExtractor {

    override fun canHandle(extension: String): Boolean {
        return extension.equals("docx", ignoreCase = true)
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
        val sb = StringBuilder()

        while (entry != null) {
            if (entry.name == "word/document.xml") {
                val parser = Xml.newPullParser()
                parser.setInput(zip, "UTF-8")
                var eventType = parser.eventType

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        val name = parser.name
                        if (name == "t" || name.endsWith(":t")) {
                            sb.append(parser.nextText()).append(" ")
                        } else if (name == "p" || name.endsWith(":p")) {
                            sb.append("\n")
                        }
                    }
                    eventType = parser.next()
                }
                break
            }
            entry = zip.nextEntry
        }

        val text = sb.toString().trim()
        return ExtractionResult(
            fullText = text,
            pages = listOf(ExtractedPage(1, text)),
            needsOcrFallback = false
        )
    }
}
