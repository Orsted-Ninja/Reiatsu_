package com.storagesense.app.indexing

import android.content.Context
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
    private val imageAnalyzer: com.storagesense.app.ai.vision.ImageAnalyzer
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()

    @Volatile
    private var isScanRunning = false

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
                val batchSize = 100
                val filesToProcess = mutableListOf<FileItem>()
                for (chunk in scannedItems.chunked(batchSize)) {
                    val insertedIds = fileRepository.insertAll(chunk)
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
                        searchDao.indexDocumentText(
                            fileId = fileId,
                            filename = item.name,
                            textChunks = listOf(item.name)
                        )
                    }
                }

                _progress.value = _progress.value.copy(
                    discoveredFiles = scannedItems.size,
                    message = "Discovered ${scannedItems.size} files. Indexing document contents..."
                )

                // Tier 2: Deep content indexing (Documents + Images)
                if (filesToProcess.isNotEmpty()) {
                    // Prioritize user documents and images
                    val prioritizedFiles = filesToProcess.sortedByDescending { doc ->
                        val pathLower = doc.path.lowercase()
                        val nameLower = doc.name.lowercase()
                        var priority = 0
                        if (doc.isImportant || nameLower.contains("aadhar") || nameLower.contains("aadhaar") || nameLower.contains("pan") || nameLower.contains("resume") || nameLower.contains("passport")) {
                            priority += 100
                        }
                        if (pathLower.contains("/download/") || pathLower.contains("/documents/")) {
                            priority += 50
                        }
                        if (doc.category == FileCategory.IMAGE_PHOTO) {
                            priority += 40
                        }
                        priority
                    }

                    _progress.value = _progress.value.copy(
                        phase = ScanPhase.INDEXING_DOCUMENTS,
                        totalToIndex = prioritizedFiles.size,
                        indexedCount = 0,
                        message = "Processing ${prioritizedFiles.size} files..."
                    )

                    for ((idx, doc) in prioritizedFiles.withIndex()) {
                        _progress.value = _progress.value.copy(
                            indexedCount = idx + 1,
                            currentFileName = doc.name,
                            message = "Analyzing (${idx + 1}/${prioritizedFiles.size}): ${doc.name}"
                        )
                        if (doc.category == FileCategory.IMAGE_PHOTO || doc.category == FileCategory.IMAGE_SCREENSHOT) {
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
                            } catch (e: Exception) {
                                // Ignore single image analysis failure (corrupt image, OOM, etc.)
                            }
                            
                            // RESTORE PIPELINE: Generate MobileCLIP embeddings and FTS Index
                            indexingPipeline.indexFile(finalDoc)
                            
                            // Cooperatively yield to prevent CPU starvation while indexing at maximum efficient throughput
                            kotlinx.coroutines.yield()
                        } else {
                            indexingPipeline.indexFile(doc)
                            kotlinx.coroutines.yield()
                        }
                    }
                }

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
}
