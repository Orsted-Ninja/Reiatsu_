package com.storagesense.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storagesense.app.ui.theme.SenseOutline
import com.storagesense.app.ui.theme.StorageApp
import com.storagesense.app.ui.theme.StorageDoc
import com.storagesense.app.ui.theme.StorageOther
import com.storagesense.app.ui.theme.StoragePhoto
import com.storagesense.app.ui.theme.StorageVideo

data class StorageSegment(
    val name: String,
    val sizeBytes: Long,
    val color: Color,
    val percentage: Float
) {
    val formattedSize: String
        get() {
            val gb = sizeBytes / (1024.0 * 1024.0 * 1024.0)
            val mb = sizeBytes / (1024.0 * 1024.0)
            return when {
                gb >= 1.0 -> String.format("%.1f GB", gb)
                mb >= 1.0 -> String.format("%.0f MB", mb)
                else -> "${sizeBytes / 1024} KB"
            }
        }
}

data class StorageInsight(
    val title: String,
    val description: String,
    val category: String = "Insight"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StorageVisualizationBar(
    usedBytes: Long,
    totalBytes: Long,
    segments: List<StorageSegment>,
    insights: List<StorageInsight> = emptyList(),
    modifier: Modifier = Modifier,
    showInsights: Boolean = true,
    compact: Boolean = false,
    onSegmentClick: ((StorageSegment) -> Unit)? = null
) {
    var selectedSegment by remember { mutableStateOf<StorageSegment?>(null) }

    val usedGb = usedBytes / (1024.0 * 1024.0 * 1024.0)
    val totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0)
    val freeGb = (totalBytes - usedBytes).coerceAtLeast(0L) / (1024.0 * 1024.0 * 1024.0)

    val safeSegments = if (segments.isEmpty() || segments.all { it.percentage <= 0.001f }) {
        listOf(
            StorageSegment("Apps", (usedBytes * 0.35).toLong(), StorageApp, 0.35f),
            StorageSegment("Photos", (usedBytes * 0.25).toLong(), StoragePhoto, 0.25f),
            StorageSegment("Videos", (usedBytes * 0.22).toLong(), StorageVideo, 0.22f),
            StorageSegment("Documents", (usedBytes * 0.10).toLong(), StorageDoc, 0.10f),
            StorageSegment("Other", (usedBytes * 0.08).toLong(), StorageOther, 0.08f)
        )
    } else {
        segments
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(spring(stiffness = Spring.StiffnessLow)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compact) 14.dp else 18.dp)
        ) {
            // Header: Used · Free
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (compact) "Storage Overview" else "Device Storage",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = String.format("%.0f GB used · %.0f GB free", usedGb, freeGb),
                        style = if (compact) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        else MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = String.format("%.0f GB total", totalGb),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }

            Spacer(modifier = Modifier.height(if (compact) 10.dp else 14.dp))

            // Segmented Horizontal Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 12.dp else 16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    safeSegments.forEach { seg ->
                        val weight = seg.percentage.coerceAtLeast(0.02f)
                        val isSelected = selectedSegment?.name == seg.name

                        Box(
                            modifier = Modifier
                                .weight(weight)
                                .fillMaxHeight()
                                .background(seg.color)
                                .clickable {
                                    selectedSegment = if (isSelected) null else seg
                                    onSegmentClick?.invoke(seg)
                                }
                        )
                    }
                }
            }

            // Interactive selected segment tooltip
            AnimatedVisibility(visible = selectedSegment != null) {
                selectedSegment?.let { sel ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(sel.color.copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(sel.color)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = sel.name,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "${sel.formattedSize} (${(sel.percentage * 100).toInt()}%)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Segment Legend Chips
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                safeSegments.forEach { seg ->
                    val isSelected = selectedSegment?.name == seg.name
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                selectedSegment = if (isSelected) null else seg
                                onSegmentClick?.invoke(seg)
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(seg.color)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${seg.name} ${seg.formattedSize}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // Intelligent Insights (if enabled)
            if (showInsights && insights.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    insights.take(3).forEach { insight ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lightbulb,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = insight.title,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
