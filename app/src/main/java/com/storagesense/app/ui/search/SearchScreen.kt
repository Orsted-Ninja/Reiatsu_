package com.storagesense.app.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.storagesense.app.domain.model.FileCategory
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.domain.model.SearchResult
import com.storagesense.app.domain.model.SearchSource
import com.storagesense.app.ui.components.EmptyStateView
import com.storagesense.app.ui.components.FileDetailSheet
import com.storagesense.app.ui.components.VoiceQueryDialog
import com.storagesense.app.ui.theme.SensePrimary
import com.storagesense.app.ui.theme.StorageArchive
import com.storagesense.app.ui.theme.StorageAudio
import com.storagesense.app.ui.theme.StorageDoc
import com.storagesense.app.ui.theme.StorageOther
import com.storagesense.app.ui.theme.StoragePdf
import com.storagesense.app.ui.theme.StoragePhoto
import com.storagesense.app.ui.theme.StorageVideo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedFileForDetail by remember { mutableStateOf<FileItem?>(null) }
    var showVoiceDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.actionResultMessage) {
        uiState.actionResultMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissActionToast()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = com.storagesense.app.ui.theme.VaultBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Vault Search",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = com.storagesense.app.ui.theme.VaultOnSurface
                        )
                        Text(
                            text = "NEURAL INDEX • ZERO NETWORK",
                            style = MaterialTheme.typography.labelSmall,
                            color = com.storagesense.app.ui.theme.VaultPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = com.storagesense.app.ui.theme.VaultBackground
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Large Search Field
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = { viewModel.onQueryChange(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp)),
                    placeholder = {
                        Text(
                            text = "Search files, content, notes, duplicates...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = com.storagesense.app.ui.theme.VaultPrimary,
                        unfocusedBorderColor = com.storagesense.app.ui.theme.VaultOutlineVariant,
                        focusedContainerColor = com.storagesense.app.ui.theme.VaultSurfaceContainerLow,
                        unfocusedContainerColor = com.storagesense.app.ui.theme.VaultSurfaceContainerLow,
                        focusedTextColor = com.storagesense.app.ui.theme.VaultOnSurface,
                        unfocusedTextColor = com.storagesense.app.ui.theme.VaultOnSurface
                    ),
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = SensePrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (uiState.query.isNotEmpty()) {
                                IconButton(
                                    onClick = { viewModel.onQueryChange("") },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { showVoiceDialog = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Voice Search",
                                    tint = SensePrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                    }
                )
            }

            // Suggested Searches Row (Natural Language Prompts)
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.suggestedSearches) { suggestion ->
                    AssistChip(
                        onClick = { viewModel.onSuggestionClick(suggestion) },
                        label = {
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            // Filter Chips Bar
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = uiState.activeFilter == SearchFilter.ALL,
                        onClick = { viewModel.onFilterSelect(SearchFilter.ALL) },
                        label = { Text("All") }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.activeFilter == SearchFilter.PHOTOS,
                        onClick = { viewModel.onFilterSelect(SearchFilter.PHOTOS) },
                        label = { Text("Photos") }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.activeFilter == SearchFilter.VIDEOS,
                        onClick = { viewModel.onFilterSelect(SearchFilter.VIDEOS) },
                        label = { Text("Videos") }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.activeFilter == SearchFilter.DOCUMENTS,
                        onClick = { viewModel.onFilterSelect(SearchFilter.DOCUMENTS) },
                        label = { Text("Documents") }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.activeFilter == SearchFilter.AUDIO,
                        onClick = { viewModel.onFilterSelect(SearchFilter.AUDIO) },
                        label = { Text("Audio") }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.activeFilter == SearchFilter.LARGE_FILES,
                        onClick = { viewModel.onFilterSelect(SearchFilter.LARGE_FILES) },
                        label = { Text("Large files") }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.activeFilter == SearchFilter.DUPLICATES,
                        onClick = { viewModel.onFilterSelect(SearchFilter.DUPLICATES) },
                        label = { Text("Duplicates") }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.activeFilter == SearchFilter.RECENT,
                        onClick = { viewModel.onFilterSelect(SearchFilter.RECENT) },
                        label = { Text("Recent") }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.activeFilter == SearchFilter.OLDER_THAN_YEAR,
                        onClick = { viewModel.onFilterSelect(SearchFilter.OLDER_THAN_YEAR) },
                        label = { Text("Older than 1 yr") }
                    )
                }
            }

            // Results Count & Sorting Options Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${uiState.results.size} results",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sort:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SortPill(
                        label = "Relevance",
                        selected = uiState.activeSort == SearchSort.RELEVANCE,
                        onClick = { viewModel.onSortSelect(SearchSort.RELEVANCE) }
                    )
                    SortPill(
                        label = "Size",
                        selected = uiState.activeSort == SearchSort.SIZE,
                        onClick = { viewModel.onSortSelect(SearchSort.SIZE) }
                    )
                    SortPill(
                        label = "Date",
                        selected = uiState.activeSort == SearchSort.DATE,
                        onClick = { viewModel.onSortSelect(SearchSort.DATE) }
                    )
                    SortPill(
                        label = "Name",
                        selected = uiState.activeSort == SearchSort.NAME,
                        onClick = { viewModel.onSortSelect(SearchSort.NAME) }
                    )
                }
            }

            // Search Loading State
            if (uiState.isSearching) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = SensePrimary)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = "Searching local index...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Results List or Contextual Empty State
            if (uiState.results.isEmpty() && !uiState.isSearching) {
                when {
                    uiState.activeFilter == SearchFilter.DUPLICATES || uiState.query.contains("duplicate", ignoreCase = true) -> {
                        EmptyStateView(
                            title = "No duplicates found",
                            subtitle = "Your photos and files look unique.",
                            icon = Icons.Outlined.ContentCopy,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    uiState.activeFilter == SearchFilter.LARGE_FILES || uiState.query.contains("500 mb", ignoreCase = true) -> {
                        EmptyStateView(
                            title = "Nothing large enough",
                            subtitle = "No files above 500 MB were found.",
                            icon = Icons.Outlined.Storage,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    uiState.activeFilter == SearchFilter.RECENT -> {
                        EmptyStateView(
                            title = "No recent activity",
                            subtitle = "We'll show recently accessed files here.",
                            icon = Icons.Outlined.Schedule,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    else -> {
                        EmptyStateView(
                            title = "No matching files",
                            subtitle = "Try searching by keyword, file extension, or asking a natural language question.",
                            icon = Icons.Default.Search,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(uiState.results) { res ->
                        SearchResultItemCard(
                            result = res,
                            onClick = { selectedFileForDetail = res.file }
                        )
                    }
                }
            }
        }
    }

    // Voice Query Listening Modal
    if (showVoiceDialog) {
        VoiceQueryDialog(
            onDismiss = { showVoiceDialog = false },
            onSpeechResult = { text ->
                viewModel.onQueryChange(text)
            }
        )
    }

    // File Detail Inspection Bottom Sheet
    selectedFileForDetail?.let { file ->
        FileDetailSheet(
            file = file,
            onDismiss = { selectedFileForDetail = null },
            onDeleteRequest = { f ->
                viewModel.deleteFile(f)
            }
        )
    }
}

@Composable
fun SortPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) SensePrimary else MaterialTheme.colorScheme.onSurfaceVariant
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) SensePrimary.copy(alpha = 0.1f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
fun SearchResultItemCard(
    result: SearchResult,
    onClick: () -> Unit
) {
    val file = result.file
    val isImage = file.category == FileCategory.IMAGE_PHOTO ||
            file.category == FileCategory.IMAGE_SCREENSHOT ||
            file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val dateStr = remember(file.lastModifiedEpochMs) {
        dateFormat.format(Date(file.lastModifiedEpochMs))
    }

    val (badgeColor, badgeIcon) = when (file.category) {
        FileCategory.DOCUMENT_PDF -> StoragePdf to Icons.Outlined.PictureAsPdf
        FileCategory.DOCUMENT_WORD,
        FileCategory.DOCUMENT_SLIDES,
        FileCategory.DOCUMENT_TEXT -> StorageDoc to Icons.Outlined.Description
        FileCategory.IMAGE_PHOTO,
        FileCategory.IMAGE_SCREENSHOT -> StoragePhoto to Icons.Outlined.Image
        FileCategory.VIDEO -> StorageVideo to Icons.Outlined.Movie
        FileCategory.AUDIO -> StorageAudio to Icons.Outlined.Audiotrack
        FileCategory.ARCHIVE,
        FileCategory.INSTALLER -> StorageArchive to Icons.Outlined.FolderZip
        else -> StorageOther to Icons.Outlined.Description
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail or File Type Badge
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(badgeColor.copy(alpha = 0.12f))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isImage) {
                    val context = LocalContext.current
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(if (file.path.startsWith("content://")) android.net.Uri.parse(file.path) else File(file.path))
                            .crossfade(true)
                            .build(),
                        contentDescription = file.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = badgeIcon,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // File Information & Matched Snippet
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = file.formattedSize,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${file.extension.uppercase()} • $dateStr • ${file.path.replace('\\', '/')}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val snippet = result.matchedSnippet
                if (!snippet.isNullOrBlank() && snippet != "File in ${file.path}") {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = snippet,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = SensePrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
