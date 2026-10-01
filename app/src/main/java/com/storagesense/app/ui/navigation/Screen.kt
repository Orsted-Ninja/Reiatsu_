package com.storagesense.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Chat : Screen("chat", "Assistant", Icons.Default.ChatBubble)
    object Dashboard : Screen("dashboard", "Storage", Icons.Default.PieChart)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}
