package com.storagesense.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storagesense.app.action.ActionEngine
import com.storagesense.app.ai.llm.IntentParser
import com.storagesense.app.domain.model.FileCategory
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.domain.model.SearchResult
import com.storagesense.app.domain.model.SearchSource
import com.storagesense.app.domain.model.StorageIntent
import com.storagesense.app.domain.repository.FileRepository
import com.storagesense.app.domain.usecase.DuplicateDetectionUseCase
import com.storagesense.app.domain.usecase.HybridSearchUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchFilter {
    ALL,
    PHOTOS,
    VIDEOS,
    DOCUMENTS,
    AUDIO,
    LARGE_FILES,
    DUPLICATES,
    RECENT,
    OLDER_THAN_YEAR
}

enum class SearchSort {
    RELEVANCE,
    SIZE,
    DATE,
    NAME
}

data class SearchUiState(
    val query: String = "",
    val activeFilter: SearchFilter = SearchFilter.ALL,
    val activeSort: SearchSort = SearchSort.RELEVANCE,
    val results: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val totalIndexedCount: Int = 0,
    val suggestedSearches: List<String> = listOf(
        "Find videos larger than 500 MB",
        "Show screenshots from last month",
        "Find duplicate photos",
        "Find PDFs containing invoice",
        "Files I haven't opened in 6 months"
    ),
    val actionResultMessage: String? = null
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val hybridSearchUseCase: HybridSearchUseCase,
    private val fileRepository: FileRepository,
    private val duplicateDetectionUseCase: DuplicateDetectionUseCase,
    private val intentParser: IntentParser,
    private val actionEngine: ActionEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            val count = fileRepository.getTotalIndexedCount()
            _uiState.value = _uiState.value.copy(totalIndexedCount = count)
            // Load initial recent items
            performSearch("", _uiState.value.activeFilter, _uiState.value.activeSort)
        }
    }

    fun onQueryChange(newQuery: String) {
        _uiState.value = _uiState.value.copy(query = newQuery)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250) // Debounce typing
            performSearch(newQuery, _uiState.value.activeFilter, _uiState.value.activeSort)
        }
    }

    fun onFilterSelect(filter: SearchFilter) {
        val newFilter = if (_uiState.value.activeFilter == filter) SearchFilter.ALL else filter
        _uiState.value = _uiState.value.copy(activeFilter = newFilter)
        performSearch(_uiState.value.query, newFilter, _uiState.value.activeSort)
    }

    fun onSortSelect(sort: SearchSort) {
        _uiState.value = _uiState.value.copy(activeSort = sort)
        val sorted = applySorting(_uiState.value.results, sort)
        _uiState.value = _uiState.value.copy(results = sorted)
    }

    fun onSuggestionClick(suggestion: String) {
        _uiState.value = _uiState.value.copy(query = suggestion)
        performSearch(suggestion, _uiState.value.activeFilter, _uiState.value.activeSort)
    }

    fun deleteFile(file: FileItem) {
        viewModelScope.launch {
            val proposal = actionEngine.proposeTrash(listOf(file), "Move ${file.name} to Trash")
            val result = actionEngine.executeAction(proposal)
            _uiState.value = _uiState.value.copy(
                actionResultMessage = "${result.message} (staged in trash)",
                results = _uiState.value.results.filter { it.file.id != file.id }
            )
        }
    }

    fun dismissActionToast() {
        _uiState.value = _uiState.value.copy(actionResultMessage = null)
    }

    private fun performSearch(query: String, filter: SearchFilter, sort: SearchSort) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true)
            try {
                val rawResults = when {
                    // Specific Filter Shortcut: Duplicates
                    filter == SearchFilter.DUPLICATES || query.contains("duplicate", ignoreCase = true) -> {
                        val dups = duplicateDetectionUseCase.findExactDuplicates()
                        dups.flatMap { group ->
                            group.deleteCandidates.map { file ->
                                SearchResult(
                                    file = file,
                                    matchedSnippet = "Duplicate copy (${file.formattedSize}) · Original kept in ${group.keepCandidate.path}",
                                    score = 1.0f,
                                    source = SearchSource.METADATA
                                )
                            }
                        }
                    }

                    // Size Filter Shortcut (> 500 MB)
                    filter == SearchFilter.LARGE_FILES || query.contains("500 mb", ignoreCase = true) -> {
                        val large = fileRepository.getFilesLargerThan(500L * 1024L * 1024L)
                        large.map {
                            SearchResult(
                                file = it,
                                matchedSnippet = "Large file (${it.formattedSize}) at ${it.path}",
                                score = 0.95f,
                                source = SearchSource.METADATA
                            )
                        }
                    }

                    // Query is blank -> Show recently modified files
                    query.isBlank() -> {
                        val all = fileRepository.getAllFiles()
                        all.sortedByDescending { it.lastModifiedEpochMs }.take(50).map {
                            SearchResult(
                                file = it,
                                matchedSnippet = "File in ${it.path}",
                                score = 0.5f,
                                source = SearchSource.METADATA
                            )
                        }
                    }

                    // Natural language intent query or keyword hybrid search
                    else -> {
                        val parsed = intentParser.parse(query)
                        if (parsed is StorageIntent.Search) {
                            hybridSearchUseCase(query = parsed.query, isImageSearch = parsed.isImageSearch)
                        } else if (parsed is StorageIntent.Filter) {
                            val items = when {
                                parsed.category != null -> fileRepository.getFilesByCategory(parsed.category.name)
                                parsed.minSizeBytes != null && parsed.minSizeBytes > 0 -> fileRepository.getFilesLargerThan(parsed.minSizeBytes)
                                else -> fileRepository.getAllFiles()
                            }
                            items.map {
                                SearchResult(
                                    file = it,
                                    matchedSnippet = "Matched query: ${parsed.label}",
                                    score = 0.9f,
                                    source = SearchSource.METADATA
                                )
                            }
                        } else {
                            hybridSearchUseCase(query = query)
                        }
                    }
                }

                // Apply category filter if active
                val filtered = if (filter == SearchFilter.ALL || filter == SearchFilter.DUPLICATES || filter == SearchFilter.LARGE_FILES) {
                    rawResults
                } else {
                    val now = System.currentTimeMillis()
                    rawResults.filter { res ->
                        when (filter) {
                            SearchFilter.PHOTOS -> res.file.category == FileCategory.IMAGE_PHOTO || res.file.category == FileCategory.IMAGE_SCREENSHOT
                            SearchFilter.VIDEOS -> res.file.category == FileCategory.VIDEO
                            SearchFilter.DOCUMENTS -> res.file.category == FileCategory.DOCUMENT_PDF ||
                                    res.file.category == FileCategory.DOCUMENT_WORD ||
                                    res.file.category == FileCategory.DOCUMENT_SLIDES ||
                                    res.file.category == FileCategory.DOCUMENT_TEXT
                            SearchFilter.AUDIO -> res.file.category == FileCategory.AUDIO
                            SearchFilter.RECENT -> (now - res.file.lastModifiedEpochMs) <= (7L * 24L * 60L * 60L * 1000L)
                            SearchFilter.OLDER_THAN_YEAR -> (now - res.file.lastModifiedEpochMs) >= (365L * 24L * 60L * 60L * 1000L)
                            else -> true
                        }
                    }
                }

                val sorted = applySorting(filtered, sort)
                _uiState.value = _uiState.value.copy(results = sorted)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(results = emptyList())
            } finally {
                _uiState.value = _uiState.value.copy(isSearching = false)
            }
        }
    }

    private fun applySorting(list: List<SearchResult>, sort: SearchSort): List<SearchResult> {
        return when (sort) {
            SearchSort.RELEVANCE -> list.sortedByDescending { it.score }
            SearchSort.SIZE -> list.sortedByDescending { it.file.sizeBytes }
            SearchSort.DATE -> list.sortedByDescending { it.file.lastModifiedEpochMs }
            SearchSort.NAME -> list.sortedBy { it.file.name.lowercase() }
        }
    }
}
