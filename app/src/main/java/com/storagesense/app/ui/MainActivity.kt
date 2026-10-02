package com.storagesense.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storagesense.app.ui.chat.ChatScreen
import com.storagesense.app.ui.chat.ChatViewModel
import com.storagesense.app.ui.navigation.Screen
import com.storagesense.app.ui.search.SearchScreen
import com.storagesense.app.ui.search.SearchViewModel
import com.storagesense.app.ui.theme.SensePrimary
import com.storagesense.app.ui.theme.StorageSenseTheme
import com.storagesense.app.ui.view.ViewScreen
import com.storagesense.app.ui.view.ViewViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()
    private val viewViewModel: ViewViewModel by viewModels()
    private val searchViewModel: SearchViewModel by viewModels()

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewViewModel.loadData()
        chatViewModel.triggerScan()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkStoragePermission()
        requestMediaPermissions()

        setContent {
            StorageSenseTheme {
                MainAppContent(
                    chatViewModel = chatViewModel,
                    viewViewModel = viewViewModel,
                    searchViewModel = searchViewModel
                )
            }
        }
    }

    private fun requestMediaPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mediaPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO
                )
            )
        } else {
            mediaPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            chatViewModel.triggerScan()
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }
    }
}

@Composable
fun MainAppContent(
    chatViewModel: ChatViewModel,
    viewViewModel: ViewViewModel,
    searchViewModel: SearchViewModel
) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Chat) }

    val screens = listOf(
        Screen.Chat,
        Screen.View,
        Screen.Search
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = com.storagesense.app.ui.theme.VaultBackground,
                shadowElevation = 12.dp,
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = com.storagesense.app.ui.theme.VaultOutlineVariant.copy(alpha = 0.45f)
                )
            ) {
                NavigationBar(
                    containerColor = com.storagesense.app.ui.theme.VaultBackground,
                    contentColor = com.storagesense.app.ui.theme.VaultOnSurface,
                    tonalElevation = 0.dp,
                    modifier = Modifier.height(68.dp)
                ) {
                    screens.forEach { screen ->
                        val isSelected = currentScreen.route == screen.route
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                if (currentScreen.route != screen.route) {
                                    currentScreen = screen
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (isSelected) screen.selectedIcon else screen.icon,
                                    contentDescription = screen.title
                                )
                            },
                            label = {
                                Text(
                                    text = screen.title.uppercase(),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 10.sp
                                    )
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = com.storagesense.app.ui.theme.VaultPrimary,
                                unselectedIconColor = com.storagesense.app.ui.theme.VaultOutline,
                                selectedTextColor = com.storagesense.app.ui.theme.VaultPrimary,
                                unselectedTextColor = com.storagesense.app.ui.theme.VaultOutline,
                                indicatorColor = com.storagesense.app.ui.theme.VaultPrimary.copy(alpha = 0.12f)
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Crossfade(
                targetState = currentScreen,
                animationSpec = tween(durationMillis = 200),
                label = "ScreenTransition"
            ) { screen ->
                when (screen) {
                    is Screen.Chat -> ChatScreen(viewModel = chatViewModel)
                    is Screen.View -> ViewScreen(viewModel = viewViewModel)
                    is Screen.Search -> SearchScreen(viewModel = searchViewModel)
                }
            }
        }
    }
}
