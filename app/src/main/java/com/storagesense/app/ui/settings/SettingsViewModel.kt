package com.storagesense.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storagesense.app.action.SafeFileOps
import com.storagesense.app.domain.repository.FileRepository
import com.storagesense.app.indexing.IndexProgress
import com.storagesense.app.indexing.StorageIndexManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val trashCount: Int = 0,
    val trashSizeFormatted: String = "0 B",
    val totalFilesIndexed: Int = 0,
    val indexProgress: IndexProgress = IndexProgress(),
    val actionMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val safeFileOps: SafeFileOps,
    private val storageIndexManager: StorageIndexManager,
    private val fileRepository: FileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshStats()
        viewModelScope.launch {
            storageIndexManager.progress.collect { prog ->
                _uiState.value = _uiState.value.copy(
                    indexProgress = prog,
                    totalFilesIndexed = if (prog.discoveredFiles > 0) prog.discoveredFiles else _uiState.value.totalFilesIndexed
                )
            }
        }
    }

    fun refreshStats() {
        viewModelScope.launch {
            val (count, bytes) = safeFileOps.getTrashStats()
            val totalFiles = fileRepository.getTotalIndexedCount()
            _uiState.value = _uiState.value.copy(
                trashCount = count,
                trashSizeFormatted = formatBytes(bytes),
                totalFilesIndexed = totalFiles
            )
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            val deleted = safeFileOps.emptyTrash()
            refreshStats()
            _uiState.value = _uiState.value.copy(actionMessage = "Trash cleared ($deleted files removed)")
        }
    }

    fun triggerRescan() {
        storageIndexManager.startScan(force = true)
    }

    fun clearActionMessage() {
        _uiState.value = _uiState.value.copy(actionMessage = null)
    }

    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.1f MB", mb)
            kb >= 1.0 -> String.format("%.1f KB", kb)
            else -> "$bytes B"
        }
    }
}
