package com.orbitstudio.capture.ui.sketch

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.orbitstudio.capture.data.FeatureType
import com.orbitstudio.capture.data.FloorPlan
import com.orbitstudio.capture.data.PlanFeature
import com.orbitstudio.capture.data.PlanRoom
import com.orbitstudio.capture.ui.theme.SketchPalette
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Cell-to-screen mapping for [drawSketch]. [cellPx] already folds in the current zoom
 * level (world cell size in screen px); world cell (c, r) -> screen (c*cellPx + panX,
 * r*cellPx + panY).
 */
data class SketchViewport(val cellPx: Float, val panX: Float, val panY: Float)

// Room floor fills, rotated by room index. Values lifted verbatim from the design system's
// --fill-slate/sky/sand/sage/powder tokens (light-theme hex, alpha-blended so the same hue
// reads correctly on both sheet backgrounds instead of shipping a separate dark set).
private val ROOM_FILLS = listOf(
    Color(0xFFCFD8DC), // slate
    Color(0xFFE3F2FD), // sky
    Color(0xFFFFE0B2), // sand
    Color(0xFFC8E6C9), // sage
    Color(0xFFBBDEFB), // powder
)
private const val ROOM_FILL_ALPHA = 0.35f

private fun roomRectPx(room: PlanRoom, cellPx: Float): Rect = Rect(
    offset = Offset(room.col * cellPx, room.row * cellPx),
    size = Size(room.cols * cellPx, room.rows * cellPx),
)

private fun featureRectPx(f: PlanFeature, cellPx: Float): Rect = Rect(
    offset = Offset(f.col * cellPx, f.row * cellPx),
    size = Size(f.cols * cellPx, f.rows * cellPx),
)

/**
 * Draws a [FloorPlan] onto the current [DrawScope] through [vp] (cell size + pan offset).
 * Pure geometry from the frozen data model — no gestures, no state reads, no composables.
 * The caller (screen layer) owns text layout: room-name labels are intentionally NOT drawn
 * here because DrawScope text needs a TextMeasurer, which this function is not given.
 *
 * @param selectedId room id or feature id to ring-highlight, or null to skip.
 * @param pathCells ordered cell centers (col, row) for the coaching walk path, or null to skip.
 * @param previewRect rubber-band selection as [col, row, cols, rows], or null to skip.
 * @param showGrid whether to draw the cell grid lines.
 */
