package com.storagesense.app.ui.chat

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.storagesense.app.domain.model.ActionProposal
import com.storagesense.app.domain.model.ActionType
import com.storagesense.app.domain.model.FileCategory
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.ui.theme.DangerRed
import com.storagesense.app.ui.theme.GoldBevel
import com.storagesense.app.ui.theme.StorageArchive
import com.storagesense.app.ui.theme.StorageDoc
import com.storagesense.app.ui.theme.StorageImage
import com.storagesense.app.ui.theme.StorageOther
import com.storagesense.app.ui.theme.StoragePdf
import com.storagesense.app.ui.theme.StorageVideo
import com.storagesense.app.ui.theme.VaultBackground
import com.storagesense.app.ui.theme.VaultOnPrimary
import com.storagesense.app.ui.theme.VaultOnSurface
import com.storagesense.app.ui.theme.VaultOnSurfaceVariant
import com.storagesense.app.ui.theme.VaultOutline
import com.storagesense.app.ui.theme.VaultOutlineVariant
import com.storagesense.app.ui.theme.VaultPrimary
import com.storagesense.app.ui.theme.VaultPrimaryContainer
import com.storagesense.app.ui.theme.VaultSurfaceContainer
import com.storagesense.app.ui.theme.VaultSurfaceContainerHigh
import com.storagesense.app.ui.theme.VaultSurfaceContainerLow
import java.io.File

