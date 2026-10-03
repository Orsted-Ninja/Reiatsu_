package com.storagesense.app.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.storagesense.app.data.extractor.DocxExtractor
import com.storagesense.app.data.extractor.ExtractedPage
import com.storagesense.app.data.extractor.PptxExtractor
import com.storagesense.app.domain.model.FileCategory
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.ui.theme.VaultBackground
import com.storagesense.app.ui.theme.VaultOnPrimary
import com.storagesense.app.ui.theme.VaultOnSurface
import com.storagesense.app.ui.theme.VaultOnSurfaceVariant
import com.storagesense.app.ui.theme.VaultOutline
import com.storagesense.app.ui.theme.VaultOutlineVariant
import com.storagesense.app.ui.theme.VaultPrimary
import com.storagesense.app.ui.theme.VaultSurfaceContainerHigh
import com.storagesense.app.ui.theme.VaultSurfaceContainerLow
import com.storagesense.app.ui.util.FileActionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

@Composable
fun UniversalFileViewer(
    file: FileItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val recentFilesHelper = remember(context) { com.storagesense.app.ui.util.RecentFilesHelper(context) }

    LaunchedEffect(file.path) {
        try {
            recentFilesHelper.recordOpened(file.path)
        } catch (_: Exception) {}
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = VaultBackground
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(VaultBackground)
            ) {
                // Header Bar
                ViewerTopBar(
                    file = file,
                    onBack = onDismiss,
                    onShare = { shareFile(context, file) },
                    onOpenExternally = { FileActionHelper.openFile(context, file) }
                )

                // Content Viewport
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        // PDF Documents
                        file.category == FileCategory.DOCUMENT_PDF || file.extension.equals("pdf", ignoreCase = true) -> {
                            PdfViewerContent(file)
                        }

                        // Video Playback
                        file.category == FileCategory.VIDEO || isVideoExtension(file.extension) -> {
                            VideoPlayerContent(file)
                        }

                        // Audio Playback
                        file.category == FileCategory.AUDIO || isAudioExtension(file.extension) -> {
                            AudioPlayerContent(file)
                        }

                        // Images, Photos & Screenshots
                        file.category == FileCategory.IMAGE_PHOTO ||
                                file.category == FileCategory.IMAGE_SCREENSHOT ||
                                isImageExtension(file.extension) -> {
                            ImageViewerContent(file)
                        }

                        // Presentations (PPTX / PPT)
                        file.category == FileCategory.DOCUMENT_SLIDES || file.extension.equals("pptx", ignoreCase = true) -> {
                            PresentationViewerContent(file)
                        }

                        // Word Documents (DOCX)
                        file.category == FileCategory.DOCUMENT_WORD || file.extension.equals("docx", ignoreCase = true) -> {
                            DocumentViewerContent(file)
                        }

                        // Plain Text, Code, Logs, JSON, Scripts
                        isTextOrCodeExtension(file.extension) || file.category == FileCategory.DOCUMENT_TEXT -> {
                            CodeTextViewerContent(file)
                        }

                        // Fallback binary card
                        else -> {
                            GenericFallbackContent(
                                file = file,
                                onOpenExternally = { FileActionHelper.openFile(context, file) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerTopBar(
    file: FileItem,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onOpenExternally: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(VaultSurfaceContainerLow)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = VaultOnSurface
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                ),
                color = VaultOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(VaultPrimary.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = file.extension.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = VaultPrimary
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = file.formattedSize,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = VaultOnSurfaceVariant
                )
            }
        }

        IconButton(onClick = onShare) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Share",
                tint = VaultOnSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        IconButton(onClick = onOpenExternally) {
            Icon(
                imageVector = Icons.Default.OpenInNew,
                contentDescription = "Open Externally",
                tint = VaultPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 1. Native Android PDF Viewer using PdfRenderer with asynchronous page bitmaps
 */
@Composable
private fun PdfViewerContent(file: FileItem) {
    val realFile = remember(file.path) { File(file.path) }
    var pageCount by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val renderedPages = remember { mutableStateMapOf<Int, Bitmap>() }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val firstVisiblePage by remember {
        derivedStateOf { listState.firstVisibleItemIndex + 1 }
    }

    DisposableEffect(realFile.absolutePath) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null

        val renderJob = try {
            if (realFile.exists()) {
                pfd = ParcelFileDescriptor.open(realFile, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(pfd)
                pageCount = renderer.pageCount

                val activeRenderer = renderer
                // Launch rendering job; we keep a handle so we can cancel before disposing renderer
                coroutineScope.launch(Dispatchers.IO) {
                    val initialCount = activeRenderer.pageCount.coerceAtMost(10)
                    for (i in 0 until initialCount) {
                        if (!coroutineContext.isActive) break // respect cancellation
                        try {
                            synchronized(activeRenderer) {
                                val page = activeRenderer.openPage(i)
                                val w = page.width * 2
                                val h = page.height * 2
                                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                page.close()
                                renderedPages[i] = bitmap
                            }
                        } catch (_: Exception) {
                            // Individual page render failure — skip it
                        }
                    }
                }
            } else {
                loadError = "PDF file not found on disk"
                null
            }
        } catch (e: Exception) {
            loadError = "Failed to open PDF: ${e.localizedMessage}"
            null
        }

        onDispose {
            try {
                renderJob?.cancel() // Cancel rendering before closing renderer
                synchronized(renderer ?: Any()) {
                    renderer?.close()
                }
                pfd?.close()
                renderedPages.values.forEach { if (!it.isRecycled) it.recycle() }
                renderedPages.clear()
            } catch (_: Exception) {}
        }
    }

    if (loadError != null) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, tint = VaultPrimary, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = loadError ?: "", color = VaultOnSurfaceVariant, textAlign = TextAlign.Center)
        }
        return
    }

    if (pageCount == 0) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = VaultPrimary, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Rendering PDF pages...", color = VaultOnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    if (scale > 1f) {
                        offset += pan
                    } else {
                        offset = Offset.Zero
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1.2f) 1f else 2f
                        offset = Offset.Zero
                    }
                )
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                ),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(pageCount) { pageIndex ->
                val bitmap = renderedPages[pageIndex]
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Page ${pageIndex + 1}",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(400.dp)
                                .background(Color(0xFF202024)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = VaultPrimary, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }

        // Floating Page Number Indicator
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(VaultSurfaceContainerHigh.copy(alpha = 0.9f))
                .border(1.dp, VaultOutlineVariant, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                text = "Page $firstVisiblePage of $pageCount",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = VaultOnSurface
            )
        }
    }
}