fun DrawScope.drawSketch(
    plan: FloorPlan,
    vp: SketchViewport,
    pal: SketchPalette,
    selectedId: String?,
    pathCells: List<Pair<Int, Int>>?,
    previewRect: IntArray?,
    showGrid: Boolean,
    // When true, the selected object gets Bridge-style edit handles: a filled
    // bottom-right resize grip plus a round rotate/flip grip above it.
    showHandles: Boolean = false,
) {
    translate(left = vp.panX, top = vp.panY) {
        val cellPx = vp.cellPx
        val hairline = 1.dp.toPx()
        val strokeW = 1.5.dp.toPx()
        val gridW = plan.gridCols * cellPx
        val gridH = plan.gridRows * cellPx

        // 1. Drafting sheet background, sized to the plan's grid extent (not the viewport,
        // which may be larger or smaller once pan/zoom are in play).
        drawRect(color = pal.sheet, size = Size(gridW, gridH))

        // 2. Inset-frame vignette: a top-edge darkening fade (Compose has no native inset
        // box-shadow) plus a 1px hairline border around the sheet.
        val vignetteH = 44.dp.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent),
                startY = 0f,
                endY = vignetteH,
            ),
            size = Size(gridW, vignetteH.coerceAtMost(gridH)),
        )
        drawRect(color = pal.line, size = Size(gridW, gridH), style = Stroke(width = hairline))

        // 3. Grid: per-cell minor lines only when cells are big enough to read (otherwise a
        // fine-resolution grid, e.g. 160x200, would draw as a solid mass); a major line every
        // ~0.5m always draws so the sheet keeps a readable reference grid at any resolution.
        // Loops are clipped to the visible cell range (not 0..gridCols/gridRows) so a
        // 7200x9000 grid still costs O(visible lines), not O(grid size).
        if (showGrid) {
            val firstCol = floor(-vp.panX / cellPx).toInt().coerceIn(0, plan.gridCols)
            val lastCol = ceil((size.width - vp.panX) / cellPx).toInt().coerceIn(0, plan.gridCols)
            val firstRow = floor(-vp.panY / cellPx).toInt().coerceIn(0, plan.gridRows)
            val lastRow = ceil((size.height - vp.panY) / cellPx).toInt().coerceIn(0, plan.gridRows)
            val visLeft = firstCol * cellPx
            val visRight = lastCol * cellPx
            val visTop = firstRow * cellPx
            val visBottom = lastRow * cellPx

            val major = max(1, Math.round(0.5f / plan.scaleMPerCell))
            val majorColor = pal.line.copy(alpha = pal.line.alpha * 0.4f)
            if (cellPx >= 7f) {
                for (c in firstCol..lastCol) {
                    val gx = c * cellPx
                    drawLine(pal.grid, Offset(gx, visTop), Offset(gx, visBottom), strokeWidth = hairline)
                }
                for (r in firstRow..lastRow) {
                    val gy = r * cellPx
                    drawLine(pal.grid, Offset(visLeft, gy), Offset(visRight, gy), strokeWidth = hairline)
                }
            }
            var mc = (firstCol / major) * major
            while (mc <= lastCol) {
                val gx = mc * cellPx
                drawLine(majorColor, Offset(gx, visTop), Offset(gx, visBottom), strokeWidth = hairline)
                mc += major
            }
            var mr = (firstRow / major) * major
            while (mr <= lastRow) {
                val gy = mr * cellPx
                drawLine(majorColor, Offset(visLeft, gy), Offset(visRight, gy), strokeWidth = hairline)
                mr += major
            }
        }

        // 4. Rooms: floor poché (fill + outline), then a thick exterior wall band
        plan.rooms.forEachIndexed { index, room ->
            val r = roomRectPx(room, cellPx)
            drawRect(color = ROOM_FILLS[index % ROOM_FILLS.size].copy(alpha = ROOM_FILL_ALPHA), topLeft = r.topLeft, size = r.size)
            drawRect(color = pal.ink, topLeft = r.topLeft, size = r.size, style = Stroke(width = strokeW))
            val wallThickness = cellPx * 0.18f
            drawRect(color = pal.ink, topLeft = r.topLeft, size = r.size, style = Stroke(width = wallThickness))
        }

        // 5. Features, by type then variant
        plan.features.forEach { f ->
            when (f.type) {
                FeatureType.WALL -> drawWallFeature(f, cellPx, pal)
                FeatureType.WINDOW -> drawWindowFeature(f, cellPx, pal, strokeW)
                FeatureType.DOOR -> drawDoorFeature(f, cellPx, pal, strokeW)
                FeatureType.OBSTACLE -> drawObstacleFeature(f, cellPx, pal, strokeW)
            }
        }

        // 6. Selection ring + tint on the selected room or feature, plus Bridge-style
        // edit handles (resize grip bottom-right, rotate grip above) when requested.
        val selectedRoom = selectedId?.let { id -> plan.rooms.firstOrNull { it.id == id } }
        val selRect: Rect? = if (selectedRoom != null) {
            roomRectPx(selectedRoom, cellPx)
        } else {
            selectedId?.let { id -> plan.features.firstOrNull { it.id == id } }?.let { featureRectPx(it, cellPx) }
        }
        if (selRect != null) {
            drawRect(color = pal.accentTint.copy(alpha = 0.45f), topLeft = selRect.topLeft, size = selRect.size)
            drawRect(color = pal.accent, topLeft = selRect.topLeft, size = selRect.size, style = Stroke(width = 2.dp.toPx()))
            if (showHandles) drawEditHandles(selRect, pal)
        }

        // 7. Coaching walk path
        if (!pathCells.isNullOrEmpty()) drawWalkPath(pathCells, cellPx, pal)

        // 8. Rubber-band preview rect
        if (previewRect != null && previewRect.size >= 4) drawPreviewRect(previewRect, cellPx, pal, strokeW)
    }
}

/** Interior wall: a solid ink-colored bar centered within the 1-cell-thick feature band.
 *  Bar is narrower than the full cell (ponytail: fixed 0.4x weight, not a structural value) so
 *  it reads distinctly from the thicker exterior wall poché drawn under it. */
private fun DrawScope.drawWallFeature(f: PlanFeature, cellPx: Float, pal: SketchPalette) {
    val r = featureRectPx(f, cellPx)
    val bar = cellPx * 0.4f
    if (f.horizontal) {
        val cy = r.top + r.height / 2f
        drawRect(color = pal.ink, topLeft = Offset(r.left, cy - bar / 2f), size = Size(r.width, bar))
    } else {
        val cx = r.left + r.width / 2f
        drawRect(color = pal.ink, topLeft = Offset(cx - bar / 2f, r.top), size = Size(bar, r.height))
    }
}

