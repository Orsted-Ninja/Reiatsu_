package com.storagesense.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Obsidian & Gilt Vault Material 3 Scheme
private val ObsidianVaultColorScheme = darkColorScheme(
    primary = VaultPrimary,
    onPrimary = VaultOnPrimary,
    primaryContainer = VaultPrimaryContainer,
    onPrimaryContainer = VaultOnPrimaryContainer,
    secondary = VaultSecondary,
    onSecondary = VaultOnPrimary,
    secondaryContainer = VaultSecondaryContainer,
    onSecondaryContainer = VaultOnSurface,
    tertiary = VaultTertiary,
    onTertiary = VaultOnPrimary,
    tertiaryContainer = VaultTertiaryContainer,
    onTertiaryContainer = VaultOnSurface,
    background = VaultBackground,
    onBackground = VaultOnSurface,
    surface = VaultSurface,
    onSurface = VaultOnSurface,
    surfaceVariant = VaultSurfaceContainer,
    onSurfaceVariant = VaultOnSurfaceVariant,
    outline = VaultOutline,
    outlineVariant = VaultOutlineVariant,
    error = VaultError,
    onError = VaultOnPrimary,
    errorContainer = VaultErrorContainer
)

@Composable
fun StorageSenseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // The Obsidian & Gilt Vault is an OLED-first luxury dark vault experience
    val colorScheme = ObsidianVaultColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                // Use WindowCompat insets controller only — avoid deprecated statusBarColor /
                // navigationBarColor (deprecated in API 35). Edge-to-edge + scaffold bg handles colours.
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = false
                insetsController.isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
