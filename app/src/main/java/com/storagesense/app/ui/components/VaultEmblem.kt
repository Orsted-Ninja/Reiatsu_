package com.storagesense.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.storagesense.app.ui.theme.VaultPrimary
import com.storagesense.app.ui.theme.VaultPrimaryContainer

/**
 * Obsidian & Gilt Sacred-Geometry Vault Emblem.
 * Features rotating ambient radiance, geometric hexagon lattice, and pulsing amber core.
 */
@Composable
fun VaultEmblem(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp
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
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val cx = w / 2f
            val cy = h / 2f
            val scale = w / 100f

            // 1. Ambient Radial Glow
            drawCircle(
                color = VaultPrimaryContainer.copy(alpha = 0.12f * pulseAlpha),
                radius = cx * 0.98f
            )

            // 2. Outer Solid Circle
            drawCircle(
                color = VaultPrimaryContainer.copy(alpha = 0.6f),
                radius = 44f * scale,
                style = Stroke(width = 1.5f * scale)
            )

            // 3. Inner Dashed Circle
            drawCircle(
                color = VaultPrimary.copy(alpha = 0.45f),
                radius = 36f * scale,
                style = Stroke(
                    width = 1f * scale,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * scale, 4f * scale), 0f)
                )
            )

            // 4. Hexagon Polygon
            val hexPath = Path().apply {
                moveTo(50f * scale, 22f * scale)
                lineTo(74f * scale, 36f * scale)
                lineTo(74f * scale, 64f * scale)
                lineTo(50f * scale, 78f * scale)
                lineTo(26f * scale, 64f * scale)
                lineTo(26f * scale, 36f * scale)
                close()
            }
            drawPath(
                path = hexPath,
                color = VaultPrimaryContainer.copy(alpha = 0.08f),
                style = Fill
            )
            drawPath(
                path = hexPath,
                color = VaultPrimaryContainer.copy(alpha = 0.9f),
                style = Stroke(width = 1.8f * scale)
            )

            // 5. Precision Lattice Ray Lines
            val rayColor = VaultPrimary.copy(alpha = 0.75f)
            val rayStroke = Stroke(width = 1.2f * scale)
            drawLine(rayColor, Offset(50f * scale, 22f * scale), Offset(50f * scale, 42f * scale), strokeWidth = rayStroke.width)
            drawLine(rayColor, Offset(50f * scale, 58f * scale), Offset(50f * scale, 78f * scale), strokeWidth = rayStroke.width)
            drawLine(rayColor, Offset(26f * scale, 36f * scale), Offset(43f * scale, 46f * scale), strokeWidth = rayStroke.width)
            drawLine(rayColor, Offset(74f * scale, 64f * scale), Offset(57f * scale, 54f * scale), strokeWidth = rayStroke.width)

            // 6. Central Amber Core with Pulsing Glow
            drawCircle(
                color = VaultPrimary.copy(alpha = 0.25f * pulseAlpha),
                radius = 12f * scale
            )
            drawCircle(
                color = VaultPrimary,
                radius = 7.5f * scale,
                style = Fill
            )
        }
    }
}