/** Window: unfilled stroked rect exactly on the wall gap, plus glazing lines per variant. All
 *  glazing stays within [featureRectPx] (a window must not spill out of its 1-cell wall band):
 *  "small" and the default both draw the plain double parallel glazing line along the window's
 *  long axis; "bay" adds a shallow trapezoid bulge capped at half a cell (or half the band
 *  thickness, whichever is smaller) so it reads as a bump, not a spill. */
private fun DrawScope.drawWindowFeature(f: PlanFeature, cellPx: Float, pal: SketchPalette, strokeW: Float) {
    val r = featureRectPx(f, cellPx)
    drawRect(color = pal.accent, topLeft = r.topLeft, size = r.size, style = Stroke(width = strokeW))
    when (f.variant) {
        "bay" -> {
            val thickness = if (f.horizontal) r.height else r.width
            val bulge = (thickness * 0.5f).coerceAtMost(cellPx * 0.5f)
            val path = Path()
            if (f.horizontal) {
                path.moveTo(r.left, r.top)
                path.lineTo(r.left + r.width * 0.2f, r.top - bulge)
                path.lineTo(r.right - r.width * 0.2f, r.top - bulge)
                path.lineTo(r.right, r.top)
            } else {
                path.moveTo(r.left, r.top)
                path.lineTo(r.left - bulge, r.top + r.height * 0.2f)
                path.lineTo(r.left - bulge, r.bottom - r.height * 0.2f)
                path.lineTo(r.left, r.bottom)
            }
            drawPath(path, color = pal.accent, style = Stroke(width = strokeW))
            drawWindowGlazing(r, f.horizontal, pal, strokeW)
        }
        // "small" reads as the same in-band glazing as default (ponytail: no visual distinction
        // left once the perpendicular mullion lines that used to spill were removed; upgrade with
        // a genuinely narrower symbol if the catalog needs "small" to look different again).
        else -> drawWindowGlazing(r, f.horizontal, pal, strokeW)
    }
}

private fun DrawScope.drawWindowGlazing(r: Rect, horizontal: Boolean, pal: SketchPalette, strokeW: Float) {
    if (horizontal) {
        val y1 = r.top + r.height / 3f
        val y2 = r.top + r.height * 2f / 3f
        drawLine(pal.accent, Offset(r.left, y1), Offset(r.right, y1), strokeWidth = strokeW)
        drawLine(pal.accent, Offset(r.left, y2), Offset(r.right, y2), strokeWidth = strokeW)
    } else {
        val x1 = r.left + r.width / 3f
        val x2 = r.left + r.width * 2f / 3f
        drawLine(pal.accent, Offset(x1, r.top), Offset(x1, r.bottom), strokeWidth = strokeW)
        drawLine(pal.accent, Offset(x2, r.top), Offset(x2, r.bottom), strokeWidth = strokeW)
    }
}

/** Door: filled jambs at both ends of the opening. "double" mirrors two leaves+arcs off each
 *  jamb; "sliding" draws an offset bar with no swing arc; default ("single"/"") is one leaf
 *  swinging off the near jamb with a quarter-circle arc — mirrors the catalog door-single symbol.
 *  Ponytail: swings toward +row/+col (the band's "far" edge) since PlanFeature doesn't encode
 *  which side of the wall the room interior is on; upgrade if mirrored swings matter later. */
