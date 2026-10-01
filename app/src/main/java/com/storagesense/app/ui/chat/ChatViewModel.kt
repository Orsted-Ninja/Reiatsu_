package com.storagesense.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storagesense.app.action.ActionEngine
import com.storagesense.app.ai.llm.IntentParser
import com.storagesense.app.ai.llm.RagEngine
import com.storagesense.app.domain.model.ActionProposal
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.domain.model.SearchResult
import com.storagesense.app.domain.model.SearchSource
import com.storagesense.app.domain.model.StorageIntent
import com.storagesense.app.domain.repository.FileRepository
import com.storagesense.app.domain.usecase.DuplicateDetectionUseCase
import com.storagesense.app.domain.usecase.ExecuteActionUseCase
import com.storagesense.app.domain.usecase.HybridSearchUseCase
import com.storagesense.app.domain.usecase.SpaceReclaimerUseCase
import com.storagesense.app.indexing.IndexProgress
import com.storagesense.app.indexing.StorageIndexManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isProcessing: Boolean = false,
    val pendingApproval: ActionProposal? = null,
    val indexProgress: IndexProgress = IndexProgress()
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val intentParser: IntentParser,
    private val hybridSearchUseCase: HybridSearchUseCase,
    private val duplicateDetectionUseCase: DuplicateDetectionUseCase,
    private val spaceReclaimerUseCase: SpaceReclaimerUseCase,
    private val executeActionUseCase: ExecuteActionUseCase,
    private val actionEngine: ActionEngine,
    private val ragEngine: RagEngine,
    private val fileRepository: FileRepository,
    private val storageIndexManager: StorageIndexManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        // Initial greeting
        addAssistantMessage(
            "Hello! I am **StorageSense**, your on-device AI storage assistant.\n\n" +
                    "I analyze the real files and documents on your phone.\n" +
                    "Try asking me:\n" +
                    "• *\"What is taking up space?\"*\n" +
                    "• *\"Show my largest files\"*\n" +
                    "• *\"Find all my PDFs\"*\n" +
                    "• *\"Remove duplicate assignments, keep latest\"*\n" +
                    "• *\"Free up 5 GB without deleting important\"*"
        )

        // Observe background indexing progress
        viewModelScope.launch {
            storageIndexManager.progress.collect { prog ->
                _uiState.value = _uiState.value.copy(indexProgress = prog)
            }
        }

        // Auto-start scan on launch if database is empty
        viewModelScope.launch {
            val count = fileRepository.getTotalIndexedCount()
            if (count == 0) {
                storageIndexManager.startScan()
            }
        }
    }

    fun onSendMessage(userText: String) {
        if (userText.isBlank()) return

        val userMsg = ChatMessage(
            sender = MessageSender.USER,
            text = userText.trim()
        )

        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + userMsg,
            isProcessing = true
        )

        viewModelScope.launch {
            try {
                processIntent(userText.trim())
            } catch (e: Exception) {
                addAssistantMessage("Sorry, I encountered an error processing your request: ${e.localizedMessage}")
            } finally {
                _uiState.value = _uiState.value.copy(isProcessing = false)
            }
        }
    }

    private suspend fun processIntent(userInput: String) {
        val intent = intentParser.parse(userInput)

        when (intent) {
            is StorageIntent.Audit -> {
                val totalFiles = fileRepository.getTotalIndexedCount()
                val totalBytes = fileRepository.getTotalStorageBytes()
                val largest = fileRepository.getLargestFiles(5)

                if (totalFiles == 0) {
                    addAssistantMessage("No files indexed yet. I have started a scan of your storage right now!")
                    storageIndexManager.startScan()
                    return
                }

                val sb = StringBuilder()
                sb.append("📊 **Storage Overview**\n\n")
                sb.append("• **Total Discovered:** $totalFiles files (${formatBytes(totalBytes)})\n\n")
                if (largest.isNotEmpty()) {
                    sb.append("**Top ${largest.size} Largest Files:**\n")
                    for ((idx, file) in largest.withIndex()) {
                        sb.append("${idx + 1}. **${file.name}** — ${formatBytes(file.sizeBytes)}\n")
                    }
                }

                val searchResults = largest.map {
                    SearchResult(
                        file = it,
                        matchedSnippet = "Consuming ${formatBytes(it.sizeBytes)} in ${it.path}",
                        score = 1.0f,
                        source = SearchSource.METADATA
                    )
                }

                val msg = ChatMessage(
                    sender = MessageSender.ASSISTANT,
                    text = sb.toString(),
                    searchResults = searchResults
                )
                _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + msg)
            }

            is StorageIntent.Filter -> {
                val files: List<FileItem> = when {
                    intent.folderKeyword != null -> {
                        fileRepository.getFilesByFolderKeyword(
                            folderKeyword = intent.folderKeyword,
                            category = intent.category?.name
                        )
                    }
                    intent.category != null -> {
                        fileRepository.getFilesByCategory(intent.category.name)
                    }
                    intent.recentDays != null -> {
                        val since = System.currentTimeMillis() - (intent.recentDays.toLong() * 24L * 60L * 60L * 1000L)
                        fileRepository.getRecentFiles(since, 50)
                    }
                    intent.minSizeBytes != null && intent.minSizeBytes > 0L -> {
                        fileRepository.getFilesLargerThan(intent.minSizeBytes, 50)
                    }
                    else -> {
                        fileRepository.getLargestFiles(20)
                    }
                }

                if (files.isEmpty()) {
                    addAssistantMessage("No files found matching **${intent.label}**.")
                    return
                }

                val searchResults = files.take(30).map { file ->
                    SearchResult(
                        file = file,
                        matchedSnippet = "${file.category.name.replace('_', ' ')} • ${formatBytes(file.sizeBytes)}",
                        score = 1.0f,
                        source = SearchSource.METADATA
                    )
                }

                val msg = ChatMessage(
                    sender = MessageSender.ASSISTANT,
                    text = "Found **${files.size} files** matching **${intent.label}**:",
                    searchResults = searchResults
                )
                _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + msg)
            }

            is StorageIntent.Search -> {
                val results = hybridSearchUseCase(
                    query = intent.query,
                    isImageSearch = intent.isImageSearch
                )

                val assistantMsgId = java.util.UUID.randomUUID().toString()
                val initialMsg = ChatMessage(
                    id = assistantMsgId,
                    sender = MessageSender.ASSISTANT,
                    text = "",
                    searchResults = results,
                    isStreaming = true
                )
                _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + initialMsg)

                var accumulated = ""
                ragEngine.streamAnswer(intent.query, results).collect { chunk ->
                    accumulated += chunk
                    updateMessage(assistantMsgId, accumulated, isStreaming = true, results = results)
                }
                updateMessage(assistantMsgId, accumulated, isStreaming = false, results = results)
            }

            is StorageIntent.Deduplicate -> {
                val duplicates = duplicateDetectionUseCase.findAllDuplicates()
                val totalDeleteCandidates = duplicates.flatMap { it.deleteCandidates }

                if (totalDeleteCandidates.isEmpty()) {
                    addAssistantMessage("Good news! No redundant duplicate files were found in your storage.")
                    return
                }

                val reclaimableBytes = duplicates.sumOf { it.reclaimableBytes }
                val formatted = formatBytes(reclaimableBytes)

                val proposal = actionEngine.proposeTrash(
                    files = totalDeleteCandidates,
                    reason = "Remove ${totalDeleteCandidates.size} duplicate copies (reclaim $formatted)"
                )

                val msg = ChatMessage(
                    sender = MessageSender.ASSISTANT,
                    text = "Identified **${duplicates.size} duplicate groups** (${totalDeleteCandidates.size} redundant copies) taking **$formatted**. Latest version of each file will be preserved.",
                    duplicateGroups = duplicates,
                    pendingAction = proposal
                )
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages + msg,
                    pendingApproval = proposal
                )
            }

            is StorageIntent.Cleanup -> {
                val plan = spaceReclaimerUseCase(intent.targetBytes)
                val allCleanupFiles = plan.categories.flatMap { it.items }

                if (allCleanupFiles.isEmpty()) {
                    addAssistantMessage("Your storage is already clean! No old APKs or stale files found.")
                    return
                }

                val proposal = actionEngine.proposeTrash(
                    files = allCleanupFiles,
                    reason = "Free up ${plan.formattedReclaimable} from ${plan.categories.size} categories"
                )

                val categorySummary = plan.categories.joinToString("\n") {
                    "• **${it.title}**: ${it.items.size} files (${it.formattedSize})"
                }

                val msg = ChatMessage(
                    sender = MessageSender.ASSISTANT,
                    text = "Analyzed storage to free up **${formatBytes(intent.targetBytes)}**:\n\n$categorySummary\n\n" +
                            "Protected **${plan.protectedFilesCount} critical documents** (resumes, tax records, recent files).",
                    pendingAction = proposal
                )
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages + msg,
                    pendingApproval = proposal
                )
            }

            is StorageIntent.Delete -> {
                val matches = hybridSearchUseCase(query = intent.query, limit = 20)
                if (matches.isEmpty()) {
                    addAssistantMessage("Could not find any files matching \"${intent.query}\" to delete.")
                    return
                }

                val files = matches.map { it.file }
                val proposal = if (intent.permanent) {
                    actionEngine.proposePermanentDelete(files, "Permanently delete ${files.size} files matching '${intent.query}'")
                } else {
                    actionEngine.proposeTrash(files, "Move ${files.size} files matching '${intent.query}' to trash")
                }

                val msg = ChatMessage(
                    sender = MessageSender.ASSISTANT,
                    text = "Found **${files.size} files** matching \"${intent.query}\". Review the files below before confirming.",
                    searchResults = matches,
                    pendingAction = proposal
                )
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages + msg,
                    pendingApproval = proposal
                )
            }

            is StorageIntent.Summarize -> {
                val totalFiles = fileRepository.getTotalIndexedCount()
                val totalBytes = fileRepository.getTotalStorageBytes()
                addAssistantMessage("Storage summary: $totalFiles files (${formatBytes(totalBytes)}) indexed locally on your device. Zero cloud, 100% offline.")
            }

            is StorageIntent.ChatOnly -> {
                addAssistantMessage(intent.message)
            }
        }
    }

    fun onConfirmAction(proposal: ActionProposal) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(pendingApproval = null, isProcessing = true)
            val result = executeActionUseCase.execute(proposal)

            addAssistantMessage(
                "${result.message}\n" + if (result.undoAvailable) "\n*You can undo this action anytime.*" else ""
            )
            _uiState.value = _uiState.value.copy(isProcessing = false)
        }
    }

    fun onDismissApproval() {
        _uiState.value = _uiState.value.copy(pendingApproval = null)
        addAssistantMessage("Action cancelled. No files were modified.")
    }

    fun onUndo() {
        viewModelScope.launch {
            val result = executeActionUseCase.undo()
            if (result != null) {
                addAssistantMessage(result.message)
            } else {
                addAssistantMessage("No recent actions available to undo.")
            }
        }
    }

    fun triggerScan() {
        storageIndexManager.startScan(force = true)
    }

    private fun addAssistantMessage(text: String) {
        val msg = ChatMessage(
            sender = MessageSender.ASSISTANT,
            text = text
        )
        _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + msg)
    }

    private fun updateMessage(id: String, text: String, isStreaming: Boolean, results: List<SearchResult>) {
        val updated = _uiState.value.messages.map {
            if (it.id == id) it.copy(text = text, isStreaming = isStreaming, searchResults = results) else it
        }
        _uiState.value = _uiState.value.copy(messages = updated)
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
