package com.storagesense.app.indexing

import android.content.Context
import com.storagesense.app.ai.face.FaceClusterer
import com.storagesense.app.ai.vision.ImageAnalyzer
import com.storagesense.app.data.local.room.DocumentChunkDao
import com.storagesense.app.data.local.room.FaceClusterDao
import com.storagesense.app.data.local.room.SearchDao
import com.storagesense.app.domain.model.FileCategory
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.domain.repository.FileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

enum class ScanPhase {
    IDLE,
    SCANNING_METADATA,
    INDEXING_DOCUMENTS,
    COMPLETED,
    ERROR
}

data class IndexProgress(
    val isRunning: Boolean = false,
    val phase: ScanPhase = ScanPhase.IDLE,
    val discoveredFiles: Int = 0,
    val indexedCount: Int = 0,
    val totalToIndex: Int = 0,
    val currentFileName: String = "",
    val message: String = ""
)

@Singleton
class StorageIndexManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileScanner: FileScanner,
    private val indexingPipeline: IndexingPipeline,
    private val fileRepository: FileRepository,
    private val searchDao: SearchDao,
    private val chunkDao: DocumentChunkDao,
    private val imageAnalyzer: ImageAnalyzer,
    private val faceClusterer: FaceClusterer,
    private val faceClusterDao: FaceClusterDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()

    @Volatile
    private var isScanRunning = false

    fun startFastDocumentChunking() {
        if (isScanRunning) return
        isScanRunning = true
        scope.launch {
            try {
                val allDocsInDb = (fileRepository.getFilesByCategory("DOCUMENT_PDF") +
                                  fileRepository.getFilesByCategory("DOCUMENT_WORD") +
                                  fileRepository.getFilesByCategory("DOCUMENT_SLIDES") +
                                  fileRepository.getFilesByCategory("DOCUMENT_TEXT")).distinctBy { it.id }

                val chunkedFileIds = chunkDao.getChunkedFileIds().toSet()
                val docsToProcess = allDocsInDb.filter { it.id !in chunkedFileIds }.ifEmpty { allDocsInDb }

                if (docsToProcess.isNotEmpty()) {
                    _progress.value = IndexProgress(
                        isRunning = true,
                        phase = ScanPhase.INDEXING_DOCUMENTS,
                        totalToIndex = docsToProcess.size,
                        indexedCount = 0,
                        message = "Extracting text & chunks for ${docsToProcess.size} documents..."
                    )

                    for ((idx, doc) in docsToProcess.withIndex()) {
                        if (idx % 5 == 0 || idx == docsToProcess.size - 1) {
                            _progress.value = _progress.value.copy(
                                indexedCount = idx + 1,
                                currentFileName = doc.name,
                                message = "Chunking (${idx + 1}/${docsToProcess.size}): ${doc.name}"
                            )
                        }
                        indexingPipeline.indexDocumentFast(doc)
                        yield()
                    }

                    _progress.value = IndexProgress(
                        isRunning = false,
                        phase = ScanPhase.COMPLETED,
                        indexedCount = docsToProcess.size,
                        totalToIndex = docsToProcess.size,
                        message = "Indexed ${docsToProcess.size} documents successfully!"
                    )
                }
            } catch (e: Exception) {
                _progress.value = IndexProgress(
                    isRunning = false,
                    phase = ScanPhase.ERROR,
                    message = "Document indexing error: ${e.localizedMessage}"
                )
            } finally {
                isScanRunning = false
            }
        }
    }

    fun startScan(force: Boolean = false) {
        if (isScanRunning && !force) return
        isScanRunning = true

        scope.launch {
            try {
                // Cleanup any stale/orphan FTS virtual table records before starting scan
                searchDao.cleanupOrphanFts()

                // Tier 1: Fast filesystem discovery & metadata extraction
                _progress.value = IndexProgress(
                    isRunning = true,
                    phase = ScanPhase.SCANNING_METADATA,
                    message = "Scanning storage folders..."
                )

                val scannedItems = mutableListOf<FileItem>()
                fileScanner.scanDirectories { item ->
                    scannedItems.add(item)
                    if (scannedItems.size % 20 == 0) {
                        _progress.value = _progress.value.copy(
                            discoveredFiles = scannedItems.size,
                            currentFileName = item.name,
                            message = "Found ${scannedItems.size} files..."
                        )
                    }
                }

                // Batch insert into database and retain real database IDs
                val batchSize = 250
                val filesToProcess = mutableListOf<FileItem>()
                for (chunk in scannedItems.chunked(batchSize)) {
                    val insertedIds = fileRepository.insertAll(chunk)
                    val filenamePairs = mutableListOf<Pair<Long, String>>()
                    for ((index, item) in chunk.withIndex()) {
                        val fileId = insertedIds.getOrNull(index) ?: item.id
                        val itemWithId = if (item.id != fileId) item.copy(id = fileId) else item
                        if (item.category in listOf(
                                FileCategory.DOCUMENT_PDF,
                                FileCategory.DOCUMENT_WORD,
                                FileCategory.DOCUMENT_SLIDES,
                                FileCategory.DOCUMENT_TEXT,
                                FileCategory.IMAGE_PHOTO,
                                FileCategory.IMAGE_SCREENSHOT
                            )) {
                            filesToProcess.add(itemWithId)
                        }
                        filenamePairs.add(Pair(fileId, item.name))
                    }
                    searchDao.batchIndexFilenames(filenamePairs)
                }

                _progress.value = _progress.value.copy(
                    discoveredFiles = scannedItems.size,
                    message = "Discovered ${scannedItems.size} files. Indexing document contents..."
                )

                // Tier 2: Separate documents (PDF, Word, Slides, Text) from media
                val allDocsInDb = (fileRepository.getFilesByCategory("DOCUMENT_PDF") +
                                  fileRepository.getFilesByCategory("DOCUMENT_WORD") +
                                  fileRepository.getFilesByCategory("DOCUMENT_SLIDES") +
                                  fileRepository.getFilesByCategory("DOCUMENT_TEXT")).distinctBy { it.id }

                val documentsToChunk = if (allDocsInDb.isNotEmpty()) {
                    allDocsInDb
                } else {
                    filesToProcess.filter {
                        it.category in listOf(
                            FileCategory.DOCUMENT_PDF,
                            FileCategory.DOCUMENT_WORD,
                            FileCategory.DOCUMENT_SLIDES,
                            FileCategory.DOCUMENT_TEXT
                        )
                    }
                }

                val mediaToAnalyze = filesToProcess.filter {
                    it.category in listOf(
                        FileCategory.IMAGE_PHOTO,
                        FileCategory.IMAGE_SCREENSHOT
                    )
                }

                // Phase 2: Rapid Full-Document Text Extraction & FTS Chunking (~5-15ms/doc)
                if (documentsToChunk.isNotEmpty()) {
                    _progress.value = _progress.value.copy(
                        phase = ScanPhase.INDEXING_DOCUMENTS,
                        totalToIndex = documentsToChunk.size,
                        indexedCount = 0,
                        message = "Extracting text & chunks for ${documentsToChunk.size} documents..."
                    )

                    for ((idx, doc) in documentsToChunk.withIndex()) {
                        if (idx % 10 == 0 || idx == documentsToChunk.size - 1) {
                            _progress.value = _progress.value.copy(
                                indexedCount = idx + 1,
                                currentFileName = doc.name,
                                message = "Chunking (${idx + 1}/${documentsToChunk.size}): ${doc.name}"
                            )
                        }
                        indexingPipeline.indexDocumentFast(doc)
                        yield()
                    }
                }

                // Phase 3: Media & Image Processing (Prioritized without thermal throttling)
                if (mediaToAnalyze.isNotEmpty()) {
                    val prioritizedMedia = mediaToAnalyze.take(200)
                    _progress.value = _progress.value.copy(
                        phase = ScanPhase.INDEXING_DOCUMENTS,
                        totalToIndex = prioritizedMedia.size,
                        indexedCount = 0,
                        message = "Analyzing images..."
                    )

                    var faceDetectionCounter = 0
                    for ((idx, doc) in prioritizedMedia.withIndex()) {
                        if (idx % 5 == 0 || idx == prioritizedMedia.size - 1) {
                            _progress.value = _progress.value.copy(
                                indexedCount = idx + 1,
                                currentFileName = doc.name,
                                message = "Analyzing image (${idx + 1}/${prioritizedMedia.size}): ${doc.name}"
                            )
                        }
                        var finalDoc = doc
                        try {
                            val visionResult = imageAnalyzer.analyzeImage(doc.path)
                            if (visionResult.labels.isNotEmpty() || visionResult.hasFaces) {
                                finalDoc = doc.copy(
                                    imageLabels = visionResult.labels,
                                    hasFaces = visionResult.hasFaces
                                )
                                fileRepository.insertOrUpdate(finalDoc)
                            }
                        } catch (_: Exception) {}

                        indexingPipeline.indexFile(finalDoc)
                        faceDetectionCounter++
                        if (faceDetectionCounter % 20 == 0) {
                            clusterAllFaces()
                        }
                        yield()
                    }
                }

                _progress.value = _progress.value.copy(
                    message = "Grouping faces..."
                )
                clusterAllFaces()

                _progress.value = IndexProgress(
                    isRunning = false,
                    phase = ScanPhase.COMPLETED,
                    discoveredFiles = scannedItems.size,
                    indexedCount = filesToProcess.size,
                    totalToIndex = filesToProcess.size,
                    message = "Indexing complete! ${scannedItems.size} files ready."
                )
            } catch (e: Exception) {
                _progress.value = IndexProgress(
                    isRunning = false,
                    phase = ScanPhase.ERROR,
                    message = "Scan stopped: ${e.localizedMessage}"
                )
            } finally {
                isScanRunning = false
            }
        }
    }

    private suspend fun clusterAllFaces() {
        try {
            val allFaces = faceClusterDao.getAllFaces()
            if (allFaces.isEmpty()) return

            val floatEmbeddings = allFaces.map { entity ->
                val buffer = ByteBuffer.wrap(entity.faceEmbedding).order(ByteOrder.BIG_ENDIAN).asFloatBuffer()
                val array = FloatArray(buffer.capacity())
                buffer.get(array)
                Pair(entity.id, array)
            }
            val clusters = faceClusterer.clusterFaces(floatEmbeddings)
            for ((clusterId, faceIds) in clusters) {
                for (id in faceIds) {
                    faceClusterDao.updateClusterId(id, clusterId)
                }
            }
        } catch (e: Exception) {
            // Safe logging / graceful fallback
        }
    }
}