/**
 * 2. Media3 ExoPlayer Video Player with hardware decoding & playback controls
 */
@Composable
private fun VideoPlayerContent(file: FileItem) {
    val context = LocalContext.current
    val realFile = remember(file.path) { File(file.path) }
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

    DisposableEffect(realFile.absolutePath) {
        val player = ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(realFile))
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
        exoPlayer = player

        onDispose {
            player.release()
            exoPlayer = null
        }
    }

    if (!realFile.exists()) {
        Text("Video file not found", color = VaultOnSurfaceVariant)
        return
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = exoPlayer
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { view ->
            view.player = exoPlayer
        },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * 3. Media3 Audio Player with track controls
 */
@Composable
private fun AudioPlayerContent(file: FileItem) {
    val context = LocalContext.current
    val realFile = remember(file.path) { File(file.path) }
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

    DisposableEffect(realFile.absolutePath) {
        val player = ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(realFile))
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
        exoPlayer = player

        onDispose {
            player.release()
            exoPlayer = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(VaultPrimary.copy(alpha = 0.15f))
                .border(2.dp, VaultPrimary.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.MusicNote,
                contentDescription = null,
                tint = VaultPrimary,
                modifier = Modifier.size(60.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = file.name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = VaultOnSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = file.formattedSize,
            style = MaterialTheme.typography.bodySmall,
            color = VaultOnSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = exoPlayer
                    useController = true
                    controllerShowTimeoutMs = 0
                    controllerHideOnTouch = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
        )
    }
}

/**
 * 4. High-Res Image Viewer with Pinch-to-Zoom and Pan
 */
@Composable
private fun ImageViewerContent(file: FileItem) {
    val realFile = remember(file.path) { File(file.path) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) {
                        offset += pan
                    } else {
                        offset = Offset.Zero
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1.2f) 1f else 2.5f
                        offset = Offset.Zero
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(if (file.path.startsWith("content://")) Uri.parse(file.path) else realFile)
                .crossfade(true)
                .size(1920) // Limit max dimension to 1920px to prevent Canvas 'too large' crash
                .build(),
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )

        if (scale > 1.1f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(VaultSurfaceContainerHigh.copy(alpha = 0.85f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${(scale * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultPrimary
                )
            }
        }
    }
}

/**
 * 5. Presentation Viewer for PPTX slide decks
 */
@Composable
private fun PresentationViewerContent(file: FileItem) {
    val realFile = remember(file.path) { File(file.path) }
    var slides by remember { mutableStateOf<List<ExtractedPage>>(emptyList()) }
    var currentSlideIndex by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(realFile.absolutePath) {
        withContext(Dispatchers.IO) {
            if (realFile.exists()) {
                val extractor = PptxExtractor()
                val result = extractor.extractText(realFile)
                slides = result.pages
            }
            isLoading = false
        }
    }

    if (isLoading) {
        CircularProgressIndicator(color = VaultPrimary)
        return
    }

    if (slides.isEmpty()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(Icons.Outlined.Description, contentDescription = null, tint = VaultPrimary, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text("No slide text could be extracted from this presentation.", color = VaultOnSurfaceVariant, textAlign = TextAlign.Center)
        }
        return
    }

    val currentSlide = slides.getOrNull(currentSlideIndex)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Slide Card Viewport
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = VaultSurfaceContainerLow),
            border = androidx.compose.foundation.BorderStroke(1.dp, VaultOutlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Slide ${currentSlideIndex + 1} of ${slides.size}",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = VaultPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = currentSlide?.text ?: "Empty slide",
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                    color = VaultOnSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Slide Navigation Carousel Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { if (currentSlideIndex > 0) currentSlideIndex-- },
                enabled = currentSlideIndex > 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = VaultSurfaceContainerHigh,
                    contentColor = VaultOnSurface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Previous")
            }

            Text(
                text = "${currentSlideIndex + 1} / ${slides.size}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = VaultOnSurfaceVariant
            )

            Button(
                onClick = { if (currentSlideIndex < slides.size - 1) currentSlideIndex++ },
                enabled = currentSlideIndex < slides.size - 1,
                colors = ButtonDefaults.buttonColors(
                    containerColor = VaultPrimary,
                    contentColor = VaultOnPrimary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Next")
                Spacer(modifier = Modifier.width(4.dp))
                Icon(Icons.Default.ChevronRight, contentDescription = "Next")
            }
        }
    }
}

