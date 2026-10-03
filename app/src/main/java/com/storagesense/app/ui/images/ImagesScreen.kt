package com.storagesense.app.ui.images

import android.graphics.Bitmap
import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.ui.theme.SensePrimary
import com.storagesense.app.ui.theme.VaultBackground
import com.storagesense.app.ui.theme.VaultOnSurface
import com.storagesense.app.ui.theme.VaultOnSurfaceVariant
import java.io.File

@Composable
fun ImagesScreen(viewModel: ImagesViewModel) {
    val images by viewModel.images.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val isClassifying by viewModel.isClassifying.collectAsState()
    var previewingFile by remember { mutableStateOf<FileItem?>(null) }
    
    val categories = listOf("All", "People", "Pet", "Food", "Text", "Vehicle", "Nature", "Screenshot")

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

        // Content Area: Grid or Empty State
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
                    if (isClassifying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = SensePrimary,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Scanning & classifying $selectedCategory photos...",
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
            // Image Grid
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
                            .size(300) // Downscale to 300px max for thumbnails to prevent OOM
                            .build(),
                        contentDescription = fileItem.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(100.dp) // Force square aspect ratio in grid
                            .clickable {
                                previewingFile = fileItem
                            }
                    )
                }
            }
        }
    }

    previewingFile?.let { file ->
        com.storagesense.app.ui.viewer.UniversalFileViewer(
            file = file,
            onDismiss = { previewingFile = null }
        )
    }
}
