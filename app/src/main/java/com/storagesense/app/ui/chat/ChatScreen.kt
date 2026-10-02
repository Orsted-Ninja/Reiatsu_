package com.storagesense.app.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.storagesense.app.ui.settings.SettingsScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.storagesense.app.domain.model.ActionProposal
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.ui.components.FileDetailSheet
import com.storagesense.app.ui.components.PrivacySheet
import com.storagesense.app.ui.components.VaultEmblem
import com.storagesense.app.ui.theme.AmbientGiltGlow
import com.storagesense.app.ui.theme.VaultBackground
import com.storagesense.app.ui.theme.VaultOnPrimary
import com.storagesense.app.ui.theme.VaultOnSurface
import com.storagesense.app.ui.theme.VaultOnSurfaceVariant
import com.storagesense.app.ui.theme.VaultOutline
import com.storagesense.app.ui.theme.VaultOutlineVariant
import com.storagesense.app.ui.theme.VaultPrimary
import com.storagesense.app.ui.theme.VaultPrimaryContainer
import com.storagesense.app.ui.theme.VaultSecondary
import com.storagesense.app.ui.theme.VaultSurfaceContainer
import com.storagesense.app.ui.theme.VaultSurfaceContainerHigh
import com.storagesense.app.ui.theme.VaultSurfaceContainerLow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    var showPrivacySheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var selectedFileForDetail by remember { mutableStateOf<FileItem?>(null) }
    val context = LocalContext.current
    var isListening by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Microphone permission required for speech recognition", Toast.LENGTH_SHORT).show()
        }
    }

    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else null
    }

    DisposableEffect(speechRecognizer) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
            }
            override fun onError(error: Int) {
                isListening = false
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val spoken = matches[0].trim()
                    if (spoken.isNotEmpty()) {
                        inputText = if (inputText.isBlank()) spoken else "$inputText $spoken"
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val partial = matches[0].trim()
                    if (partial.isNotEmpty()) {
                        inputText = partial
                    }
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        speechRecognizer?.setRecognitionListener(listener)

        onDispose {
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
        }
    }

    fun startListening() {
        val hasAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasAudio) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (speechRecognizer == null) {
            Toast.makeText(context, "Speech recognition not available on device", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            isListening = false
        }
    }

    fun stopListening() {
        if (isListening) {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
            isListening = false
        }
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    val hasUserMessages = uiState.messages.any { it.sender == MessageSender.USER }

    val recommendedHeuristics = remember {
        listOf(
            Pair("Cache Cleanup • 2.4 GB", Icons.Default.CleaningServices),
            Pair("Duplicate Clusters", Icons.Default.Refresh),
            Pair("Large Media > 1 GB", Icons.Default.VideoLibrary),
            Pair("Inspect Archives", Icons.Default.FolderZip),
            Pair("What is taking up space?", Icons.Default.AutoAwesome)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VaultBackground)
    ) {
        // Ambient Gilt Radiance at the top of the canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AmbientGiltGlow,
                            Color.Transparent
                        ),
                        radius = 600f
                    )
                )
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                // Fixed Header Bar (Minimal, without top branding, with ? info icon)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(VaultBackground.copy(alpha = 0.85f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.onUndo() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo Last Action",
                            tint = VaultOnSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Engine Settings",
                            tint = VaultOnSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(VaultPrimary)
                            .clickable { showPrivacySheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.QuestionMark,
                            contentDescription = "Help & Information",
                            tint = VaultOnPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {

                // Main Viewport
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (!hasUserMessages) {
                        // Ambient Vault Emblem & Hero Header
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            VaultEmblem(size = 80.dp)

                            Spacer(modifier = Modifier.height(20.dp))

                            Text(
                                text = "What would you like to know?",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontFamily = FontFamily.Serif,
                                    fontStyle = FontStyle.Italic,
                                    fontSize = 24.sp
                                ),
                                color = VaultPrimary,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(VaultSurfaceContainerLow)
                                    .border(1.dp, VaultOutlineVariant, RoundedCornerShape(99.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(VaultPrimary)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "ON-DEVICE NEURAL ENGINE ACTIVE • ENCRYPTED VAULT",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VaultOutline
                                )
                            }

                            Spacer(modifier = Modifier.height(32.dp))

                            // Recommended Heuristics Row
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = VaultPrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "RECOMMENDED HEURISTICS",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VaultOutline
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(horizontal = 2.dp)
                                ) {
                                    items(recommendedHeuristics) { (title, icon) ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(VaultSurfaceContainerHigh.copy(alpha = 0.9f))
                                                .border(1.dp, VaultOutlineVariant.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                                                .clickable { viewModel.onSendMessage(title) }
                                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                tint = VaultPrimary,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = title,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = VaultOnSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Conversational Chat Stream
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp)
                        ) {
                            items(uiState.messages) { message ->
                                VaultChatMessageItem(
                                    message = message,
                                    onFileClick = { file -> selectedFileForDetail = file },
                                    onActionProposalClick = { proposal -> viewModel.onConfirmAction(proposal) },
                                    onDeleteFile = { file -> viewModel.requestDeleteFile(file) }
                                )
                            }

                            if (uiState.isProcessing) {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = VaultPrimary
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "Querying on-device cog-core...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = VaultOnSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Recommended Heuristics Quick Pills when in active chat
                if (hasUserMessages) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(recommendedHeuristics) { (title, icon) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(VaultSurfaceContainerHigh.copy(alpha = 0.85f))
                                    .border(1.dp, VaultOutlineVariant.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                    .clickable { viewModel.onSendMessage(title) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = VaultPrimary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VaultOnSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Recessed Obsidian Floating Input Well
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(VaultSurfaceContainerLow)
                            .border(1.dp, VaultOutlineVariant, RoundedCornerShape(24.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Tap and Hold Mic Button
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (isListening) VaultPrimary.copy(alpha = 0.25f) else Color.Transparent)
                                .pointerInput(speechRecognizer) {
                                    detectTapGestures(
                                        onPress = {
                                            startListening()
                                            try {
                                                awaitRelease()
                                            } finally {
                                                stopListening()
                                            }
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Hold to Speak",
                                tint = if (isListening) VaultPrimary else VaultPrimary.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Text Input
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = VaultOnSurface),
                            cursorBrush = SolidColor(VaultPrimary),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                if (inputText.isBlank()) {
                                    Text(
                                        text = if (isListening) "Listening... speak now" else "Consult the vault assistant...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isListening) VaultPrimary else VaultOutline
                                    )
                                }
                                innerTextField()
                            }
                        )

                        // Circular Solid Gold Send Button
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (inputText.isNotBlank()) VaultPrimary else VaultPrimary.copy(alpha = 0.35f))
                                .clickable(enabled = inputText.isNotBlank()) {
                                    val text = inputText
                                    inputText = ""
                                    viewModel.onSendMessage(text)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = VaultOnPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Action Approval Sheet for Destructive Operations
    uiState.pendingApproval?.let { proposal ->
        ActionApprovalSheet(
            proposal = proposal,
            onConfirm = { viewModel.onConfirmAction(proposal) },
            onDismiss = { viewModel.onDismissApproval() }
        )
    }

    // Privacy Details Sheet
    if (showPrivacySheet) {
        PrivacySheet(onDismiss = { showPrivacySheet = false })
    }

    // Engine & Settings Sheet
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            containerColor = VaultBackground
        ) {
            SettingsScreen()
        }
    }

    // File Detail Inspection Bottom Sheet
    selectedFileForDetail?.let { file ->
        FileDetailSheet(
            file = file,
            onDismiss = { selectedFileForDetail = null },
            onDeleteRequest = { f -> viewModel.deleteFileDirectly(f) }
        )
    }
}

/**
 * Obsidian & Gilt Chat Bubble Item
 */
@Composable
fun VaultChatMessageItem(
    message: ChatMessage,
    onFileClick: (FileItem) -> Unit,
    onActionProposalClick: (ActionProposal) -> Unit,
    onDeleteFile: ((FileItem) -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isUser = message.sender == MessageSender.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            // User Query Bubble
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth(0.85f)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(VaultSurfaceContainerHigh)
                        .border(1.dp, VaultOutlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultOnSurface,
                        lineHeight = 20.sp
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "VERIFIED BIOMETRIC CONTEXT",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = VaultOutline.copy(alpha = 0.65f),
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
        } else {
            // Assistant Bubble with Vertical Gold Gradient Accent Bar
            Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth(0.92f)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(VaultSurfaceContainerLow.copy(alpha = 0.85f))
                        .border(1.dp, VaultOutlineVariant.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row {
                        // Left vertical gold accent hairline
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(28.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            VaultPrimary,
                                            VaultPrimaryContainer,
                                            Color.Transparent
                                        )
                                    )
                                )
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        Column {
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = VaultOnSurface,
                                lineHeight = 21.sp
                            )

                            // Interactive Proposal Chip if pending action
                            message.pendingAction?.let { proposal ->
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(99.dp))
                                        .background(VaultPrimary.copy(alpha = 0.15f))
                                        .border(1.dp, VaultPrimary.copy(alpha = 0.4f), RoundedCornerShape(99.dp))
                                        .clickable { onActionProposalClick(proposal) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(VaultPrimary)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "REVIEW • ${proposal.formattedTotalSize}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VaultPrimary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = VaultPrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }

                            // Attached Search Results
                            if (message.searchResults.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Matching files (${message.searchResults.size}):",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VaultPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                message.searchResults.forEach { res ->
                                    FileResultCard(
                                        result = res,
                                        onClick = { onFileClick(res.file) },
                                        onOpen = { com.storagesense.app.ui.util.FileActionHelper.openFile(context, it) },
                                        onDelete = onDeleteFile
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "STORAGE SENSE • LOCAL COG-CORE",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = VaultOutline.copy(alpha = 0.65f),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}
