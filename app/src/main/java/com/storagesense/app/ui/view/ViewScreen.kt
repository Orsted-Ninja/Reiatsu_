package com.storagesense.app.ui.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storagesense.app.domain.model.FileCategory
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.ui.components.StorageSegment
import com.storagesense.app.ui.components.VaultArcGauge
import com.storagesense.app.ui.theme.AmbientGiltGlow
import com.storagesense.app.ui.theme.DangerRed
import com.storagesense.app.ui.theme.StorageApp
import com.storagesense.app.ui.theme.StorageArchive
import com.storagesense.app.ui.theme.StorageAudio
import com.storagesense.app.ui.theme.StorageDoc
import com.storagesense.app.ui.theme.StorageDownload
import com.storagesense.app.ui.theme.StorageOther
import com.storagesense.app.ui.theme.StoragePhoto
import com.storagesense.app.ui.theme.StorageVideo
import com.storagesense.app.ui.theme.VaultBackground
import com.storagesense.app.ui.theme.VaultOnSurface
import com.storagesense.app.ui.theme.VaultOnSurfaceVariant
import com.storagesense.app.ui.theme.VaultOutline
import com.storagesense.app.ui.theme.VaultOutlineVariant
import com.storagesense.app.ui.theme.VaultPrimary
import com.storagesense.app.ui.theme.VaultSecondary
import com.storagesense.app.ui.theme.VaultSurfaceContainer
import com.storagesense.app.ui.theme.VaultSurfaceContainerHigh
import com.storagesense.app.ui.theme.VaultSurfaceContainerLow
import com.storagesense.app.ui.viewer.UniversalFileViewer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Obsidian & Gilt Luxury Vault View Screen.
 * Features:
 * - Liquid-gold radial gauge & storage breakdown metrics
 * - Android OS Storage Breakdown Categories (Documents & PDFs, Images, Videos, Audio, APKs & Archives, Downloads)
 * - Active Vault Repositories (Duplicates, Large files, Recently added, Recently opened, Trash, WhatsApp, Telegram)
 * - Interactive Drill-Down Bottom Sheet to inspect, open, restore, or delete files
 * - 1-Tap In-App Universal File Viewer
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewScreen(
    viewModel: ViewViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var previewingFile by remember { mutableStateOf<FileItem?>(null) }
    var activeRepositoryFilter by remember { mutableStateOf<CollectionType?>(null) }

    LaunchedEffect(uiState.actionResultMessage) {
        uiState.actionResultMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissActionToast()
        }
    }

    val repositories = remember(uiState.collections) {
        uiState.collections.map { collection ->
            val icon = when (collection.type) {
                CollectionType.DUPLICATES -> Icons.Default.Refresh
                CollectionType.LARGE_FILES -> Icons.Default.Movie
                CollectionType.RECENTLY_ADDED -> Icons.Default.Image
                CollectionType.RECENTLY_OPENED -> Icons.Default.Description
                CollectionType.OLD_FILES -> Icons.Default.Archive
                CollectionType.RECENTLY_DELETED -> Icons.Default.Security
                CollectionType.WHATSAPP_MEDIA -> Icons.AutoMirrored.Filled.Send
                CollectionType.TELEGRAM_MEDIA -> Icons.AutoMirrored.Filled.Send
            }
            VaultRepoData(
                title = collection.title,
                subtitle = collection.subtitle,
                icon = icon,
                type = collection.type,
                formattedSize = collection.formattedSize,
                count = collection.count
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = VaultBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Ambient Radial Glow at top
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                AmbientGiltGlow,
                                Color.Transparent
                            ),
                            radius = 500f
                        )
                    )
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // 1. Vault Top Bar
                item(span = { GridItemSpan(2) }) {
                    VaultTopBar(
                        usedBytes = uiState.totalUsedBytes,
                        totalBytes = uiState.totalDeviceBytes,
                        onScanClick = { viewModel.loadData() }
                    )
                }

                // 2. Liquid Glass Curved Arc Radial Gauge Hero
                item(span = { GridItemSpan(2) }) {
                    VaultArcGauge(
                        usedBytes = uiState.totalUsedBytes,
                        totalBytes = uiState.totalDeviceBytes,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }

                // 3. Segmented Metric Glass Pill Bar
                item(span = { GridItemSpan(2) }) {
                    SegmentedMetricGlassPill(
                        segments = uiState.segments
                    )
                }

                // 4. SECTION: OS Storage Breakdown Categories
                item(span = { GridItemSpan(2) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Storage Categories",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 17.sp
                                ),
                                color = VaultOnSurface
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(VaultSurfaceContainerHigh)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${uiState.categories.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VaultPrimary
                                )
                            }
                        }

                        Text(
                            text = "OS BREAKDOWN",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = VaultOutline
                        )
                    }
                }

                // OS Category Cards
                items(uiState.categories) { cat ->
                    val (icon, badgeColor) = when (cat.type) {
                        ViewCategoryType.DOCUMENTS -> Pair(Icons.Default.PictureAsPdf, StorageDoc)
                        ViewCategoryType.PHOTOS -> Pair(Icons.Default.Image, StoragePhoto)
                        ViewCategoryType.VIDEOS -> Pair(Icons.Default.Movie, StorageVideo)
                        ViewCategoryType.AUDIO -> Pair(Icons.Default.Audiotrack, StorageAudio)
                        ViewCategoryType.APPS -> Pair(Icons.Default.FolderZip, StorageApp)
                        ViewCategoryType.DOWNLOADS -> Pair(Icons.Default.Download, StorageDownload)
                        ViewCategoryType.ARCHIVES -> Pair(Icons.Default.Archive, StorageArchive)
                        ViewCategoryType.SCREENSHOTS -> Pair(Icons.Default.Image, StoragePhoto)
                    }

                    OsCategoryCard(
                        title = cat.title,
                        count = cat.count,
                        formattedSize = cat.formattedSize,
                        icon = icon,
                        tintColor = badgeColor,
                        onClick = { viewModel.openCategoryDrillDown(cat.type) }
                    )
                }

                // 5. SECTION: Active Repositories
                item(span = { GridItemSpan(2) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Active Repositories",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 17.sp
                                ),
                                color = VaultOnSurface
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(VaultSurfaceContainerHigh)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${uiState.collections.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VaultPrimary
                                )
                            }
                        }

                        Text(
                            text = if (activeRepositoryFilter != null) "CLEAR FILTER" else "SMART FILTERS",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = VaultPrimary,
                            modifier = Modifier.clickable { activeRepositoryFilter = null }
                        )
                    }
                }

                // Active Vault Repositories (Cards)
                items(repositories) { repo ->
                    val isSelected = activeRepositoryFilter == repo.type
                    VaultRepositoryCard(
                        repo = repo,
                        isSelected = isSelected,
                        onClick = {
                            activeRepositoryFilter = if (isSelected) null else repo.type
                            viewModel.openCollectionDrillDown(repo.type)
                        }
                    )
                }

                // Bottom Padding Spacer
                item(span = { GridItemSpan(2) }) {
                    Spacer(modifier = Modifier.height(72.dp))
                }
            }
        }
    }

    // Interactive Drill-Down Bottom Sheet for Category or Collection Files
    if (uiState.activeDrillDownTitle != null) {
        val isTrash = uiState.activeDrillDownTitle?.contains("Trash", ignoreCase = true) == true ||
                uiState.activeDrillDownTitle?.contains("Deleted", ignoreCase = true) == true

        ModalBottomSheet(
            onDismissRequest = { viewModel.closeDrillDown() },
            containerColor = VaultBackground,
            dragHandle = { BottomSheetDefaults.DragHandle(color = VaultOutlineVariant) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .padding(horizontal = 16.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = uiState.activeDrillDownTitle ?: "",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            ),
                            color = VaultOnSurface
                        )
                        val totalBytes = uiState.activeDrillDownFiles.sumOf { it.sizeBytes }
                        Text(
                            text = "${uiState.activeDrillDownFiles.size} items • ${formatBytesHelper(totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultPrimary
                        )
                    }

                    if (isTrash && uiState.activeDrillDownFiles.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { viewModel.emptyTrash() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                            border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.6f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteForever,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = DangerRed
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Empty Trash", fontSize = 11.sp, color = DangerRed)
                        }
                    }
                }

                // Sort Filter Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = uiState.currentSortOption == SortOption.SIZE_DESC,
                        onClick = { viewModel.setSortOption(SortOption.SIZE_DESC) },
                        label = { Text("Size ↓", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VaultPrimary.copy(alpha = 0.2f),
                            selectedLabelColor = VaultPrimary,
                            containerColor = VaultSurfaceContainerLow,
                            labelColor = VaultOnSurfaceVariant
                        )
                    )
                    FilterChip(
                        selected = uiState.currentSortOption == SortOption.DATE_DESC,
                        onClick = { viewModel.setSortOption(SortOption.DATE_DESC) },
                        label = { Text("Date ↓", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VaultPrimary.copy(alpha = 0.2f),
                            selectedLabelColor = VaultPrimary,
                            containerColor = VaultSurfaceContainerLow,
                            labelColor = VaultOnSurfaceVariant
                        )
                    )
                    FilterChip(
                        selected = uiState.currentSortOption == SortOption.NAME_ASC,
                        onClick = { viewModel.setSortOption(SortOption.NAME_ASC) },
                        label = { Text("Name A-Z", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VaultPrimary.copy(alpha = 0.2f),
                            selectedLabelColor = VaultPrimary,
                            containerColor = VaultSurfaceContainerLow,
                            labelColor = VaultOnSurfaceVariant
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // File List or Empty State
                if (uiState.activeDrillDownFiles.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = VaultOutline.copy(alpha = 0.5f),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (isTrash) "Trash is empty" else "No files found in this category",
                                style = MaterialTheme.typography.bodyMedium,
                                color = VaultOutline
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 28.dp)
                    ) {
                        items(uiState.activeDrillDownFiles, key = { it.path }) { file ->
                            DrillDownFileCard(
                                file = file,
                                isTrash = isTrash,
                                onOpen = {
                                    previewingFile = file
                                    viewModel.recordFileOpened(file)
                                },
                                onDelete = { viewModel.deleteFile(file) },
                                onRestore = { viewModel.restoreTrashFile(file) },
                                onPermanentDelete = { viewModel.permanentlyDeleteTrashFile(file) }
                            )
                        }
                    }
                }
            }
        }
    }

    // In-App Universal File & Media Viewer
    previewingFile?.let { file ->
        UniversalFileViewer(
            file = file,
            onDismiss = { previewingFile = null }
        )
    }
}

