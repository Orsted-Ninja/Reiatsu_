package com.storagesense.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Image
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
) {
    object Chat : Screen("chat", "Chat", Icons.Outlined.ChatBubbleOutline, Icons.Filled.ChatBubble)
    object View : Screen("view", "View", Icons.Outlined.GridView, Icons.Filled.GridView)
    object Images : Screen("images", "Images", Icons.Outlined.Image, Icons.Filled.Image)
    object Search : Screen("search", "Search", Icons.Outlined.Search, Icons.Filled.Search)
}
