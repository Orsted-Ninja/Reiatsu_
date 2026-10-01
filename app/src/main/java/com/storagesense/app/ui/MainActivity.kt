package com.storagesense.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.storagesense.app.ui.chat.ChatScreen
import com.storagesense.app.ui.chat.ChatViewModel
import com.storagesense.app.ui.dashboard.DashboardScreen
import com.storagesense.app.ui.dashboard.DashboardViewModel
import com.storagesense.app.ui.navigation.Screen
import com.storagesense.app.ui.settings.SettingsScreen
import com.storagesense.app.ui.theme.StorageSenseTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()
    private val dashboardViewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkStoragePermission()

        setContent {
            StorageSenseTheme {
                MainAppContent(
                    chatViewModel = chatViewModel,
                    dashboardViewModel = dashboardViewModel
                )
            }
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
    dashboardViewModel: DashboardViewModel
) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Chat) }

    val screens = listOf(
        Screen.Chat,
        Screen.Dashboard,
        Screen.Settings
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                screens.forEach { screen ->
                    NavigationBarItem(
                        selected = currentScreen.route == screen.route,
                        onClick = { currentScreen = screen },
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Modifier.padding(innerPadding)
        when (currentScreen) {
            is Screen.Chat -> ChatScreen(viewModel = chatViewModel)
            is Screen.Dashboard -> DashboardScreen(viewModel = dashboardViewModel)
            is Screen.Settings -> SettingsScreen()
        }
    }
}