/**
 * 6. Word Document (DOCX) Reading Mode
 */
@Composable
private fun DocumentViewerContent(file: FileItem) {
    val realFile = remember(file.path) { File(file.path) }
    var documentText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(realFile.absolutePath) {
        withContext(Dispatchers.IO) {
            if (realFile.exists()) {
                val extractor = DocxExtractor()
                val result = extractor.extractText(realFile)
                documentText = result.fullText
            }
            isLoading = false
        }
    }

    if (isLoading) {
        CircularProgressIndicator(color = VaultPrimary)
        return
    }

    if (documentText.isBlank()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(Icons.Outlined.Description, contentDescription = null, tint = VaultPrimary, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text("No text could be extracted from this document.", color = VaultOnSurfaceVariant, textAlign = TextAlign.Center)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = VaultSurfaceContainerLow),
            border = androidx.compose.foundation.BorderStroke(1.dp, VaultOutlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "DOCUMENT READING MODE",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = VaultPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = documentText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = 24.sp,
                        fontFamily = FontFamily.Serif
                    ),
                    color = VaultOnSurface
                )
            }
        }
    }
}

/**
 * 7. Plaintext / Source Code / Log / JSON Viewer with line numbers
 */
@Composable
private fun CodeTextViewerContent(file: FileItem) {
    val context = LocalContext.current
    val realFile = remember(file.path) { File(file.path) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(realFile.absolutePath) {
        withContext(Dispatchers.IO) {
            if (realFile.exists()) {
                val lineList = mutableListOf<String>()
                try {
                    BufferedReader(FileReader(realFile)).use { reader ->
                        var line: String? = reader.readLine()
                        var count = 0
                        while (line != null && count < 2500) {
                            lineList.add(line)
                            line = reader.readLine()
                            count++
                        }
                    }
                } catch (_: Exception) {}
                lines = lineList
            }
            isLoading = false
        }
    }

    if (isLoading) {
        CircularProgressIndicator(color = VaultPrimary)
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VaultSurfaceContainerLow)
    ) {
        // Code toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(VaultSurfaceContainerHigh)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${lines.size} lines",
                style = MaterialTheme.typography.labelSmall,
                color = VaultOnSurfaceVariant
            )

            IconButton(
                onClick = {
                    val full = lines.joinToString("\n")
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Text Content", full))
                    Toast.makeText(context, "Copied content to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy Code",
                    tint = VaultPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Code viewport with line numbers
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            itemsIndexed(lines) { idx, line ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp)
                ) {
                    Text(
                        text = "${idx + 1}".padStart(4, ' '),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        color = VaultOutline,
                        modifier = Modifier.width(40.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            ),
                            color = VaultOnSurface
                        )
                    }
                }
            }
        }
    }
}

