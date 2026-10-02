package com.storagesense.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storagesense.app.ui.theme.VaultOnSurface
import com.storagesense.app.ui.theme.VaultOnSurfaceVariant
import com.storagesense.app.ui.theme.VaultOutline
import com.storagesense.app.ui.theme.VaultOutlineVariant
import com.storagesense.app.ui.theme.VaultPrimary
import com.storagesense.app.ui.theme.VaultPrimaryContainer
import com.storagesense.app.ui.theme.VaultSurfaceContainerHigh
import com.storagesense.app.ui.theme.VaultSurfaceContainerLow
import kotlin.math.cos
import kotlin.math.sin

/**
 * Obsidian & Gilt Liquid Glass Curved Arc Radial Gauge.
 * Renders a 180-degree semicircular storage gauge with glowing gold gradient fill,
 * leading needle tip, and available storage badge.
 */
@Composable
fun VaultArcGauge(
    usedBytes: Long,
    totalBytes: Long,
    modifier: Modifier = Modifier
) {
    val progress = if (totalBytes > 0) {
        (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0.01f, 1f)
    } else 0.75f

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
        label = "ArcProgress"
    )

    val usedGb = String.format(java.util.Locale.US, "%.1f", usedBytes / (1024.0 * 1024.0 * 1024.0))
    val availableBytes = (totalBytes - usedBytes).coerceAtLeast(0L)
    val availableGb = String.format(java.util.Locale.US, "%.1f", availableBytes / (1024.0 * 1024.0 * 1024.0))

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Vault Allocation Badge
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(99.dp))
                .background(VaultSurfaceContainerHigh.copy(alpha = 0.6f))
                .border(1.dp, VaultOutlineVariant.copy(alpha = 0.5f), RoundedCornerShape(99.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(VaultPrimary)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "VAULT ALLOCATION",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultOnSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Main Value Hero (Large Serif)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = usedGb,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = FontFamily.Serif,
                    fontSize = 42.sp
                ),
                color = VaultOnSurface
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "GB USED",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = VaultOutline,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Curved Arc Gauge Canvas
        Box(
            modifier = Modifier
                .size(width = 240.dp, height = 125.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Canvas(modifier = Modifier.size(width = 240.dp, height = 125.dp)) {
                val w = size.width
                val h = size.height
                val strokeWidth = 12f
                val radius = (w / 2f) - strokeWidth

                val arcSize = Size(radius * 2f, radius * 2f)
                val arcTopLeft = Offset(strokeWidth, strokeWidth)

                // 1. Inactive Track
                drawArc(
                    color = Color(0xFF1F1F1F),
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // 2. Active Gold Liquid Gradient Arc
                val sweepAngle = 180f * animatedProgress
                val goldGradient = Brush.sweepGradient(
                    colors = listOf(
                        Color(0xFF735C00),
                        VaultPrimaryContainer,
                        VaultPrimary
                    ),
                    center = Offset(w / 2f, h)
                )

                drawArc(
                    brush = goldGradient,
                    startAngle = 180f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // 3. Glowing Leading Tip Indicator
                val angleRad = Math.toRadians((180f + sweepAngle).toDouble())
                val tipX = (w / 2f) + (radius * cos(angleRad)).toFloat()
                val tipY = (h) + (radius * sin(angleRad)).toFloat()

                // Glow circle
                drawCircle(
                    color = VaultPrimary.copy(alpha = 0.4f),
                    radius = strokeWidth * 1.5f,
                    center = Offset(tipX, tipY)
                )
                // Solid gold tip
                drawCircle(
                    color = VaultPrimary,
                    radius = strokeWidth * 0.8f,
                    center = Offset(tipX, tipY)
                )
                // White center pip
                drawCircle(
                    color = Color.White,
                    radius = strokeWidth * 0.4f,
                    center = Offset(tipX, tipY)
                )
            }

            // Bottom Available Capsule Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(VaultSurfaceContainerLow.copy(alpha = 0.9f))
                    .border(1.dp, VaultOutlineVariant.copy(alpha = 0.5f), RoundedCornerShape(99.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$availableGb GB",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = VaultPrimary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "AVAILABLE",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = VaultOnSurfaceVariant
                    )
                }
            }
        }
    }
}