/**
 * Obsidian & Gilt Luxury Action Approval Sheet (Expanded Collection Sheet).
 * Implements 2-column rich media cards, visual image previews, filter segmented pills,
 * and high-contrast gold confirmation triggers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionApprovalSheet(
    proposal: ActionProposal,
    onConfirm: (ActionProposal) -> Unit,
    onDismiss: () -> Unit
) {
    var previewTargetFile by remember { mutableStateOf<FileItem?>(null) }
    var selectedFilter by remember { mutableStateOf("All") }

    val filteredFiles = remember(proposal.targetFiles, selectedFilter) {
        when (selectedFilter) {
            "Images" -> proposal.targetFiles.filter { isImageFile(it) }
            "Documents" -> proposal.targetFiles.filter { it.category == FileCategory.DOCUMENT_PDF || it.category == FileCategory.DOCUMENT_WORD || it.category == FileCategory.DOCUMENT_TEXT }
            "Videos" -> proposal.targetFiles.filter { it.category == FileCategory.VIDEO }
            else -> proposal.targetFiles
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = VaultSurfaceContainerLow,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Subtle ambient gold glow at the top
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                VaultPrimaryContainer.copy(alpha = 0.12f),
                                Color.Transparent
                            ),
                            radius = 450f
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                // Header Meta Box
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(VaultSurfaceContainerHigh)
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
                                text = "VAULT NODE REVIEW",
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultPrimary
                            )
                        }
                    }

                    Text(
                        text = "Encrypted (AES-256)",
                        style = MaterialTheme.typography.labelMedium,
                        color = VaultOnSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Title & Subtitle
                Text(
                    text = if (proposal.actionType == ActionType.MOVE_TO_TRASH) "Confirm Staging Action" else "Permanent Deletion",
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Serif),
                    color = VaultOnSurface
                )
                Text(
                    text = "${proposal.targetFiles.size} items • ${proposal.formattedTotalSize} • Offline Vault",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultOnSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Safety Rule Notice Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = VaultSurfaceContainer),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, VaultOutlineVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = VaultPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (proposal.actionType == ActionType.MOVE_TO_TRASH)
                                "Reversible action: items staged to .storagesense/trash/ (recoverable for 30 days)."
                            else
                                "Permanent deletion: files will be purged from physical disk. Unrecoverable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultOnSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Filter & Utility Glass Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(VaultSurfaceContainer.copy(alpha = 0.7f))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("All", "Images", "Documents", "Videos").forEach { pill ->
                        val isSelected = selectedFilter == pill
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) VaultPrimary else Color.Transparent)
                                .clickable { selectedFilter = pill }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = pill,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
                                color = if (isSelected) VaultOnPrimary else VaultOnSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 2-Column Rich Media Grid for Review
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(filteredFiles) { file ->
                        val isImage = isImageFile(file)
                        val (badgeColor, badgeIcon) = getFileCategoryBadge(file)

                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = VaultSurfaceContainer.copy(alpha = 0.85f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1.15f)
                                .border(1.dp, VaultOutlineVariant.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                .clickable {
                                    if (isImage) previewTargetFile = file
                                }
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (isImage) {
                                    val context = LocalContext.current
                                    val imageModel = remember(file.path) {
                                        if (file.path.startsWith("content://")) {
                                            android.net.Uri.parse(file.path)
                                        } else {
                                            File(file.path)
                                        }
                                    }
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(imageModel)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = file.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(badgeColor.copy(alpha = 0.1f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = badgeIcon,
                                            contentDescription = null,
                                            tint = badgeColor,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }
                                }

                                // Dark Scrim Overlay
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    Color.Black.copy(alpha = 0.5f),
                                                    Color.Transparent,
                                                    Color.Black.copy(alpha = 0.85f)
                                                )
                                            )
                                        )
                                )

                                // Top Right Select Checkmark Indicator
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(VaultPrimary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = VaultOnPrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }

                                // Top Left Size Pill
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(6.dp)
                                        .clip(RoundedCornerShape(99.dp))
                                        .background(Color.Black.copy(alpha = 0.7f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = file.formattedSize,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VaultOnSurface
                                    )
                                }

                                // Bottom Details
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .fillMaxWidth()
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = file.extension.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VaultPrimary
                                    )
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        color = VaultOnSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom Dual Action Triggers
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Outlined Liquid Glass Cancel Button
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = VaultSurfaceContainer.copy(alpha = 0.5f),
                            contentColor = VaultOnSurface
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, VaultOutlineVariant)
                    ) {
                        Text("Cancel", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                    }

                    // Solid Champagne Gold Action Button
                    Button(
                        onClick = { onConfirm(proposal) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (proposal.actionType == ActionType.PERMANENT_DELETE) DangerRed else VaultPrimary,
                            contentColor = if (proposal.actionType == ActionType.PERMANENT_DELETE) Color.White else VaultOnPrimary
                        )
                    ) {
                        Text(
                            text = if (proposal.actionType == ActionType.MOVE_TO_TRASH) "Move to Trash" else "Confirm Delete",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // High-Resolution Image Preview Dialog
    previewTargetFile?.let { file ->
        ImagePreviewDialog(
            file = file,
            onDismiss = { previewTargetFile = null }
        )
    }
}

@Composable
fun ImagePreviewDialog(
    file: FileItem,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = VaultSurfaceContainerHigh),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, VaultOutlineVariant, RoundedCornerShape(20.dp))
                .padding(vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = VaultOnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${file.formattedSize} • ${file.extension.uppercase()} • Encrypted Node",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultPrimary
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close preview",
                            tint = VaultOnSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(VaultSurfaceContainer)
                        .border(1.dp, VaultOutlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val context = LocalContext.current
                    val imageModel = remember(file.path) {
                        if (file.path.startsWith("content://")) {
                            android.net.Uri.parse(file.path)
                        } else {
                            File(file.path)
                        }
                    }
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(imageModel)
                            .crossfade(true)
                            .build(),
                        contentDescription = file.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = file.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultOutline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VaultPrimary,
                        contentColor = VaultOnPrimary
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Close Preview", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

private fun isImageFile(file: FileItem): Boolean {
    return file.category == FileCategory.IMAGE_PHOTO ||
            file.category == FileCategory.IMAGE_SCREENSHOT ||
            file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
}

private fun getFileCategoryBadge(file: FileItem): Pair<Color, ImageVector> {
    return when (file.category) {
        FileCategory.DOCUMENT_PDF -> Pair(StoragePdf, Icons.Default.PictureAsPdf)
        FileCategory.DOCUMENT_WORD,
        FileCategory.DOCUMENT_SLIDES,
        FileCategory.DOCUMENT_TEXT -> Pair(StorageDoc, Icons.Default.Description)
        FileCategory.IMAGE_PHOTO,
        FileCategory.IMAGE_SCREENSHOT -> Pair(StorageImage, Icons.Default.Image)
        FileCategory.VIDEO -> Pair(StorageVideo, Icons.Default.Movie)
        FileCategory.AUDIO -> Pair(StorageOther, Icons.Default.Audiotrack)
        FileCategory.ARCHIVE,
        FileCategory.INSTALLER -> Pair(StorageArchive, Icons.Default.Folder)
        else -> Pair(StorageOther, Icons.Default.Description)
    }
}