/**
 * 8. Fallback card for unknown / binary archives
 */
@Composable
private fun GenericFallbackContent(
    file: FileItem,
    onOpenExternally: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val icon = when (file.category) {
            FileCategory.ARCHIVE -> Icons.Outlined.FolderZip
            else -> Icons.Outlined.Description
        }

        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(VaultSurfaceContainerHigh)
                .border(1.dp, VaultOutlineVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = VaultPrimary,
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = file.name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = VaultOnSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "${file.formattedSize} • ${file.category.name.replace('_', ' ')}",
            style = MaterialTheme.typography.bodySmall,
            color = VaultOnSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onOpenExternally,
            colors = ButtonDefaults.buttonColors(
                containerColor = VaultPrimary,
                contentColor = VaultOnPrimary
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Open with External App")
        }
    }
}

private fun isImageExtension(ext: String): Boolean {
    return ext.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg")
}

private fun isVideoExtension(ext: String): Boolean {
    return ext.lowercase() in setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "ts", "flv")
}

private fun isAudioExtension(ext: String): Boolean {
    return ext.lowercase() in setOf("mp3", "m4a", "wav", "aac", "flac", "ogg", "opus")
}

private fun isTextOrCodeExtension(ext: String): Boolean {
    return ext.lowercase() in setOf(
        "txt", "md", "json", "xml", "html", "css", "js", "ts", "kt", "java", "py",
        "c", "cpp", "h", "hpp", "rs", "go", "sh", "bat", "ps1", "sql", "csv", "tsv",
        "yaml", "yml", "properties", "gradle", "log", "ini", "conf"
    )
}

private fun shareFile(context: Context, file: FileItem) {
    try {
        val target = File(file.path)
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        } catch (_: Exception) {
            Uri.fromFile(target)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    } catch (_: Exception) {
        Toast.makeText(context, "Unable to share file", Toast.LENGTH_SHORT).show()
    }
}