private fun DrawScope.drawDoorFeature(f: PlanFeature, cellPx: Float, pal: SketchPalette, strokeW: Float) {
    val r = featureRectPx(f, cellPx)
    if (f.horizontal) {
        val jamb = (strokeW * 4f).coerceAtMost(r.width * 0.3f)
        drawRect(pal.ink, topLeft = Offset(r.left, r.top), size = Size(jamb, r.height))
        drawRect(pal.ink, topLeft = Offset(r.right - jamb, r.top), size = Size(jamb, r.height))
        val leafLen = (r.width - jamb * 2f).coerceAtLeast(0f)
        if (leafLen <= 0f) return
        when (f.variant) {
            "opening" -> {} // clean gap: jambs only, no leaf, no swing arc
            "sliding" -> {
                val barH = r.height * 0.5f
                drawRect(pal.muted, topLeft = Offset(r.left + jamb, r.top), size = Size(leafLen, barH))
            }
            "double" -> {
                val half = leafLen / 2f
                drawDoorLeaf(Offset(r.left + jamb, r.bottom), half, pal.muted, strokeW)
                drawDoorLeaf(Offset(r.right - jamb, r.bottom), -half, pal.muted, strokeW)
            }
            else -> drawDoorLeaf(Offset(r.left + jamb, r.bottom), leafLen, pal.muted, strokeW)
        }
    } else {
        val jamb = (strokeW * 4f).coerceAtMost(r.height * 0.3f)
        drawRect(pal.ink, topLeft = Offset(r.left, r.top), size = Size(r.width, jamb))
        drawRect(pal.ink, topLeft = Offset(r.left, r.bottom - jamb), size = Size(r.width, jamb))
        val leafLen = (r.height - jamb * 2f).coerceAtLeast(0f)
        if (leafLen <= 0f) return
        when (f.variant) {
            "opening" -> {} // clean gap: jambs only, no leaf, no swing arc
            "sliding" -> {
                val barW = r.width * 0.5f
                drawRect(pal.muted, topLeft = Offset(r.left, r.top + jamb), size = Size(barW, leafLen))
            }
            "double" -> {
                val half = leafLen / 2f
                drawDoorLeafVertical(Offset(r.right, r.top + jamb), half, pal.muted, strokeW)
                drawDoorLeafVertical(Offset(r.right, r.bottom - jamb), -half, pal.muted, strokeW)
            }
            else -> drawDoorLeafVertical(Offset(r.right, r.top + jamb), leafLen, pal.muted, strokeW)
        }
    }
}

/** One horizontal-band door leaf: a vertical leaf line dropping [leafLen] (sign = direction)
 *  from [hinge], plus the quarter-circle swing arc back to the jamb. */
private fun DrawScope.drawDoorLeaf(hinge: Offset, leafLen: Float, color: Color, strokeW: Float) {
    val len = kotlin.math.abs(leafLen)
    val dir = if (leafLen >= 0f) 1f else -1f
    val tip = Offset(hinge.x, hinge.y + len)
    drawLine(color, hinge, tip, strokeWidth = strokeW)
    // Quarter-circle centered on the hinge (radius = leaf length), so it always meets the
    // leaf tip (due south) and sweeps to the wall (east for dir>=0, west for dir<0).
    drawArc(
        color = color,
        startAngle = if (dir >= 0f) 0f else 90f,
        sweepAngle = 90f,
        useCenter = false,
        topLeft = Offset(hinge.x - len, hinge.y - len),
        size = Size(len * 2f, len * 2f),
        style = Stroke(width = strokeW),
    )
}

/** One vertical-band door leaf: a horizontal leaf line reaching [leafLen] (sign = direction)
 *  from [hinge], plus the quarter-circle swing arc. */
private fun DrawScope.drawDoorLeafVertical(hinge: Offset, leafLen: Float, color: Color, strokeW: Float) {
    val len = kotlin.math.abs(leafLen)
    val dir = if (leafLen >= 0f) 1f else -1f
    // Leaf always reaches east into the room; dir only selects the arc's sweep quadrant so
    // a double door's two leaves swing symmetrically (top jamb south, bottom jamb north).
    val tip = Offset(hinge.x + len, hinge.y)
    drawLine(color, hinge, tip, strokeWidth = strokeW)
    drawArc(
        color = color,
        startAngle = if (dir >= 0f) 0f else 270f,
        sweepAngle = 90f,
        useCenter = false,
        topLeft = Offset(hinge.x - len, hinge.y - len),
        size = Size(len * 2f, len * 2f),
        style = Stroke(width = strokeW),
    )
}

/** Obstacle: stroked box plus a variant-specific fill. "column" is a small filled roundel;
 *  "stairs" adds parallel tread lines across the shorter axis; "void" dashes the box outline;
 *  default ("generic"/"") crosses the box with both diagonals (an X). */