/**
 * Clean OS Storage Category Card
 */
@Composable
fun OsCategoryCard(
    title: String,
    count: Int,
    formattedSize: String,
    icon: ImageVector,
    tintColor: Color,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = VaultSurfaceContainer.copy(alpha = 0.85f)),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, VaultOutlineVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(tintColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tintColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = VaultOutline.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = VaultOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$count files",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = VaultOutline
                )
                Text(
                    text = formattedSize,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = VaultPrimary
                )
            }
        }
    }
}

/**
 * Individual File Row in Drill Down View
 */
@Composable
fun DrillDownFileCard(
    file: FileItem,
    isTrash: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit
) {
    val (badgeIcon, badgeColor) = when (file.category) {
        FileCategory.DOCUMENT_PDF -> Pair(Icons.Default.PictureAsPdf, StorageDoc)
        FileCategory.DOCUMENT_WORD,
        FileCategory.DOCUMENT_SLIDES,
        FileCategory.DOCUMENT_TEXT -> Pair(Icons.Default.Description, StorageDoc)
        FileCategory.IMAGE_PHOTO,
        FileCategory.IMAGE_SCREENSHOT -> Pair(Icons.Default.Image, StoragePhoto)
        FileCategory.VIDEO -> Pair(Icons.Default.Movie, StorageVideo)
        FileCategory.AUDIO -> Pair(Icons.Default.Audiotrack, StorageAudio)
        FileCategory.ARCHIVE -> Pair(Icons.Default.FolderZip, StorageArchive)
        FileCategory.INSTALLER -> Pair(Icons.Default.Folder, StorageApp)
        else -> Pair(Icons.Default.Description, StorageOther)
    }

    val dateFormatted = remember(file.lastModifiedEpochMs) {
        val sdf = SimpleDateFormat("MMM d, yyyy", Locale.US)
        sdf.format(Date(file.lastModifiedEpochMs))
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = VaultSurfaceContainerLow.copy(alpha = 0.9f)),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, VaultOutlineVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(badgeColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = badgeIcon,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // File Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = VaultOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = file.path,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = VaultOutline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "${file.formattedSize} • $dateFormatted",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = VaultPrimary
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Actions
            if (isTrash) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onRestore,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = "Restore",
                            tint = VaultPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onPermanentDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = "Delete Permanently",
                            tint = DangerRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onOpen,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open",
                            tint = VaultPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = VaultOutline,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

data class VaultRepoData(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val type: CollectionType,
    val formattedSize: String,
    val count: Int
)

/**
 * Top Navigation Bar for Vault View
 */
@Composable
fun VaultTopBar(
    usedBytes: Long,
    totalBytes: Long,
    onScanClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "StorageSense",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = VaultOnSurface
            )
            Text(
                text = "VIEW REPOSITORY",
                style = MaterialTheme.typography.labelSmall,
                color = VaultPrimary
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            val usedGb = String.format(Locale.US, "%.0f", usedBytes / (1024.0 * 1024.0 * 1024.0))
            val totalGb = String.format(Locale.US, "%.0f", totalBytes / (1024.0 * 1024.0 * 1024.0))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(VaultSurfaceContainerHigh.copy(alpha = 0.8f))
                    .border(1.dp, VaultOutlineVariant.copy(alpha = 0.5f), RoundedCornerShape(99.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(VaultPrimary)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$usedGb / $totalGb GB",
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onScanClick,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(VaultSurfaceContainerHigh)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = VaultPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Segmented Metric Glass Pill Bar (Media, System, Archives)
 */
@Composable
fun SegmentedMetricGlassPill(
    segments: List<StorageSegment>
) {
    val mediaSize = segments.filter { it.name.contains("Photo", true) || it.name.contains("Image", true) || it.name.contains("Video", true) }
        .sumOf { it.sizeBytes }
    val docSize = segments.filter { it.name.contains("Doc", true) || it.name.contains("Archive", true) || it.name.contains("APK", true) }
        .sumOf { it.sizeBytes }
    val systemSize = segments.filter { it.name.contains("System", true) || it.name.contains("Other", true) || it.name.contains("App", true) }
        .sumOf { it.sizeBytes }

    val mediaFormatted = String.format(Locale.US, "%.1f GB", mediaSize / (1024.0 * 1024.0 * 1024.0))
    val docFormatted = String.format(Locale.US, "%.1f GB", docSize / (1024.0 * 1024.0 * 1024.0))
    val sysFormatted = String.format(Locale.US, "%.1f GB", systemSize / (1024.0 * 1024.0 * 1024.0))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(99.dp))
            .background(VaultSurfaceContainerLow.copy(alpha = 0.85f))
            .border(1.dp, VaultOutlineVariant.copy(alpha = 0.5f), RoundedCornerShape(99.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Segment 1: Media
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(VaultPrimary))
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(text = "Media", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = VaultOutline)
                    Text(text = mediaFormatted, style = MaterialTheme.typography.labelSmall, color = VaultOnSurface)
                }
            }

            Box(modifier = Modifier.width(1.dp).height(24.dp).background(VaultOutlineVariant))

            // Segment 2: System
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(VaultSecondary))
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(text = "System", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = VaultOutline)
                    Text(text = sysFormatted, style = MaterialTheme.typography.labelSmall, color = VaultOnSurface)
                }
            }

            Box(modifier = Modifier.width(1.dp).height(24.dp).background(VaultOutlineVariant))

            // Segment 3: Documents & Archives
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFF4C86C)))
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(text = "Docs", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = VaultOutline)
                    Text(text = docFormatted, style = MaterialTheme.typography.labelSmall, color = VaultOnSurface)
                }
            }
        }
    }
}

/**
 * 2-Column Obsidian Glass Vault Repository Card
 */
@Composable
fun VaultRepositoryCard(
    repo: VaultRepoData,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) VaultSurfaceContainerHigh else VaultSurfaceContainer.copy(alpha = 0.85f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isSelected) VaultPrimary else VaultOutlineVariant.copy(alpha = 0.5f),
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(VaultPrimary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = repo.icon,
                        contentDescription = null,
                        tint = VaultPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Text(
                    text = "${repo.count} items",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultOutline
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = repo.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = VaultOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = repo.formattedSize,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = VaultPrimary
            )
        }
    }
}
