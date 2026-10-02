package com.storagesense.app.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.storagesense.app.ui.components.FileDetailSheet
import com.storagesense.app.ui.components.StorageSegment
import com.storagesense.app.ui.components.VaultArcGauge
import com.storagesense.app.ui.theme.AmbientGiltGlow
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

/**
 * Obsidian & Gilt Luxury Vault View Screen (Analytics & Active Repositories).
 * Implements the curved liquid-gold radial gauge, segmented metric pill,
 * active vault repositories grid, and deep media gallery.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewScreen(
    viewModel: ViewViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedMediaForDetail by remember { mutableStateOf<MediaItem?>(null) }
    var activeRepositoryFilter by remember { mutableStateOf<CollectionType?>(null) }

    LaunchedEffect(uiState.actionResultMessage) {
        uiState.actionResultMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissActionToast()
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

                // 4. Section Title: Active Repositories
                item(span = { GridItemSpan(2) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 4.dp),
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
                                    text = "6",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VaultPrimary
                                )
                            }
                        }

                        Text(
                            text = if (activeRepositoryFilter != null) "CLEAR FILTER" else "SORT BY SIZE",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultPrimary,
                            modifier = Modifier.clickable { activeRepositoryFilter = null }
                        )
                    }
                }

                // 5. Active Vault Repositories (Cards)
                val repositories = listOf(
                    VaultRepoData("Cinematic Exports", "Large 4K & Raw Videos", Icons.Default.Movie, CollectionType.LARGE_FILES, "84.2 GB", 42),
                    VaultRepoData("Raw Photo Vault", "Uncompressed Camera RAW", Icons.Default.Image, CollectionType.RECENTLY_ADDED, "124.6 GB", 1280),
                    VaultRepoData("Encrypted Records", "Financials & Documents", Icons.Default.Description, CollectionType.OLD_FILES, "9.4 GB", 314),
                    VaultRepoData("Cold Archives", "ZIP / 7Z / TAR Containers", Icons.Default.Archive, CollectionType.RECENTLY_OPENED, "48.6 GB", 14),
                    VaultRepoData("Redundant Copies", "Cryptographic Duplicates", Icons.Default.Refresh, CollectionType.DUPLICATES, "32.4 GB", 340),
                    VaultRepoData("Transient Cache", "App Cache & Stale Temp", Icons.Default.Security, CollectionType.RECENTLY_DELETED, "2.4 GB", 86)
                )

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

                // 6. Section Title: Vault Asset Gallery
                item(span = { GridItemSpan(2) }) {
                    Text(
                        text = "Vault Asset Gallery",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp
                        ),
                        color = VaultOnSurface,
                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
                    )
                }

                // 7. Media Gallery Items
                items(uiState.mediaItems.take(20), key = { it.id }) { item ->
                    VaultMediaCell(
                        item = item,
                        onClick = { selectedMediaForDetail = item }
                    )
                }

                // Bottom Padding Spacer
                item(span = { GridItemSpan(2) }) {
                    Spacer(modifier = Modifier.height(72.dp))
                }
            }
        }
    }

    // Detail Inspection Bottom Sheet
    selectedMediaForDetail?.let { media ->
        val fileItem = media.toFileItem()
        FileDetailSheet(
            file = fileItem,
            onDismiss = { selectedMediaForDetail = null },
            onDeleteRequest = { _ ->
                viewModel.deleteMediaItem(media)
                selectedMediaForDetail = null
            }
        )
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
            val usedGb = String.format(java.util.Locale.US, "%.0f", usedBytes / (1024.0 * 1024.0 * 1024.0))
            val totalGb = String.format(java.util.Locale.US, "%.0f", totalBytes / (1024.0 * 1024.0 * 1024.0))

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
    val mediaSize = segments.filter { it.name.contains("Photo", true) || it.name.contains("Video", true) }
        .sumOf { it.sizeBytes }
    val docSize = segments.filter { it.name.contains("Doc", true) || it.name.contains("Archive", true) }
        .sumOf { it.sizeBytes }
    val systemSize = segments.filter { it.name.contains("App", true) || it.name.contains("Other", true) }
        .sumOf { it.sizeBytes }

    val mediaFormatted = String.format(java.util.Locale.US, "%.1f GB", mediaSize / (1024.0 * 1024.0 * 1024.0))
    val docFormatted = String.format(java.util.Locale.US, "%.1f GB", docSize / (1024.0 * 1024.0 * 1024.0))
    val sysFormatted = String.format(java.util.Locale.US, "%.1f GB", systemSize / (1024.0 * 1024.0 * 1024.0))

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

            // Segment 3: Archives
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFF4C86C)))
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(text = "Archives", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = VaultOutline)
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

/**
 * Rich Media Cell in Gallery Grid
 */
@Composable
fun VaultMediaCell(
    item: MediaItem,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = VaultSurfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.1f)
            .border(1.dp, VaultOutlineVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.contentUri)
                    .crossfade(true)
                    .build(),
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Bottom Gradient Scrim
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                        )
                    )
            )

            // Video indicator if video
            if (item.isVideo) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Video",
                        tint = VaultPrimary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            // Size pill in bottom right
            Text(
                text = item.formattedSize,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = VaultOnSurface,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
            )
        }
    }
}