private fun DrawScope.drawObstacleFeature(f: PlanFeature, cellPx: Float, pal: SketchPalette, strokeW: Float) {
    val r = featureRectPx(f, cellPx)
    when (f.variant) {
        "column" -> {
            drawRect(color = pal.muted, topLeft = r.topLeft, size = r.size, style = Stroke(width = strokeW))
            drawCircle(color = pal.muted, radius = min(r.width, r.height) * 0.22f, center = r.center)
        }
        "stairs" -> {
            drawRect(color = pal.muted, topLeft = r.topLeft, size = r.size, style = Stroke(width = strokeW))
            val treads = 6
            if (r.width >= r.height) {
                for (i in 1 until treads) {
                    val x = r.left + r.width * i / treads
                    drawLine(pal.muted, Offset(x, r.top), Offset(x, r.bottom), strokeWidth = strokeW)
                }
            } else {
                for (i in 1 until treads) {
                    val y = r.top + r.height * i / treads
                    drawLine(pal.muted, Offset(r.left, y), Offset(r.right, y), strokeWidth = strokeW)
                }
            }
        }
        "void" -> {
            drawRect(
                color = pal.muted,
                topLeft = r.topLeft,
                size = r.size,
                style = Stroke(width = strokeW, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
            )
            drawLine(pal.muted, r.topLeft, r.bottomRight, strokeWidth = strokeW)
            drawLine(pal.muted, Offset(r.right, r.top), Offset(r.left, r.bottom), strokeWidth = strokeW)
        }
        "bed-queen", "bed-double" -> drawBedFeature(r, pal, strokeW)
        "chair" -> drawChairFeature(r, pal, strokeW)
        "sofa" -> drawSofaFeature(r, pal, strokeW)
        "table" -> {
            val corner = min(r.width, r.height) * 0.1f
            drawRoundRect(color = pal.muted, topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(corner), style = Stroke(width = strokeW))
        }
        "desk" -> drawDeskFeature(r, pal, strokeW)
        else -> {
            drawRect(color = pal.muted, topLeft = r.topLeft, size = r.size, style = Stroke(width = strokeW))
            drawLine(pal.muted, r.topLeft, r.bottomRight, strokeWidth = strokeW)
            drawLine(pal.muted, Offset(r.right, r.top), Offset(r.left, r.bottom), strokeWidth = strokeW)
        }
    }
}

/** Bed (queen/double): rounded mattress outline, a pillow band across the head (short edge),
 *  and a thin inset seam echoing the mattress edge. Stroke-only line art over the box, same as
 *  the other obstacle variants. */
private fun DrawScope.drawBedFeature(r: Rect, pal: SketchPalette, strokeW: Float) {
    val corner = min(r.width, r.height) * 0.12f
    drawRoundRect(color = pal.muted, topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(corner), style = Stroke(width = strokeW))
    val portrait = r.height >= r.width
    val headInset = (if (portrait) r.height else r.width) * 0.18f
    if (portrait) {
        val y = r.top + headInset
        drawLine(pal.muted, Offset(r.left, y), Offset(r.right, y), strokeWidth = strokeW)
    } else {
        val x = r.left + headInset
        drawLine(pal.muted, Offset(x, r.top), Offset(x, r.bottom), strokeWidth = strokeW)
    }
    val seamInset = min(r.width, r.height) * 0.08f
    val seamRect = Rect(r.left + seamInset, r.top + seamInset, r.right - seamInset, r.bottom - seamInset)
    drawRoundRect(color = pal.muted, topLeft = seamRect.topLeft, size = seamRect.size, cornerRadius = CornerRadius(corner * 0.6f), style = Stroke(width = strokeW * 0.6f))
}

/** Chair: small rounded square, an inset seat square, and a back-edge line along the top. */
private fun DrawScope.drawChairFeature(r: Rect, pal: SketchPalette, strokeW: Float) {
    val corner = min(r.width, r.height) * 0.15f
    drawRoundRect(color = pal.muted, topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(corner), style = Stroke(width = strokeW))
    val seatInset = min(r.width, r.height) * 0.22f
    val seatRect = Rect(r.left + seatInset, r.top + seatInset, r.right - seatInset, r.bottom - seatInset)
    drawRoundRect(color = pal.muted, topLeft = seatRect.topLeft, size = seatRect.size, cornerRadius = CornerRadius(corner * 0.5f), style = Stroke(width = strokeW * 0.8f))
    drawLine(pal.muted, Offset(r.left, r.top), Offset(r.right, r.top), strokeWidth = strokeW * 1.5f)
}

/** Sofa: rounded rect, a back-edge line along the long top edge, and two short arm lines at
 *  each end. */
private fun DrawScope.drawSofaFeature(r: Rect, pal: SketchPalette, strokeW: Float) {
    val corner = min(r.width, r.height) * 0.15f
    drawRoundRect(color = pal.muted, topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(corner), style = Stroke(width = strokeW))
    val backInset = min(r.width, r.height) * 0.15f
    drawLine(pal.muted, Offset(r.left + backInset, r.top + backInset), Offset(r.right - backInset, r.top + backInset), strokeWidth = strokeW)
    val armLen = (if (r.width >= r.height) r.height else r.width) * 0.5f
    drawLine(pal.muted, Offset(r.left + backInset, r.top), Offset(r.left + backInset, r.top + armLen), strokeWidth = strokeW)
    drawLine(pal.muted, Offset(r.right - backInset, r.top), Offset(r.right - backInset, r.top + armLen), strokeWidth = strokeW)
}

/** Desk: rounded rect plus one drawer line near an edge. */
private fun DrawScope.drawDeskFeature(r: Rect, pal: SketchPalette, strokeW: Float) {
    val corner = min(r.width, r.height) * 0.1f
    drawRoundRect(color = pal.muted, topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(corner), style = Stroke(width = strokeW))
    val drawerInset = (if (r.width >= r.height) r.height else r.width) * 0.3f
    if (r.width >= r.height) {
        val y = r.bottom - drawerInset
        drawLine(pal.muted, Offset(r.left, y), Offset(r.right, y), strokeWidth = strokeW)
    } else {
        val x = r.right - drawerInset
        drawLine(pal.muted, Offset(x, r.top), Offset(x, r.bottom), strokeWidth = strokeW)
    }
}

/** Accent polyline through cell centers, a filled dot at the start, an arrowhead at the end. */
private fun DrawScope.drawWalkPath(cells: List<Pair<Int, Int>>, cellPx: Float, pal: SketchPalette) {
    val points = cells.map { (c, row) -> Offset((c + 0.5f) * cellPx, (row + 0.5f) * cellPx) }
    val strokeW = 2.dp.toPx()
    if (points.size >= 2) {
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
        drawPath(path, color = pal.accent, style = Stroke(width = strokeW))

        val tail = points[points.size - 1]
        val prev = points[points.size - 2]
        val angle = atan2(tail.y - prev.y, tail.x - prev.x)
        val arrowLen = 8.dp.toPx()
        val spread = 0.5f
        val p1 = Offset(tail.x - arrowLen * cos(angle - spread), tail.y - arrowLen * sin(angle - spread))
        val p2 = Offset(tail.x - arrowLen * cos(angle + spread), tail.y - arrowLen * sin(angle + spread))
        val arrowPath = Path().apply {
            moveTo(tail.x, tail.y)
            lineTo(p1.x, p1.y)
            lineTo(p2.x, p2.y)
            close()
        }
        drawPath(arrowPath, color = pal.accent, style = Fill)
    }
    drawCircle(color = pal.accent, radius = 4.dp.toPx(), center = points.first())
}

/** Bridge-style edit grips on the selected object's bounding box: a filled blue square at
 *  the bottom-right corner (drag to resize) and a round blue grip centered above the top
 *  edge (tap/drag to rotate-flip). Sized in dp so they stay grabbable at any zoom. */
private fun DrawScope.drawEditHandles(r: Rect, pal: SketchPalette) {
    val half = 7.dp.toPx()
    // Resize grip — bottom-right corner.
    drawRect(
        color = pal.accent,
        topLeft = Offset(r.right - half, r.bottom - half),
        size = Size(half * 2f, half * 2f),
    )
    drawRect(
        color = pal.accentInk,
        topLeft = Offset(r.right - half, r.bottom - half),
        size = Size(half * 2f, half * 2f),
        style = Stroke(width = 1.5.dp.toPx()),
    )
    // Rotate grip — a stalk up from top-center to a filled roundel.
    val topC = Offset(r.left + r.width / 2f, r.top)
    val grip = Offset(topC.x, topC.y - 22.dp.toPx())
    drawLine(pal.accent, topC, grip, strokeWidth = 2.dp.toPx())
    drawCircle(pal.accent, radius = 7.dp.toPx(), center = grip)
    drawCircle(pal.accentInk, radius = 7.dp.toPx(), center = grip, style = Stroke(width = 1.5.dp.toPx()))
}

/** Dashed accent rubber-band rect for an in-progress drag selection. */
private fun DrawScope.drawPreviewRect(rectCells: IntArray, cellPx: Float, pal: SketchPalette, strokeW: Float) {
    val topLeft = Offset(rectCells[0] * cellPx, rectCells[1] * cellPx)
    val sz = Size(rectCells[2] * cellPx, rectCells[3] * cellPx)
    drawRect(
        color = pal.accent,
        topLeft = topLeft,
        size = sz,
        style = Stroke(width = strokeW, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))),
    )
}
