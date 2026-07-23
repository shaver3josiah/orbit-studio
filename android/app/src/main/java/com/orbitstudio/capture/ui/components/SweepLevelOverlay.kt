package com.orbitstudio.capture.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.orbitstudio.capture.ui.theme.OrbitColors
import kotlin.math.abs

/**
 * Sweep alignment overlay for pano mode: a fixed center reticle plus a floating line
 * that tilts with roll and rides up/down with the pitch error to the current ring.
 * Fly the line into the reticle and hold: line level and centered = phone level and
 * on the ring, and it turns green as the auto-fire window opens. Zenith/nadir targets
 * park the line at the reticle whenever pitch is within tolerance (roll still shows).
 */
@Composable
fun SweepLevelOverlay(
    rollDeg: Float,
    pitchErrorDeg: Float,   // target.pitchDeg - current pitch; + = tilt up to reach
    aligned: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val lineColor = if (aligned) OrbitColors.success else OrbitColors.textPrimary.copy(alpha = 0.85f)

        // Fixed reticle: two side brackets and a center gap the line should sit in.
        val bracket = 38.dp.toPx()
        val gap = 62.dp.toPx()
        val stroke = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
        drawLine(OrbitColors.textSecondary, Offset(cx - gap - bracket, cy), Offset(cx - gap, cy), stroke.width, StrokeCap.Round)
        drawLine(OrbitColors.textSecondary, Offset(cx + gap, cy), Offset(cx + gap + bracket, cy), stroke.width, StrokeCap.Round)

        // Floating horizon line: 1 degree of pitch error = ~1% of height; clamped so it
        // never leaves the middle band and always stays a usable cue.
        val offsetY = (pitchErrorDeg * size.height / 100f).coerceIn(-size.height * 0.32f, size.height * 0.32f)
        val lineY = cy + offsetY
        rotate(degrees = -rollDeg, pivot = Offset(cx, lineY)) {
            drawLine(lineColor, Offset(cx - gap * 0.85f, lineY), Offset(cx + gap * 0.85f, lineY), 5.dp.toPx(), StrokeCap.Round)
            // Center tick so the line reads as an instrument, not a stray hair.
            drawLine(lineColor, Offset(cx, lineY - 7.dp.toPx()), Offset(cx, lineY + 7.dp.toPx()), 3.5.dp.toPx(), StrokeCap.Round)
        }

        // Aligned: close the reticle with a soft ring, the "locked on" read.
        if (aligned) {
            drawCircle(
                color = OrbitColors.success.copy(alpha = 0.8f),
                radius = gap * 0.55f,
                center = Offset(cx, cy),
                style = Stroke(width = 3.5.dp.toPx()),
            )
        }
        // Way off? A bold edge arrow says which way to tilt (up = arrow at top).
        if (abs(pitchErrorDeg) > 25f) {
            val up = pitchErrorDeg > 0
            val ay = if (up) cy - size.height * 0.36f else cy + size.height * 0.36f
            val dir = if (up) -1f else 1f
            val a = 13.dp.toPx()
            drawLine(OrbitColors.warning, Offset(cx - a, ay), Offset(cx, ay + dir * a), 5.dp.toPx(), StrokeCap.Round)
            drawLine(OrbitColors.warning, Offset(cx + a, ay), Offset(cx, ay + dir * a), 5.dp.toPx(), StrokeCap.Round)
        }
    }
}
