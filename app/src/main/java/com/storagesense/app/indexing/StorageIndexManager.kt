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
    private val searchDao: SearchDao
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

                // Batch insert into database
                val batchSize = 100
                for (chunk in scannedItems.chunked(batchSize)) {
                    val insertedIds = fileRepository.insertAll(chunk)
                    for ((index, item) in chunk.withIndex()) {
                        val fileId = insertedIds.getOrNull(index) ?: item.id
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
                val documents = scannedItems.filter {
                    it.category in listOf(
                        FileCategory.DOCUMENT_PDF,
                        FileCategory.DOCUMENT_WORD,
                        FileCategory.DOCUMENT_SLIDES,
                        FileCategory.DOCUMENT_TEXT
                    )
                }

                if (documents.isNotEmpty()) {
                    _progress.value = _progress.value.copy(
                        phase = ScanPhase.INDEXING_DOCUMENTS,
                        totalToIndex = documents.size,
                        indexedCount = 0,
                        message = "Extracting text from ${documents.size} documents..."
                    )

                    for ((idx, doc) in documents.withIndex()) {
                        _progress.value = _progress.value.copy(
                            indexedCount = idx + 1,
                            currentFileName = doc.name,
                            message = "Indexing (${idx + 1}/${documents.size}): ${doc.name}"
                        )
                        indexingPipeline.indexFile(doc)
                    }
                }

                _progress.value = IndexProgress(
                    isRunning = false,
                    phase = ScanPhase.COMPLETED,
                    discoveredFiles = scannedItems.size,
                    indexedCount = documents.size,
                    totalToIndex = documents.size,
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
