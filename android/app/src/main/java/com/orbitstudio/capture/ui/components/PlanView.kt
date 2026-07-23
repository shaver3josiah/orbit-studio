package com.orbitstudio.capture.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.orbitstudio.capture.ui.theme.OrbitColors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Converts a heading (0 = up/12 o'clock, clockwise-positive) to Canvas.drawArc's
 *  coordinate space (0 = 3 o'clock, clockwise-positive). */
private fun toCanvasAngle(headingDeg: Float): Float = headingDeg - 90f

/** Given an accumulated angle (may be outside 0..360) and a new raw target in 0..360,
 *  returns the equivalent target angle nearest to `fromAccumulated` so animating between
 *  them never sweeps the long way around (e.g. 359 -> 1 becomes 359 -> 361, not 359 -> 1).
 *  Shared with TreasureMap's player arrow. */
internal fun shortestPathTarget(fromAccumulated: Float, rawTargetDeg: Float): Float {
    val normalizedTarget = ((rawTargetDeg % 360f) + 360f) % 360f
    val currentNormalized = ((fromAccumulated % 360f) + 360f) % 360f
    var delta = normalizedTarget - currentNormalized
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return fromAccumulated + delta
}

@Composable
fun PlanView(
    headingDeg: Float,
    shotHeadingsDeg: List<Float>,
    targetHeadingDeg: Float?,
    modifier: Modifier = Modifier,
) {
    var accumulatedHeading by remember { mutableFloatStateOf(headingDeg) }
    accumulatedHeading = shortestPathTarget(accumulatedHeading, headingDeg)
    val animatedHeading by animateFloatAsState(
        targetValue = accumulatedHeading,
        animationSpec = tween(150),
        label = "planViewHeading",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "planViewTargetPulse")
    val targetPulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "targetPulseAlpha",
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) / 2f

        // Disc fill + rings
        drawCircle(color = OrbitColors.elevated, radius = radius, center = center)
        drawCircle(
            color = OrbitColors.hairline12,
            radius = radius * 0.6f,
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )
        drawCircle(
            color = OrbitColors.hairline20,
            radius = radius,
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )

        // Shot coverage wedges
        val shotWedge = 24f
        for (shotHeading in shotHeadingsDeg) {
            val start = toCanvasAngle(shotHeading - shotWedge / 2f)
            drawArc(
                color = OrbitColors.successSoft,
                startAngle = start,
                sweepAngle = shotWedge,
                useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                style = Fill,
            )
        }

        // Flashlight cone (current heading)
        val coneWedge = 66f
        val coneStart = toCanvasAngle(animatedHeading - coneWedge / 2f)
        drawArc(
            color = OrbitColors.accentSoft,
            startAngle = coneStart,
            sweepAngle = coneWedge,
            useCenter = true,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
            style = Fill,
        )
        drawArc(
            color = OrbitColors.accent,
            startAngle = coneStart,
            sweepAngle = coneWedge,
            useCenter = true,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
            style = Stroke(width = 1.dp.toPx()),
        )

        // Target marker
        if (targetHeadingDeg != null) {
            val targetWedge = 12f
            val targetStart = toCanvasAngle(targetHeadingDeg - targetWedge / 2f)
            drawArc(
                color = OrbitColors.warning.copy(alpha = targetPulseAlpha),
                startAngle = targetStart,
                sweepAngle = targetWedge,
                useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                style = Stroke(width = 1.5.dp.toPx()),
            )
            val dotAngleRad = Math.toRadians(toCanvasAngle(targetHeadingDeg).toDouble())
            val dotRadius = radius + 5.dp.toPx()
            val dotCenter = Offset(
                x = center.x + (cos(dotAngleRad) * dotRadius).toFloat(),
                y = center.y + (sin(dotAngleRad) * dotRadius).toFloat(),
            )
            drawCircle(
                color = OrbitColors.warning.copy(alpha = targetPulseAlpha),
                radius = 2.5.dp.toPx(),
                center = dotCenter,
            )
        }

        // Person dot
        drawCircle(color = OrbitColors.textPrimary, radius = 2.dp.toPx(), center = center)
    }
}

@Composable
fun TurnArrows(signedDeltaDeg: Float, modifier: Modifier = Modifier) {
    val absDelta = kotlin.math.abs(signedDeltaDeg)
    if (absDelta < 8f) {
        Box(modifier = modifier)
        return
    }
    val chevronCount = when {
        absDelta < 25f -> 1
        absDelta < 60f -> 2
        else -> 3
    }
    val pointsRight = signedDeltaDeg > 0

    Canvas(modifier = modifier.size(width = 68.dp, height = 32.dp)) {
        val chevronWidth = 18.dp.toPx()
        val chevronHeight = 24.dp.toPx()
        val spacing = 15.dp.toPx()
        val strokeWidth = 4.dp.toPx()
        val totalWidth = chevronWidth + (chevronCount - 1) * spacing
        val startX = (size.width - totalWidth) / 2f
        val midY = size.height / 2f

        for (i in 0 until chevronCount) {
            val x = startX + i * spacing
            val path = Path().apply {
                if (pointsRight) {
                    moveTo(x, midY - chevronHeight / 2f)
                    lineTo(x + chevronWidth, midY)
                    lineTo(x, midY + chevronHeight / 2f)
                } else {
                    moveTo(x + chevronWidth, midY - chevronHeight / 2f)
                    lineTo(x, midY)
                    lineTo(x + chevronWidth, midY + chevronHeight / 2f)
                }
            }
            drawPath(
                path = path,
                color = OrbitColors.accent,
                style = Stroke(width = strokeWidth),
            )
        }
    }
}
