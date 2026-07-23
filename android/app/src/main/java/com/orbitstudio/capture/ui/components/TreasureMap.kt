package com.orbitstudio.capture.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.orbitstudio.capture.ui.theme.OrbitColors
import kotlin.math.min

/**
 * Minimap in the kart-game sense: the route is a bold track ribbon, the player is a
 * heading-rotated arrow that glides along it, the current target dot pulses like a
 * beacon, and the final station wears a finish ring. Position is honest dead
 * reckoning: the marker sits ON a station while sweeping (measured anchor) and
 * advances along the route by hardware-detected steps while walking; no step sensor
 * simply means it waits on the last anchored dot.
 *
 * Cells are room-relative grid coordinates; [markerCell] is fractional for smooth
 * between-dot positions; [headingDeg] is heading-space (0 = the sketch's up).
 */
@Composable
fun TreasureMap(
    roomCols: Int,
    roomRows: Int,
    pathCells: List<Pair<Int, Int>>,
    stationCells: List<Pair<Int, Int>>,
    currentStation: Int,
    markerCell: Pair<Float, Float>?,
    headingDeg: Float,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "treasurePulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "treasurePulseAlpha",
    )

    // Marker glides between dead-reckoned positions; heading spins the short way around.
    val markerX by animateFloatAsState(markerCell?.first ?: 0f, tween(250), label = "mkX")
    val markerY by animateFloatAsState(markerCell?.second ?: 0f, tween(250), label = "mkY")
    var accumulatedHeading by remember { mutableFloatStateOf(headingDeg) }
    accumulatedHeading = shortestPathTarget(accumulatedHeading, headingDeg)
    val animatedHeading by animateFloatAsState(accumulatedHeading, tween(150), label = "mkHeading")

    Canvas(modifier = modifier) {
        if (roomCols < 1 || roomRows < 1) return@Canvas
        val pad = 10.dp.toPx()
        val scale = min((size.width - 2 * pad) / roomCols, (size.height - 2 * pad) / roomRows)
        val originX = (size.width - roomCols * scale) / 2f
        val originY = (size.height - roomRows * scale) / 2f
        fun toPx(col: Float, row: Float) = Offset(
            originX + (col + 0.5f) * scale,
            originY + (row + 0.5f) * scale,
        )

        fun cellPx(cell: Pair<Int, Int>) = toPx(cell.first.toFloat(), cell.second.toFloat())

        // Sheet + room outline
        drawRoundRect(
            color = OrbitColors.canvas.copy(alpha = 0.88f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
        )
        drawRect(
            color = OrbitColors.hairline20,
            topLeft = Offset(originX, originY),
            size = androidx.compose.ui.geometry.Size(roomCols * scale, roomRows * scale),
            style = Stroke(width = 1.dp.toPx()),
        )

        // Track ribbon: dark casing under a lighter road, kart-map style. The stretch
        // already driven (up to the current station) fills success-tinted.
        if (pathCells.size > 1) {
            val road = Path()
            val first = cellPx(pathCells.first())
            road.moveTo(first.x, first.y)
            for (cell in pathCells.drop(1)) {
                val p = cellPx(cell)
                road.lineTo(p.x, p.y)
            }
            val casing = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val fill = Stroke(width = 5.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(road, color = OrbitColors.elevated, style = casing)
            drawPath(road, color = OrbitColors.hairline20, style = fill)
            // Dashed center line for the road-map read at a glance.
            drawPath(
                road,
                color = OrbitColors.canvas.copy(alpha = 0.55f),
                style = Stroke(
                    width = 1.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())),
                ),
            )
        }

        // Station dots on top of the road: cleared = filled green, target = pulsing
        // beacon, upcoming = hollow. The last station wears a finish ring.
        val dotR = 6.5.dp.toPx()
        stationCells.forEachIndexed { i, cell ->
            val at = cellPx(cell)
            when {
                i < currentStation -> drawCircle(OrbitColors.success, radius = dotR, center = at)
                i == currentStation -> {
                    drawCircle(
                        color = OrbitColors.accent.copy(alpha = (1f - pulse) * 0.9f),
                        radius = dotR * (1.2f + pulse * 1.3f),
                        center = at,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    drawCircle(OrbitColors.accent, radius = dotR, center = at)
                }
                else -> {
                    drawCircle(OrbitColors.canvas, radius = dotR, center = at)
                    drawCircle(OrbitColors.textTertiary, radius = dotR, center = at, style = Stroke(width = 1.5.dp.toPx()))
                }
            }
            if (i == stationCells.lastIndex && stationCells.size > 1) {
                drawCircle(
                    color = if (i < currentStation) OrbitColors.success else OrbitColors.textTertiary,
                    radius = dotR + 3.dp.toPx(),
                    center = at,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }

        // Dot numbers, small and out of the road's way.
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 11.dp.toPx()
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
            setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
        }
        stationCells.forEachIndexed { i, cell ->
            val at = cellPx(cell)
            drawContext.canvas.nativeCanvas.drawText(
                (i + 1).toString(), at.x, at.y - dotR - 4.dp.toPx(), paint,
            )
        }

        // Player arrow: a kart-style chevron at the dead-reckoned spot, nose pointing
        // where the phone points. White body over an accent puck so it reads on any road.
        if (markerCell != null) {
            val at = toPx(markerX, markerY)
            val r = 9.5.dp.toPx()
            drawCircle(OrbitColors.accent, radius = r, center = at)
            drawCircle(OrbitColors.canvas.copy(alpha = 0.35f), radius = r, center = at, style = Stroke(width = 1.dp.toPx()))
            rotate(degrees = animatedHeading, pivot = at) {
                val nose = Path().apply {
                    moveTo(at.x, at.y - r * 0.62f)          // tip
                    lineTo(at.x - r * 0.5f, at.y + r * 0.45f)
                    lineTo(at.x, at.y + r * 0.12f)           // notch, arrowhead read
                    lineTo(at.x + r * 0.5f, at.y + r * 0.45f)
                    close()
                }
                drawPath(nose, color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}
