package com.storagesense.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storagesense.app.domain.model.FileCategory
import com.storagesense.app.domain.model.SearchResult
import com.storagesense.app.ui.theme.StorageArchive
import com.storagesense.app.ui.theme.StorageDoc
import com.storagesense.app.ui.theme.StorageImage
import com.storagesense.app.ui.theme.StorageOther
import com.storagesense.app.ui.theme.StoragePdf
import com.storagesense.app.ui.theme.StorageVideo

@Composable
fun FileResultCard(
    result: SearchResult,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val file = result.file
    val (badgeColor, badgeIcon) = when (file.category) {
        FileCategory.DOCUMENT_PDF -> Pair(StoragePdf, Icons.Default.PictureAsPdf)
        FileCategory.DOCUMENT_WORD,
        FileCategory.DOCUMENT_SLIDES,
        FileCategory.DOCUMENT_TEXT -> Pair(StorageDoc, Icons.Default.Description)
        FileCategory.IMAGE_PHOTO,
        FileCategory.IMAGE_SCREENSHOT -> Pair(StorageImage, Icons.Default.Image)
        FileCategory.VIDEO -> Pair(StorageVideo, Icons.Default.Movie)
        FileCategory.ARCHIVE,
        FileCategory.INSTALLER -> Pair(StorageArchive, Icons.Default.Folder)
        else -> Pair(StorageOther, Icons.Default.Description)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = com.storagesense.app.ui.theme.VaultSurfaceContainer.copy(alpha = 0.85f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, com.storagesense.app.ui.theme.VaultOutlineVariant.copy(alpha = 0.5f)),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    val isImage = file.category == FileCategory.IMAGE_PHOTO ||
                            file.category == FileCategory.IMAGE_SCREENSHOT ||
                            file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

                    Box(
                        modifier = Modifier
                            .size(if (isImage) 44.dp else 36.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .background(badgeColor.copy(alpha = 0.15f))
                            .border(
                                width = 1.dp,
                                color = if (isImage) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f) else Color.Transparent,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
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
                    onClick = {},
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
        }
    }
}
