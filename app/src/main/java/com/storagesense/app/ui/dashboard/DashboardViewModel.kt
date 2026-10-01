package com.storagesense.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storagesense.app.domain.model.FileCategory
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.domain.repository.FileRepository
import com.storagesense.app.indexing.FileScanner
import com.storagesense.app.indexing.IndexingPipeline
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryStat(
    val category: FileCategory,
    val title: String,
    val count: Int,
    val totalBytes: Long,
    val percentage: Float
) {
    val formattedSize: String
        get() {
            val kb = totalBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                kb >= 1.0 -> String.format("%.1f KB", kb)
                else -> "$totalBytes B"
            }
        }
}

data class DashboardUiState(
    val totalIndexedCount: Int = 0,
    val totalStorageBytes: Long = 0,
    val categories: List<CategoryStat> = emptyList(),
    val isScanning: Boolean = false,
    val recentFiles: List<FileItem> = emptyList()
) {
    val formattedTotalSize: String
        get() {
            val kb = totalStorageBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                kb >= 1.0 -> String.format("%.1f KB", kb)
                else -> "$totalStorageBytes B"
            }
        }
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val fileScanner: FileScanner,
    private val indexingPipeline: IndexingPipeline
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadStats()
        viewModelScope.launch {
            fileRepository.observeAllFiles().collect { files ->
                calculateStats(files)
            }
        }
    }

    fun loadStats() {
        viewModelScope.launch {
            val files = fileRepository.getAllFiles()
            calculateStats(files)
        }
    }

    private fun calculateStats(files: List<FileItem>) {
        val totalBytes = files.sumOf { it.sizeBytes }
        val grouped = files.groupBy { it.category }

        val categoryStats = listOf(
            FileCategory.DOCUMENT_PDF to "PDF Documents",
            FileCategory.DOCUMENT_WORD to "Word Documents",
            FileCategory.DOCUMENT_SLIDES to "Presentations",
            FileCategory.IMAGE_PHOTO to "Images & Photos",
            FileCategory.IMAGE_SCREENSHOT to "Screenshots",
            FileCategory.VIDEO to "Videos",
            FileCategory.ARCHIVE to "Archives",
            FileCategory.INSTALLER to "Installers / APKs"
        ).mapNotNull { (cat, title) ->
            val items = grouped[cat] ?: return@mapNotNull null
            val catBytes = items.sumOf { it.sizeBytes }
            val pct = if (totalBytes > 0) catBytes.toFloat() / totalBytes else 0f
            CategoryStat(cat, title, items.size, catBytes, pct)
        }

        _uiState.value = _uiState.value.copy(
            totalIndexedCount = files.size,
            totalStorageBytes = totalBytes,
            categories = categoryStats,
            recentFiles = files.take(5)
        )
    }

    fun triggerScan() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true)
            try {
                val scanned = fileScanner.scanDirectories()
                for (item in scanned) {
                    fileRepository.insertOrUpdate(item)
                    indexingPipeline.indexFile(item)
                }
                loadStats()
            } finally {
                _uiState.value = _uiState.value.copy(isScanning = false)
            }
        }
    }
}
