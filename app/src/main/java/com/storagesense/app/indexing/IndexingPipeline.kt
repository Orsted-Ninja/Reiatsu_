package com.storagesense.app.indexing

import com.storagesense.app.ai.clip.MobileCLIPModel
import com.storagesense.app.ai.embedding.TextEmbeddingModel
import com.storagesense.app.ai.ocr.OcrEngine
import com.storagesense.app.data.extractor.ExtractorFactory
import com.storagesense.app.domain.model.FileCategory
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.domain.repository.FileRepository
import com.storagesense.app.domain.repository.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

import com.storagesense.app.ai.face.FaceDetectionEngine
import com.storagesense.app.ai.face.FaceEmbeddingEngine
import com.storagesense.app.data.local.room.FaceClusterDao
import com.storagesense.app.ai.face.FaceClusterEntity
import android.graphics.BitmapFactory

@Singleton
class IndexingPipeline @Inject constructor(
    private val extractorFactory: ExtractorFactory,
    private val textChunker: TextChunker,
    private val textEmbeddingModel: TextEmbeddingModel,
    private val mobileClipModel: MobileCLIPModel,
    private val ocrEngine: OcrEngine,
    private val fileRepository: FileRepository,
    private val searchRepository: SearchRepository,
    private val faceDetectionEngine: FaceDetectionEngine,
    private val faceEmbeddingEngine: FaceEmbeddingEngine,
    private val faceClusterDao: FaceClusterDao
) {
    /**
     * Fully indexes a file: extracts text/OCR, creates chunks, computes embeddings, and stores index.
     */
    suspend fun indexFile(fileItem: FileItem): Boolean = withContext(Dispatchers.IO) {
        val file = File(fileItem.path)
        if (!file.exists() || !file.canRead()) return@withContext false

        // 1. Ensure file record exists in database
        val fileId = if (fileItem.id == 0L) {
            fileRepository.insertOrUpdate(fileItem)
        } else {
            fileItem.id
        }

        try {
            when (fileItem.category) {
                FileCategory.DOCUMENT_PDF,
                FileCategory.DOCUMENT_WORD,
                FileCategory.DOCUMENT_SLIDES,
                FileCategory.DOCUMENT_TEXT -> {
                    indexDocument(file, fileId, fileItem)
                }

                FileCategory.IMAGE_PHOTO,
                FileCategory.IMAGE_SCREENSHOT -> {
                    indexImage(file, fileId, fileItem)
                }

                else -> {
                    // For archives, videos, installers: index filename in FTS for quick keyword lookup
                    searchRepository.indexDocumentText(
                        fileId = fileId,
                        filename = fileItem.name,
                        textChunks = listOf(fileItem.name),
                        embeddings = null
                    )
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun indexDocument(file: File, fileId: Long, fileItem: FileItem) {
        val extractor = extractorFactory.getExtractor(fileItem.extension) ?: return
        val extraction = extractor.extractText(file)

        var contentText = extraction.fullText
        if ((extraction.needsOcrFallback || contentText.length < 50) && fileItem.category == FileCategory.DOCUMENT_PDF) {
            // PDF OCR fallback via PdfRenderer
            val ocr = ocrEngine.recognizePdf(file)
            if (ocr.fullText.isNotBlank()) {
                contentText = ocr.fullText
            }
        }

        if (contentText.isBlank()) {
            contentText = fileItem.name
        }

        // Chunk document text (400 tokens / ~1600 chars, 80 tokens / ~320 chars overlap)
        val chunks = textChunker.chunk(contentText)
        val chunkTexts = chunks.map { it.text }

        // Embed up to 40 primary chunks to balance neural search accuracy and indexing speed
        val embeddings = chunkTexts.take(40).map { textEmbeddingModel.embed(it) }

        searchRepository.indexDocumentText(
            fileId = fileId,
            filename = fileItem.name,
            textChunks = chunkTexts,
            embeddings = embeddings
        )
    }

    /**
     * Rapid text extraction and FTS chunking WITHOUT waiting for neural embeddings (~5-15ms per doc).
     * Extracts text, creates ~400-token chunks, and inserts them into SQLite Room & FTS4 immediately.
     */
    suspend fun indexDocumentFast(fileItem: FileItem): Boolean = withContext(Dispatchers.IO) {
        val file = File(fileItem.path)
        if (!file.exists() || !file.canRead()) return@withContext false

        val fileId = if (fileItem.id == 0L) {
            fileRepository.insertOrUpdate(fileItem)
        } else {
            fileItem.id
        }

        try {
            val extractor = extractorFactory.getExtractor(fileItem.extension) ?: return@withContext false
            val extraction = extractor.extractText(file)

            var contentText = extraction.fullText
            if ((extraction.needsOcrFallback || contentText.length < 50) && fileItem.category == FileCategory.DOCUMENT_PDF) {
                val ocr = ocrEngine.recognizePdf(file, maxPages = 2)
                if (ocr.fullText.isNotBlank()) {
                    contentText = ocr.fullText
                }
            }

            if (contentText.isBlank()) {
                contentText = fileItem.name
            }

            val chunks = textChunker.chunk(contentText)
            val chunkTexts = if (chunks.isNotEmpty()) chunks.take(35).map { it.text } else listOf(fileItem.name)

            // Index in FTS and document_chunks table with null embeddings (instantaneous!)
            searchRepository.indexDocumentText(
                fileId = fileId,
                filename = fileItem.name,
                textChunks = chunkTexts,
                embeddings = null
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun indexImage(file: File, fileId: Long, fileItem: FileItem) {
        // Fast Tier 1: OCR text extraction
        val ocrResult = ocrEngine.recognizeText(file)
        if (ocrResult.fullText.isNotBlank()) {
            searchRepository.indexDocumentText(
                fileId = fileId,
                filename = fileItem.name,
                textChunks = listOf(ocrResult.fullText),
                embeddings = listOf(textEmbeddingModel.embed(ocrResult.fullText))
            )
        }

        // Fast Tier 1: MobileCLIP image embedding (512-dim)
        val clipVec = mobileClipModel.embedImage(file)
        searchRepository.indexImage(
            fileId = fileId,
            ocrText = ocrResult.fullText,
            clipEmbedding = clipVec
        )

        // Face Grouping hook via Google ML Kit
        try {
            val detectedFaces = faceDetectionEngine.detectFacesInFile(file)
            for (face in detectedFaces) {
                // Try ONNX embedding if present, else fallback directly to Google ML Kit geometric embedding
                val embedding = face.cropBitmap?.let { faceEmbeddingEngine.getEmbedding(it) } ?: face.landmarkEmbedding

                val buffer = java.nio.ByteBuffer.allocate(embedding.size * 4)
                buffer.asFloatBuffer().put(embedding)
                
                val entity = FaceClusterEntity(
                    imagePath = file.absolutePath,
                    faceEmbedding = buffer.array(),
                    personClusterId = -1 // Unclustered
                )
                faceClusterDao.insertFace(entity)
            }
        } catch (e: Exception) {
            // Ignore face detection errors for individual files
        }
    }
}
