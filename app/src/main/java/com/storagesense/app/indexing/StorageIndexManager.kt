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

import com.storagesense.app.ai.face.FaceClusterer
import com.storagesense.app.data.local.room.FaceClusterDao
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    private val faceClusterer: FaceClusterer,
    private val faceClusterDao: FaceClusterDao
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
                val documentsToIndex = mutableListOf<FileItem>()
                val photosToIndex = mutableListOf<FileItem>()
                for (chunk in scannedItems.chunked(batchSize)) {
                    val insertedIds = fileRepository.insertAll(chunk)
                    for ((index, item) in chunk.withIndex()) {
                        val fileId = insertedIds.getOrNull(index) ?: item.id
                        val itemWithId = if (item.id != fileId) item.copy(id = fileId) else item
                        if (item.category in listOf(
                                FileCategory.DOCUMENT_PDF,
                                FileCategory.DOCUMENT_WORD,
                                FileCategory.DOCUMENT_SLIDES,
                                FileCategory.DOCUMENT_TEXT
                            )) {
                            documentsToIndex.add(itemWithId)
                        } else if (item.category == FileCategory.IMAGE_PHOTO) {
                            photosToIndex.add(itemWithId)
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

                // Tier 2: Deep content indexing for documents (PDF, DOCX, PPTX, TXT)
                if (documentsToIndex.isNotEmpty()) {
                    // Prioritize user documents (Download, Documents, important identity files, course notes)
                    val prioritizedDocs = documentsToIndex.sortedByDescending { doc ->
                        val pathLower = doc.path.lowercase()
                        val nameLower = doc.name.lowercase()
                        var priority = 0
                        if (doc.isImportant || nameLower.contains("aadhar") || nameLower.contains("aadhaar") || nameLower.contains("pan") || nameLower.contains("resume") || nameLower.contains("passport")) {
                            priority += 100
                        }
                        if (pathLower.contains("/download/") || pathLower.contains("/documents/")) {
                            priority += 50
                        }
                        if (nameLower.contains("mod") || nameLower.contains("dl") || nameLower.contains("notes")) {
                            priority += 30
                        }
                        priority
                    }

                    _progress.value = _progress.value.copy(
                        phase = ScanPhase.INDEXING_DOCUMENTS,
                        totalToIndex = prioritizedDocs.size,
                        indexedCount = 0,
                        message = "Extracting text from ${prioritizedDocs.size} documents..."
                    )

                    for ((idx, doc) in prioritizedDocs.withIndex()) {
                        _progress.value = _progress.value.copy(
                            indexedCount = idx + 1,
                            currentFileName = doc.name,
                            message = "Indexing (${idx + 1}/${prioritizedDocs.size}): ${doc.name}"
                        )
                        indexingPipeline.indexFile(doc)
                    }
                }

                // Tier 3: Photo face indexing via Google ML Kit
                if (photosToIndex.isNotEmpty()) {
                    _progress.value = _progress.value.copy(
                        message = "Detecting faces in ${photosToIndex.size} photos..."
                    )
                    for ((idx, photo) in photosToIndex.withIndex()) {
                        if (idx % 10 == 0) {
                            _progress.value = _progress.value.copy(
                                message = "Detecting faces (${idx + 1}/${photosToIndex.size}): ${photo.name}"
                            )
                        }
                        indexingPipeline.indexFile(photo)
                    }
                }

                _progress.value = _progress.value.copy(
                    message = "Grouping faces..."
                )
                
                // Fetch unclustered faces and existing clusters
                val unclustered = faceClusterDao.getUnclusteredFaces()
                if (unclustered.isNotEmpty()) {
                    val floatEmbeddings = unclustered.map { entity ->
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
                }

                _progress.value = IndexProgress(
                    isRunning = false,
                    phase = ScanPhase.COMPLETED,
                    discoveredFiles = scannedItems.size,
                    indexedCount = documentsToIndex.size,
                    totalToIndex = documentsToIndex.size,
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
