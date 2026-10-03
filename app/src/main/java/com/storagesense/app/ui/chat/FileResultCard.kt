package com.storagesense.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.storagesense.app.ui.theme.DangerRed
import com.storagesense.app.ui.theme.StorageArchive
import com.storagesense.app.ui.theme.StorageDoc
import com.storagesense.app.ui.theme.StorageImage
import com.storagesense.app.ui.theme.StorageOther
import com.storagesense.app.ui.theme.StoragePdf
import com.storagesense.app.ui.theme.StorageVideo
import com.storagesense.app.ui.util.FileActionHelper
import java.io.File

@Composable
fun FileResultCard(
    result: SearchResult,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onOpen: ((FileItem) -> Unit)? = null,
    onDelete: ((FileItem) -> Unit)? = null,
    onSummarize: ((FileItem) -> Unit)? = null
) {
    val context = LocalContext.current
    val file = result.file
    val (badgeColor, badgeIcon) = when (file.category) {
        FileCategory.DOCUMENT_PDF -> Pair(StoragePdf, Icons.Default.PictureAsPdf)
        FileCategory.DOCUMENT_WORD,
        FileCategory.DOCUMENT_SLIDES,
        FileCategory.DOCUMENT_TEXT -> Pair(StorageDoc, Icons.Default.Description)
        FileCategory.IMAGE_PHOTO,
        FileCategory.IMAGE_SCREENSHOT -> Pair(StorageImage, Icons.Default.Image)
        FileCategory.VIDEO -> Pair(StorageVideo, Icons.Default.Movie)
        FileCategory.ARCHIVE -> Pair(StorageArchive, Icons.Default.Folder)
        FileCategory.INSTALLER -> Pair(Color(0xFFE67E22), Icons.Default.Warning)
        else -> Pair(StorageOther, Icons.Default.Description)
    }

    val handleOpen = {
        if (onOpen != null) {
            onOpen(file)
        } else {
            FileActionHelper.openFile(context, file)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        onClick = {
            onClick?.invoke() ?: handleOpen()
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isImage = file.category == FileCategory.IMAGE_PHOTO ||
                                  file.category == FileCategory.IMAGE_SCREENSHOT
                    val imageFile = remember(file.path) { File(file.path) }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(badgeColor.copy(alpha = 0.15f))
                            .border(1.dp, badgeColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isImage && imageFile.exists()) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(imageFile)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = file.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = badgeIcon,
                                contentDescription = file.extension,
                                tint = badgeColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${file.formattedSize} • ${file.extension.uppercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Confidence / Relevance chip
                AssistChip(
                    onClick = { handleOpen() },
                    label = {
                        Text(
                            text = "${result.similarityPercent}% match",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.height(28.dp)
                )
            }

            result.matchedSnippet?.let { snippet ->
                if (snippet.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = snippet.replace("<b>", "").replace("</b>", ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = file.path,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Action row: Open, Summarize, & Delete buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isDocument = file.category == FileCategory.DOCUMENT_PDF ||
                                 file.category == FileCategory.DOCUMENT_WORD ||
                                 file.category == FileCategory.DOCUMENT_SLIDES ||
                                 file.category == FileCategory.DOCUMENT_TEXT

                if (isDocument && onSummarize != null) {
                    OutlinedButton(
                        onClick = { onSummarize(file) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Summarize", modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Summarize", fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                OutlinedButton(
                    onClick = { handleOpen() },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open", modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open", fontSize = 12.sp)
                }

                if (onDelete != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { onDelete(file) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = DangerRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
