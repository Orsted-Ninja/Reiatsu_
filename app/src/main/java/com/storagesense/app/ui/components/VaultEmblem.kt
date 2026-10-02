package com.storagesense.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.storagesense.app.R
import com.storagesense.app.ui.theme.VaultPrimary

/**
 * Obsidian & Gilt App Logo Emblem.
 * Renders the official StorageSense logo with a breathing ambient gold glow ring.
 */
@Composable
fun VaultEmblem(
    modifier: Modifier = Modifier,
    size: Dp = 80.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "VaultEmblemPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    Box(
        modifier = modifier.size(size * 1.2f),
        contentAlignment = Alignment.Center
    ) {
        // Ambient Radial Glow Ring
        Box(
            modifier = Modifier
                .size(size * 1.2f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            VaultPrimary.copy(alpha = 0.25f * pulseAlpha),
                            Color.Transparent
                        )
                    )
                )
        )

        // Logo Container with soft border
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .border(1.5.dp, VaultPrimary.copy(alpha = 0.5f * pulseAlpha), CircleShape)
                .shadow(elevation = 8.dp, shape = CircleShape, spotColor = VaultPrimary),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "StorageSense Logo",
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
            )
        }
    }
}
