package com.storagesense.app.ui.faces

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.rememberAsyncImagePainter
import java.io.File
import com.storagesense.app.ai.face.FaceClusterEntity

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FacesScreen(
    viewModel: FacesViewModel = hiltViewModel(),
    onImageClick: (String) -> Unit
) {
    val clusters by viewModel.clusters.collectAsState()
    var selectedCluster by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedCluster == null) "People" else "Person $selectedCluster") },
                navigationIcon = {
                    if (selectedCluster != null) {
                        IconButton(onClick = { selectedCluster = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (clusters.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No faces grouped yet. Wait for indexer to complete.")
            }
        } else if (selectedCluster == null) {
            // Show all clusters (folders)
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                items(clusters.keys.toList()) { clusterId ->
                    val firstFace = clusters[clusterId]?.firstOrNull()
                    firstFace?.let { face ->
                        PersonFolderItem(face = face, clusterId = clusterId) {
                            selectedCluster = clusterId
                        }
                    }
                }
            }
        } else {
            // Show images inside a specific cluster
            val facesInCluster = clusters[selectedCluster] ?: emptyList()
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                items(facesInCluster) { face ->
                    Image(
                        painter = rememberAsyncImagePainter(File(face.imagePath)),
                        contentDescription = "Face Image",
                        modifier = Modifier
                            .padding(4.dp)
                            .aspectRatio(1f)
                            .clickable { onImageClick(face.imagePath) },
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

@Composable
fun PersonFolderItem(face: FaceClusterEntity, clusterId: Int, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(8.dp)
            .clickable(onClick = onClick)
    ) {
        Card(
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.size(100.dp)
        ) {
            Image(
                painter = rememberAsyncImagePainter(File(face.imagePath)),
                contentDescription = "Person $clusterId",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text("Person $clusterId", style = MaterialTheme.typography.bodyMedium)
    }
}
