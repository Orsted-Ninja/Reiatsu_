package com.storagesense.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = SensePrimaryDark,
    onPrimary = SenseOnPrimaryDark,
    primaryContainer = SensePrimaryContainerDark,
    onPrimaryContainer = SenseOnPrimaryContainerDark,
    secondary = SenseSecondaryContainer,
    onSecondary = SenseOnSecondaryContainer,
    tertiary = SenseTertiaryContainer,
    onTertiary = SenseOnTertiaryContainer,
    background = SenseBackgroundDark,
    onBackground = SenseOnBackgroundDark,
    surface = SenseSurfaceDark,
    onSurface = SenseOnSurfaceDark,
    surfaceVariant = SenseSurfaceVariantDark,
    onSurfaceVariant = SenseOnSurfaceVariantDark
)

private val LightColorScheme = lightColorScheme(
    primary = SensePrimary,
    onPrimary = SenseOnPrimary,
    primaryContainer = SensePrimaryContainer,
    onPrimaryContainer = SenseOnPrimaryContainer,
    secondary = SenseSecondary,
    onSecondary = SenseOnSecondary,
    secondaryContainer = SenseSecondaryContainer,
    onSecondaryContainer = SenseOnSecondaryContainer,
    tertiary = SenseTertiary,
    onTertiary = SenseOnTertiary,
    background = SenseBackground,
    onBackground = SenseOnBackground,
    surface = SenseSurface,
    onSurface = SenseOnSurface,
    surfaceVariant = SenseSurfaceVariant,
    onSurfaceVariant = SenseOnSurfaceVariant
)

@Composable
fun StorageSenseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
