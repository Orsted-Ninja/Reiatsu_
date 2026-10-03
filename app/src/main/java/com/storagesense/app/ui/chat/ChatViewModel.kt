package com.storagesense.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storagesense.app.action.ActionEngine
import com.storagesense.app.ai.llm.IntentParser
import com.storagesense.app.ai.llm.RagEngine
import com.storagesense.app.ai.ocr.OcrEngine
import com.storagesense.app.data.extractor.ExtractorFactory
import com.storagesense.app.data.local.room.DocumentChunkDao
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
import com.storagesense.app.ui.util.RecentFilesHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

import android.os.Environment
import android.os.StatFs
import com.storagesense.app.domain.model.FileCategory
import com.storagesense.app.ui.components.StorageInsight
import com.storagesense.app.ui.components.StorageSegment
import com.storagesense.app.ui.theme.StorageApp
import com.storagesense.app.ui.theme.StorageDoc
import com.storagesense.app.ui.theme.StorageOther
import com.storagesense.app.ui.theme.StoragePhoto
import com.storagesense.app.ui.theme.StorageVideo

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isProcessing: Boolean = false,
    val pendingApproval: ActionProposal? = null,
    val indexProgress: IndexProgress = IndexProgress(),
    val usedBytes: Long = 128L * 1024L * 1024L * 1024L,
    val totalBytes: Long = 256L * 1024L * 1024L * 1024L,
    val segments: List<StorageSegment> = emptyList(),
    val insights: List<StorageInsight> = emptyList()
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
    private val storageIndexManager: StorageIndexManager,
    private val chunkDao: DocumentChunkDao,
    private val extractorFactory: ExtractorFactory,
    private val ocrEngine: OcrEngine,
    private val recentFilesHelper: RecentFilesHelper
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        // Initial greeting
        addAssistantMessage(
            "Hello! I am **Reiatsu**, your on-device AI storage assistant.\n\n" +
                    "I understand what's on your phone and help you make sense of it.\n" +
                    "Try asking me:\n" +
                    "• *\"What is taking up space?\"*\n" +
                    "• *\"Show my largest files\"*\n" +
                    "• *\"Find all my PDFs\"*\n" +
                    "• *\"Remove duplicate assignments, keep latest\"*\n" +
                    "• *\"Free up 5 GB without deleting important\"*"
        )

        loadStorageOverview()

        // Observe background indexing progress
        viewModelScope.launch {
            storageIndexManager.progress.collect { prog ->
                _uiState.value = _uiState.value.copy(indexProgress = prog)
                if (!prog.isRunning) {
                    loadStorageOverview()
                }
            }
        }

        // Auto-start scan or fast chunking on launch
        viewModelScope.launch {
            val count = fileRepository.getTotalIndexedCount()
            if (count == 0) {
                storageIndexManager.startScan()
            } else {
                val chunkCount = chunkDao.getTotalChunkCount()
                if (chunkCount < 100) {
                    storageIndexManager.startFastDocumentChunking()
                }
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

                val sysStats = com.storagesense.app.ui.util.StorageStatsHelper.getSystemStorageStats()
                val totalSys = sysStats.first
                val freeSys = sysStats.second
                val usedSys = totalSys - freeSys
                val osReserved = (usedSys - totalBytes).coerceAtLeast(0)

                val sb = StringBuilder()
                sb.append("📊 **Storage Overview**\n\n")
                sb.append("📱 **System Drive Used:** ${formatBytes(usedSys)} / ${formatBytes(totalSys)}\n")
                sb.append("📂 **User Accessible Files (Indexed):** $totalFiles files (${formatBytes(totalBytes)})\n")
                if (osReserved > 0) {
                    sb.append("⚙️ **OS & App Sandboxes (Inaccessible):** ${formatBytes(osReserved)}\n\n")
                } else {
                    sb.append("\n")
                }

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
                val q = intent.query?.trim().orEmpty()
                if (q.isNotBlank()) {
                    summarizeDocumentOrTopic(q)
                } else {
                    val totalFiles = fileRepository.getTotalIndexedCount()
                    val totalBytes = fileRepository.getTotalStorageBytes()
                    val sysStats = com.storagesense.app.ui.util.StorageStatsHelper.getSystemStorageStats()
                    val usedSys = sysStats.first - sysStats.second
                    addAssistantMessage("Storage summary: $totalFiles files (${formatBytes(totalBytes)}) indexed locally on your device. Android OS is consuming a total of ${formatBytes(usedSys)}. Zero cloud, 100% offline.")
                }
            }

            is StorageIntent.Undo -> {
                onUndo()
            }

            is StorageIntent.Help -> {
                if (intent.topic == "undo") {
                    val helpText = "🔄 **How to Undo Deletions & Actions in Reiatsu**:\n\n" +
                            "Reiatsu uses a **Reversible Trash Staging Engine**. When files are removed:\n\n" +
                            "1. **Undo Button (⮌)**: Tap the **Undo icon** in the top-right toolbar of the Chat or Dashboard screen anytime.\n" +
                            "2. **Instant Undo Snackbar**: When deleting files from the Dashboard, an **\"UNDO\"** button appears at the bottom of the screen.\n" +
                            "3. **Chat Command**: Just type *\"undo\"*, *\"undo last deletion\"*, or *\"restore\"* in this chat.\n\n" +
                            "*(All deleted files are safely preserved in `~/.storagesense/trash/` for 30 days and restored to their original folders with one tap.)*"
                    addAssistantMessage(helpText)
                } else {
                    addAssistantMessage("I am Reiatsu, your on-device AI assistant. Ask me to find files, free up space, or remove duplicates.")
                }
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

    fun loadStorageOverview() {
        viewModelScope.launch {
            val stat = try {
                StatFs(Environment.getDataDirectory().path)
            } catch (e: Exception) {
                null
            }
            val totalDeviceBytes = stat?.totalBytes ?: (256L * 1024L * 1024L * 1024L)
            val availableDeviceBytes = stat?.availableBytes ?: (128L * 1024L * 1024L * 1024L)
            val usedDeviceBytes = (totalDeviceBytes - availableDeviceBytes).coerceAtLeast(0L)

            // Load indexed file list for category-based size calculations.
            // Note: getAll() now returns up to 25,000 records — sufficient for all real devices.
            val files = fileRepository.getAllFiles()
            val photosBytes = files.filter { it.category == FileCategory.IMAGE_PHOTO || it.category == FileCategory.IMAGE_SCREENSHOT }.sumOf { it.sizeBytes }
            val videosBytes = files.filter { it.category == FileCategory.VIDEO }.sumOf { it.sizeBytes }
            val docsBytes = files.filter {
                it.category == FileCategory.DOCUMENT_PDF ||
                        it.category == FileCategory.DOCUMENT_WORD ||
                        it.category == FileCategory.DOCUMENT_SLIDES ||
                        it.category == FileCategory.DOCUMENT_TEXT
            }.sumOf { it.sizeBytes }

            // Apps/system: we cannot enumerate /data/app or /system, so estimate.
            // Real user media is reflected from the indexed files above.
            val appsEstBytes = (usedDeviceBytes * 0.35).toLong()
            val photosEstBytes = if (photosBytes > 0) photosBytes else (usedDeviceBytes * 0.25).toLong()
            val videosEstBytes = if (videosBytes > 0) videosBytes else (usedDeviceBytes * 0.20).toLong()
            val docsEstBytes = if (docsBytes > 0) docsBytes else (usedDeviceBytes * 0.12).toLong()
            val otherEstBytes = (usedDeviceBytes - (appsEstBytes + photosEstBytes + videosEstBytes + docsEstBytes)).coerceAtLeast(1024L * 1024L * 1024L)

            val sum = (appsEstBytes + photosEstBytes + videosEstBytes + docsEstBytes + otherEstBytes).toFloat()
            val segments = listOf(
                StorageSegment("Apps", appsEstBytes, StorageApp, appsEstBytes / sum),
                StorageSegment("Photos", photosEstBytes, StoragePhoto, photosEstBytes / sum),
                StorageSegment("Videos", videosEstBytes, StorageVideo, videosEstBytes / sum),
                StorageSegment("Documents", docsEstBytes, StorageDoc, docsEstBytes / sum),
                StorageSegment("Other", otherEstBytes, StorageOther, otherEstBytes / sum)
            )

            val insights = mutableListOf<StorageInsight>()
            if (videosEstBytes > 1024L * 1024L * 1024L) {
                insights.add(StorageInsight("Videos are using ${formatBytes(videosEstBytes)}", "Videos occupy a major portion of storage."))
            }
            insights.add(StorageInsight("Private on-device indexing", "Zero cloud uploads. Everything stays on this device."))

            _uiState.value = _uiState.value.copy(
                usedBytes = usedDeviceBytes,
                totalBytes = totalDeviceBytes,
                segments = segments,
                insights = insights
            )
        }
    }

    fun deleteFileDirectly(file: FileItem) {
        viewModelScope.launch {
            val proposal = actionEngine.proposeTrash(listOf(file), "Move ${file.name} to Trash")
            val result = actionEngine.executeAction(proposal)
            addAssistantMessage("${result.message}\n\n*Safely moved to .storagesense/trash/ (recoverable for 30 days).*")
            loadStorageOverview()
        }
    }

    fun requestDeleteFile(file: FileItem) {
        val proposal = actionEngine.proposeTrash(
            files = listOf(file),
            reason = "Move '${file.name}' (${file.formattedSize}) to trash"
        )
        _uiState.value = _uiState.value.copy(pendingApproval = proposal)
    }

    fun triggerScan() {
        storageIndexManager.startScan(force = true)
    }

    fun summarizeFile(file: FileItem) {
        val userMsg = ChatMessage(
            sender = MessageSender.USER,
            text = "Summarize ${file.name}"
        )
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + userMsg,
            isProcessing = true
        )
        viewModelScope.launch {
            try {
                summarizeDocumentOrTopic(file.name)
            } catch (e: Exception) {
                addAssistantMessage("Error summarizing ${file.name}: ${e.localizedMessage}")
            } finally {
                _uiState.value = _uiState.value.copy(isProcessing = false)
            }
        }
    }

    fun recordFileOpened(file: FileItem) {
        recentFilesHelper.recordOpened(file.path)
    }

    private suspend fun summarizeDocumentOrTopic(query: String) {
        val results = hybridSearchUseCase(query = query, limit = 5)
        if (results.isEmpty()) {
            addAssistantMessage("I searched your storage for **\"$query\"**, but didn't find any matching documents or files to summarize. Try checking the file name or re-indexing in Settings.")
            return
        }

        val targetResult = results.first()
        val targetFile = targetResult.file
        recentFilesHelper.recordOpened(targetFile.path)

        val assistantMsgId = java.util.UUID.randomUUID().toString()
        val initialMsg = ChatMessage(
            id = assistantMsgId,
            sender = MessageSender.ASSISTANT,
            text = "Analyzing content of **${targetFile.name}**...",
            searchResults = listOf(targetResult),
            isStreaming = true
        )
        _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + initialMsg)

        var content = ""
        try {
            if (targetFile.id != 0L) {
                val chunks = chunkDao.getChunksForFile(targetFile.id)
                if (chunks.isNotEmpty()) {
                    content = chunks.joinToString("\n\n") { it.text }
                }
            }
            if (content.isBlank()) {
                val file = File(targetFile.path)
                if (file.exists() && file.canRead()) {
                    val ext = extractorFactory.getExtractor(targetFile.extension)
                    val text = ext?.extractText(file)?.fullText ?: ""
                    content = if (text.length > 50) {
                        text
                    } else if (targetFile.category == FileCategory.DOCUMENT_PDF) {
                        ocrEngine.recognizePdf(file).fullText
                    } else {
                        text
                    }
                }
            }
        } catch (_: Exception) {}

        if (content.isBlank()) {
            content = targetResult.matchedSnippet ?: targetFile.name
        }

        var accumulated = ""
        ragEngine.streamDocumentSummary(targetFile, query, content).collect { chunk ->
            accumulated += chunk
            updateMessage(assistantMsgId, accumulated, isStreaming = true, results = listOf(targetResult))
        }
        updateMessage(assistantMsgId, accumulated, isStreaming = false, results = listOf(targetResult))
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
