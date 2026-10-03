package com.storagesense.app.ui.images

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.ui.theme.SensePrimary
import com.storagesense.app.ui.theme.VaultBackground
import com.storagesense.app.ui.theme.VaultOnSurface
import com.storagesense.app.ui.theme.VaultOnSurfaceVariant
import com.storagesense.app.ui.theme.VaultOutline
import com.storagesense.app.ui.theme.VaultSurfaceContainer
import com.storagesense.app.ui.theme.VaultSurfaceContainerHigh
import java.io.File

@Composable
fun ImagesScreen(viewModel: ImagesViewModel) {
    val images by viewModel.images.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val isClassifying by viewModel.isClassifying.collectAsState()
    val peopleClusters by viewModel.peopleClusters.collectAsState()
    val selectedPersonId by viewModel.selectedPersonClusterId.collectAsState()
    val isFaceScanning by viewModel.isFaceScanning.collectAsState()
    val faceScanProgress by viewModel.faceScanProgress.collectAsState()

    var previewingFile by remember { mutableStateOf<FileItem?>(null) }
    var renameTargetCluster by remember { mutableStateOf<PersonCluster?>(null) }
    var renameInputText by remember { mutableStateOf("") }
    
    val categories = listOf("All", "People", "Pet", "Food", "Text", "Vehicle", "Nature", "Screenshot")

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.loadImages()
        viewModel.loadFaceClusters()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VaultBackground)
    ) {
        // Categories Row
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories) { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { viewModel.selectCategory(category) },
                    label = { Text(category) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = VaultBackground,
                        labelColor = VaultOnSurface,
                        selectedContainerColor = SensePrimary,
                        selectedLabelColor = VaultBackground
                    )
                )
            }
        }

        // Live Classification Banner if running in background
        if (isClassifying) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = SensePrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "AI classifying photos on-device...",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = SensePrimary
                )
            }
        }

        // Google Photos Style People Section
        if (selectedCategory == "People") {
            if (selectedPersonId != null) {
                // Individual Person Album Header
                val currentPerson = peopleClusters.firstOrNull { it.clusterId == selectedPersonId }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.selectPerson(null) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to People",
                            tint = VaultOnSurface
                        )
                    }

                    val avatarFile = currentPerson?.thumbnailPath?.let { File(it) }
                        ?: currentPerson?.coverImagePath?.let { File(it) }

                    if (avatarFile != null && avatarFile.exists()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(avatarFile)
                                .crossfade(true)
                                .size(160)
                                .build(),
                            contentDescription = currentPerson?.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, SensePrimary, CircleShape)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(VaultSurfaceContainerHigh),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Face, contentDescription = null, tint = SensePrimary)
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = currentPerson?.displayName ?: "Person",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = VaultOnSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = {
                                    renameTargetCluster = currentPerson
                                    renameInputText = currentPerson?.displayName ?: ""
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Rename",
                                    tint = SensePrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Text(
                            text = "${images.size} photos",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultOnSurfaceVariant
                        )
                    }
                }
            } else {
                // All People Carousel / Grid (Google Photos Style)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "People & Pets",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = VaultOnSurface
                        )

                        if (isFaceScanning) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    color = SensePrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = faceScanProgress,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = SensePrimary
                                )
                            }
                        } else {
                            TextButton(
                                onClick = { viewModel.startFaceScan() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = SensePrimary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (peopleClusters.isEmpty()) "Scan Faces" else "Rescan",
                                    fontSize = 12.sp,
                                    color = SensePrimary
                                )
                            }
                        }
                    }

                    if (peopleClusters.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(peopleClusters, key = { it.clusterId }) { person ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .width(76.dp)
                                        .clickable { viewModel.selectPerson(person.clusterId) }
                                ) {
                                    val thumb = person.thumbnailPath?.let { File(it) }
                                        ?: File(person.coverImagePath)

                                    if (thumb.exists()) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(LocalContext.current)
                                                .data(thumb)
                                                .crossfade(true)
                                                .size(160)
                                                .build(),
                                            contentDescription = person.displayName,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(68.dp)
                                                .clip(CircleShape)
                                                .border(2.dp, SensePrimary.copy(alpha = 0.7f), CircleShape)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(68.dp)
                                                .clip(CircleShape)
                                                .background(VaultSurfaceContainerHigh),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Face,
                                                contentDescription = null,
                                                tint = SensePrimary,
                                                modifier = Modifier.size(32.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = person.displayName,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = VaultOnSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Text(
                                        text = "${person.photoCount} photos",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = VaultOnSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else if (!isFaceScanning) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = VaultSurfaceContainer.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Face,
                                    contentDescription = null,
                                    tint = SensePrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Google Photos Style People Grouping",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = VaultOnSurface
                                    )
                                    Text(
                                        text = "Automatically group individuals in your photos on-device.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VaultOnSurfaceVariant
                                    )
                                }
                                Button(
                                    onClick = { viewModel.startFaceScan() },
                                    colors = ButtonDefaults.buttonColors(containerColor = SensePrimary),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text("Scan", fontSize = 12.sp, color = VaultBackground)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Content Area: Photo Grid or Empty State
        if (images.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isClassifying || isFaceScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = SensePrimary,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isFaceScanning) faceScanProgress else "Classifying $selectedCategory photos...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VaultOnSurface
                        )
                    } else {
                        Text(
                            text = if (selectedCategory == "All") "No photos found on device" else "No $selectedCategory photos found",
                            style = MaterialTheme.typography.titleMedium,
                            color = VaultOnSurface
                        )
                        Text(
                            text = "On-device AI categorizes your photos offline with zero cloud upload.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultOnSurfaceVariant
                        )
                    }
                }
            }
        } else {
            // Photos Grid
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(images, key = { it.id }) { fileItem ->
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(File(fileItem.path))
                            .crossfade(false)
                            .bitmapConfig(Bitmap.Config.RGB_565)
                            .size(300)
                            .build(),
                        contentDescription = fileItem.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(100.dp)
                            .clickable {
                                previewingFile = fileItem
                            }
                    )
                }
            }
        }
    }

    // Rename Person Dialog
    renameTargetCluster?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTargetCluster = null },
            title = { Text("Name this person") },
            text = {
                OutlinedTextField(
                    value = renameInputText,
                    onValueChange = { renameInputText = it },
                    label = { Text("Person's Name") },
                    placeholder = { Text("e.g. Alex, Mom, Dad") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renamePerson(target.clusterId, renameInputText)
                        renameTargetCluster = null
                    }
                ) {
                    Text("Save", color = SensePrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetCluster = null }) {
                    Text("Cancel", color = VaultOutline)
                }
            }
        )
    }

    // Full In-App Photo & Media Viewer
    previewingFile?.let { file ->
        com.storagesense.app.ui.viewer.UniversalFileViewer(
            file = file,
            onDismiss = { previewingFile = null }
        )
    }
}
