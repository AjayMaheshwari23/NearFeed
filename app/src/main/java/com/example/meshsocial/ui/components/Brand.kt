package com.example.meshsocial.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reusable NEAR-FEED product mark.
 *
 * A simple signal/radio glyph (three arcs, like nearby-broadcast waves) + the
 * NEAR-FEED wordmark. Single source of truth used by onboarding, and a compact
 * variant for the Home header.
 */
@Composable
fun NearFeedBrandMark(
    modifier: Modifier = Modifier,
    showWordmark: Boolean = true,
    logoSize: Dp = 56.dp,
    wordmarkSize: Int = 22,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SignalGlyph(size = logoSize, color = MaterialTheme.colorScheme.onSurface)
        if (showWordmark) {
            androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
            Text(
                "NEAR-FEED",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                    fontSize = wordmarkSize.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Small signal glyph: three concentric arcs, bottom-anchored like a wifi/signal
 * mark. Renders in current onSurface color.
 */
@Composable
fun SignalGlyph(size: Dp = 32.dp, color: Color = Color.Unspecified) {
    val strokeWidth = size.value / 8f
    val arcColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    Canvas(modifier = Modifier.size(size)) {
        val center = Offset(this.size.width / 2f, this.size.height)
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        // three arcs, increasing radius
        listOf(0.32f, 0.55f, 0.78f).forEach { r ->
            val radius = this.size.width * r
            drawArc(
                color = arcColor,
                startAngle = -120f,
                sweepAngle = 60f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = stroke,
            )
        }
        drawCircle(color = arcColor, radius = strokeWidth * 0.9f, center = center)
    }
}
