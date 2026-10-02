package com.storagesense.app.ai.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class OcrResult(
    val fullText: String,
    val isScreenshot: Boolean = false,
    val hasHandwrittenNotes: Boolean = false,
    val lineCount: Int = 0
)

@Singleton
class OcrEngine @Inject constructor() {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognizeText(file: File): OcrResult = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead()) return@withContext OcrResult("")
        if (file.extension.equals("pdf", ignoreCase = true)) {
            return@withContext recognizePdf(file)
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext OcrResult("")
        try {
            recognizeBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun recognizePdf(file: File, maxPages: Int = 3): OcrResult = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead()) return@withContext OcrResult("")
        try {
            val pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            val fullTextBuilder = StringBuilder()
            var totalLines = 0
            var isScreenshot = false
            var hasHandwritten = false

            val pagesToProcess = minOf(renderer.pageCount, maxPages)
            for (pageIdx in 0 until pagesToProcess) {
                val page = renderer.openPage(pageIdx)
                val scale = 2f
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                try {
                    val pageResult = recognizeBitmap(bitmap)
                    if (pageResult.fullText.isNotBlank()) {
                        fullTextBuilder.append(pageResult.fullText).append("\n\n")
                        totalLines += pageResult.lineCount
                        if (pageResult.isScreenshot) isScreenshot = true
                        if (pageResult.hasHandwrittenNotes) hasHandwritten = true
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            renderer.close()
            pfd.close()

            OcrResult(
                fullText = fullTextBuilder.toString().trim(),
                isScreenshot = isScreenshot,
                hasHandwrittenNotes = hasHandwritten,
                lineCount = totalLines
            )
        } catch (e: Exception) {
            OcrResult("")
        }
    }

    suspend fun recognizeBitmap(bitmap: Bitmap): OcrResult = suspendCancellableCoroutine { continuation ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text.trim()
                    var lineCount = 0
                    for (block in visionText.textBlocks) {
                        lineCount += block.lines.size
                    }

                    // Heuristics for screenshot detection vs handwritten notes
                    val lower = text.lowercase()
                    val isScreenshot = lower.contains("am") || lower.contains("pm") ||
                            lower.contains("wifi") || lower.contains("battery") ||
                            lower.contains("settings") || lower.contains("http") ||
                            bitmap.width in 720..1440 && bitmap.height in 1280..3200

                    val hasHandwritten = visionText.textBlocks.any { block ->
                        block.text.length < 50 && block.lines.size > 2
                    }

                    continuation.resume(
                        OcrResult(
                            fullText = text,
                            isScreenshot = isScreenshot,
                            hasHandwrittenNotes = hasHandwritten,
                            lineCount = lineCount
                        )
                    )
                }
                .addOnFailureListener {
                    continuation.resume(OcrResult(""))
                }
        } catch (e: Exception) {
            continuation.resume(OcrResult(""))
        }
    }
}
