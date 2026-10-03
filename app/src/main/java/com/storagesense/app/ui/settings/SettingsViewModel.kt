package com.storagesense.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storagesense.app.action.SafeFileOps
import com.storagesense.app.ai.embedding.TextEmbeddingModel
import com.storagesense.app.ai.llm.OnDeviceLlmEngine
import com.storagesense.app.domain.repository.FileRepository
import com.storagesense.app.indexing.IndexProgress
import com.storagesense.app.indexing.StorageIndexManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

import com.storagesense.app.indexing.FolderConfigManager
import com.storagesense.app.indexing.IndexedFolder
import com.storagesense.app.data.local.room.SearchDao

data class SettingsUiState(
    val trashCount: Int = 0,
    val trashSizeFormatted: String = "0 B",
    val totalFilesIndexed: Int = 0,
    val indexProgress: IndexProgress = IndexProgress(),
    val isNeuralModelActive: Boolean = false,
    val onDeviceLlmName: String? = null,
    val isOnDeviceLlmReady: Boolean = false,
    val llmModelStatus: String = "Awaiting Model",
    val actionMessage: String? = null,
    val folders: List<IndexedFolder> = emptyList(),
    val suggestedFolders: List<String> = emptyList()
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val safeFileOps: SafeFileOps,
    private val storageIndexManager: StorageIndexManager,
    private val fileRepository: FileRepository,
    private val textEmbeddingModel: TextEmbeddingModel,
    private val onDeviceLlmEngine: OnDeviceLlmEngine,
    private val folderConfigManager: FolderConfigManager,
    private val searchDao: SearchDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshStats()
        viewModelScope.launch {
            folderConfigManager.folders.collect { list ->
                _uiState.value = _uiState.value.copy(
                    folders = list,
                    suggestedFolders = folderConfigManager.getSuggestedFolders()
                )
            }
        }
        viewModelScope.launch {
            textEmbeddingModel.loadModel()
            val isReady = onDeviceLlmEngine.isModelAvailable()
            val name = onDeviceLlmEngine.getDetectedModelName()
            _uiState.value = _uiState.value.copy(
                isNeuralModelActive = textEmbeddingModel.isNeuralOnnxActive,
                isOnDeviceLlmReady = isReady,
                onDeviceLlmName = name,
                llmModelStatus = if (isReady) "Active: $name" else "Ready (Listening to /sdcard/StorageSense/models)"
            )
        }
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
            val modelName = onDeviceLlmEngine.getDetectedModelName()
            val isModelReady = onDeviceLlmEngine.isModelAvailable()
            _uiState.value = _uiState.value.copy(
                trashCount = count,
                trashSizeFormatted = formatBytes(bytes),
                totalFilesIndexed = totalFiles,
                onDeviceLlmName = modelName,
                isOnDeviceLlmReady = isModelReady,
                llmModelStatus = if (isModelReady) "Active: $modelName" else "Ready (Listening to /sdcard/StorageSense/models)"
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

    fun toggleFolder(folder: IndexedFolder, isEnabled: Boolean) {
        viewModelScope.launch {
            folderConfigManager.toggleFolder(folder.id, isEnabled)
            if (!isEnabled) {
                fileRepository.deleteByPathPrefix(folder.path)
                searchDao.cleanupOrphanFts()
                refreshStats()
                _uiState.value = _uiState.value.copy(
                    actionMessage = "Disabled ${folder.displayName} (Index updated)"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    actionMessage = "Enabled ${folder.displayName}. Tap 'Re-scan Storage' to index."
                )
            }
        }
    }

    fun addCustomFolder(path: String, customName: String = "") {
        viewModelScope.launch {
            val result = folderConfigManager.addCustomFolder(path, customName)
            if (result.isSuccess) {
                val folder = result.getOrThrow()
                _uiState.value = _uiState.value.copy(
                    actionMessage = "Added ${folder.displayName}. Tap 'Re-scan Storage' to index."
                )
            } else {
                val msg = result.exceptionOrNull()?.message ?: "Failed to add folder"
                _uiState.value = _uiState.value.copy(actionMessage = msg)
            }
        }
    }

    fun removeCustomFolder(folder: IndexedFolder) {
        viewModelScope.launch {
            folderConfigManager.removeCustomFolder(folder.id)
            fileRepository.deleteByPathPrefix(folder.path)
            searchDao.cleanupOrphanFts()
            refreshStats()
            _uiState.value = _uiState.value.copy(
                actionMessage = "Removed ${folder.displayName} from index"
            )
        }
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
