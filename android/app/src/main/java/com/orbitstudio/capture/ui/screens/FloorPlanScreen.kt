package com.orbitstudio.capture.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.orbitstudio.capture.data.FeatureType
import com.orbitstudio.capture.data.FloorPlan
import com.orbitstudio.capture.data.PlanFeature
import com.orbitstudio.capture.data.PlanExport
import com.orbitstudio.capture.data.PlanMath
import com.orbitstudio.capture.data.PlanRoom
import com.orbitstudio.capture.data.Plans
import com.orbitstudio.capture.data.ScanStatus
import com.orbitstudio.capture.data.Scans
import com.orbitstudio.capture.ui.sketch.DimensionCapsule
import com.orbitstudio.capture.ui.sketch.PlaceHintPill
import com.orbitstudio.capture.ui.sketch.STAMP_GROUPS
import com.orbitstudio.capture.ui.sketch.ScaleByReferenceDialog
import com.orbitstudio.capture.ui.sketch.ScaleChipButton
import com.orbitstudio.capture.ui.sketch.SketchDrawerPanel
import com.orbitstudio.capture.ui.sketch.SketchIconButton
import com.orbitstudio.capture.ui.sketch.SketchPrimaryButton
import com.orbitstudio.capture.ui.sketch.SketchToolButton
import com.orbitstudio.capture.ui.sketch.SketchViewport
import com.orbitstudio.capture.ui.sketch.StampPaletteBody
import com.orbitstudio.capture.ui.sketch.FeaturePropertiesBody
import com.orbitstudio.capture.ui.sketch.drawSketch
import com.orbitstudio.capture.ui.theme.PlexMono
import com.orbitstudio.capture.ui.theme.PlexSans
import com.orbitstudio.capture.ui.theme.SketchColors
import com.orbitstudio.capture.ui.theme.SketchPalette
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Home Sketch editor — Bridge Sketch's tactile drafting language on a phone, scoped to what
// the reconstruction pipeline can use: rooms, walls, windows, doors, obstacles, plus the walk
// path. Furniture and inspection tools are deliberately out of scope (see the design debate).
private enum class SketchTool(
    val glyph: String,
    val caption: String,
    val hint: String,
    val defaultVariant: String,
) {
    SELECT("◈", "Select", "Tap to select. Drag to move, corner grip to resize, top grip to rotate. Long-press for more.", ""),
    ROOM("▦", "Room", "Drag to draw a room.", ""),
    WALL("▬", "Wall", "Drag inside a room to add a wall.", "wall"),
    WINDOW("▭", "Window", "Tap a room edge to place a window.", "standard"),
    DOOR("⊢", "Door", "Tap a room edge to place a door.", "single"),
    OBSTACLE("⊠", "Obstacle", "Drag inside a room to mark an obstacle.", "generic"),
    ERASE("✕", "Erase", "Tap an item to remove it.", ""),
    PAN("✋", "Pan", "Drag to move the sheet.", ""),
}

private enum class DrawerMode { NONE, STAMPS, PROPS }

@Composable
fun FloorPlanScreen(nav: NavController, planId: String) {
    val context = LocalContext.current
    // Light "engineering paper" palette — same sheet/ink/accent tokens the Bridge layout
    // tool draws with, so the room sketch reads as the same drafting surface.
    val pal = SketchColors.palette(dark = false)
    val repo = remember { Plans.repo(context) }
    val scope = rememberCoroutineScope()

    var plan by remember { mutableStateOf<FloorPlan?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(SketchTool.ROOM) }
    var variant by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showPath by remember { mutableStateOf(false) }
    var previewRect by remember { mutableStateOf<IntArray?>(null) }
    var drawer by remember { mutableStateOf(DrawerMode.NONE) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var pendingRoomRect by remember { mutableStateOf<IntArray?>(null) }
    var scaleDialog by remember { mutableStateOf(false) }
    // Start-scan setup: pick where you stand and what you face before the walk.
    var startSetupRoom by remember { mutableStateOf<PlanRoom?>(null) }

    // View transform. Cells are a FIXED on-screen size (see SketchCanvasArea), not fit-to-grid
    // — fitting a huge grid was what made cells microscopic. Zoom 1 shows ~one room; pinch out
    // to see the whole floor, in for detail.
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    // Selected stamp's real size, for tap-placed fixed-size objects (furniture). 0 = the
    // active tool is drag-drawn or edge-snapped, not a fixed stamp.
    var stampWM by remember { mutableFloatStateOf(0f) }
    var stampHM by remember { mutableFloatStateOf(0f) }

    // Copy/paste + context menu.
    var clipboard by remember { mutableStateOf<ClipItem?>(null) }
    var contextFor by remember { mutableStateOf<String?>(null) }
    // Best-fit: bump fitToken to reframe the view; fitTarget is the cell bbox to frame (null = all).
    var fitToken by remember { mutableIntStateOf(0) }
    var fitTarget by remember { mutableStateOf<IntArray?>(null) }

    // Undo / redo — snapshots of the whole plan.
    val undoStack = remember { mutableStateListOf<FloorPlan>() }
    val redoStack = remember { mutableStateListOf<FloorPlan>() }

    LaunchedEffect(planId) {
        runCatching { repo.getPlan(planId) ?: repo.createPlan("My building") }
            .onSuccess { plan = it }.onFailure { loadError = true }
    }
    LaunchedEffect(feedback) {
        if (feedback != null) { delay(1400); feedback = null }
    }

    fun commit(updated: FloorPlan) {
        val prev = plan
        // Every drag-commit and rename keystroke lands here; writing plans.json
        // synchronously blocked the main thread on each one. State updates
        // optimistically right away, and the write moves to Dispatchers.IO like
        // CaptureScreen's shot-save — a failure only surfaces as feedback, it
        // doesn't roll back the sketch.
        if (prev != null) {
            undoStack.add(prev)
            if (undoStack.size > 40) undoStack.removeAt(0)
            redoStack.clear()
        }
        plan = updated
        scope.launch(Dispatchers.IO) {
            runCatching { repo.updatePlan(updated) }
                .onFailure { feedback = "Could not save. Try again." }
        }
    }
    fun undo() {
        val target = undoStack.removeLastOrNull() ?: return
        plan?.let { redoStack.add(it) }
        plan = target
        selectedId = null
        scope.launch(Dispatchers.IO) { runCatching { repo.updatePlan(target) } }
    }
    fun redo() {
        val target = redoStack.removeLastOrNull() ?: return
        plan?.let { undoStack.add(it) }
        plan = target
        scope.launch(Dispatchers.IO) { runCatching { repo.updatePlan(target) } }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(pal.bg).safeDrawingPadding(),
    ) {
        SketchTopBar(
            pal = pal,
            plan = plan,
            selectedRoom = plan?.rooms?.find { it.id == selectedId },
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
            onBack = { nav.popBackStack() },
            onUndo = ::undo,
            onRedo = ::redo,
            onExport = {
                plan?.let { p ->
                    PlanExport.exportAndShare(context, p) { status -> feedback = status }
                }
            },
            onScaleTap = {
                val p = plan
                val room = p?.rooms?.find { it.id == selectedId }
                if (p != null && room != null) {
                    scaleDialog = true
                } else if (p != null) {
                    val next = when (p.scaleMPerCell) { 0.25f -> 0.5f; 0.5f -> 1.0f; else -> 0.25f }
                    commit(p.copy(scaleMPerCell = next))
                    feedback = "1 cell = " + scaleLabel(next)
                }
            },
            onScaleLongPress = {
                plan?.let { p ->
                    val next = when (p.scaleMPerCell) { 0.25f -> 0.5f; 0.5f -> 1.0f; else -> 0.25f }
                    commit(p.copy(scaleMPerCell = next))
                    feedback = "1 cell = " + scaleLabel(next)
                }
            },
        )

        if (loadError) {
            SketchMessage(pal, "The plan file could not be read. Go back and try again.")
        } else if (plan == null) {
            SketchMessage(pal, "Preparing the sheet.")
        } else {
            val p = plan!!
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                SketchCanvasArea(
                    plan = p,
                    pal = pal,
                    tool = tool,
                    zoom = zoom,
                    pan = pan,
                    selectedId = selectedId,
                    pathCells = if (showPath) {
                        // previewRect updates on every pointer-move sample during a drag,
                        // recomposing this whole scope — remember so the walk only
                        // re-solves when the room/features/scale it's drawn from change.
                        p.rooms.find { it.id == selectedId }?.let { room ->
                            remember(room, p.features, p.scaleMPerCell) {
                                PlanMath.walkPath(room, p.features, p.scaleMPerCell)
                            }
                        }
                    } else null,
                    previewRect = previewRect,
                    isRubberBand = tool == SketchTool.ROOM || tool == SketchTool.WALL ||
                        (tool == SketchTool.OBSTACLE && stampWM <= 0f),
                    selectedMoveRect = (p.rooms.find { it.id == selectedId }
                        ?.let { intArrayOf(it.col, it.row, it.cols, it.rows) }
                        ?: p.features.find { it.id == selectedId }
                            ?.let { intArrayOf(it.col, it.row, it.cols, it.rows) }),
                    fitToken = fitToken,
                    fitTarget = fitTarget,
                    onFit = { z, pn -> zoom = z; pan = pn },
                    onPreview = { previewRect = it },
                    onTransform = { centroid, panDelta, zoomChange ->
                        val newZoom = (zoom * zoomChange).coerceIn(0.15f, 16f)
                        val applied = newZoom / zoom
                        pan = Offset(
                            centroid.x - (centroid.x - pan.x) * applied + panDelta.x,
                            centroid.y - (centroid.y - pan.y) * applied + panDelta.y,
                        )
                        zoom = newZoom
                    },
                    onTap = { col, row ->
                        contextFor = null
                        handleTap(
                            p, tool, variant, col, row, p.scaleMPerCell, stampWM, stampHM,
                            onCommit = { updated, note -> commit(updated); if (note != null) feedback = note },
                            // Select inline and show edit handles on the canvas (Bridge-style):
                            // no drawer pops up, so the object is immediately movable/resizable.
                            // Properties open via the long-press context bar's Edit action.
                            onSelect = { id -> selectedId = id; drawer = DrawerMode.NONE; showPath = false },
                            onNote = { feedback = it },
                        )
                    },
                    onDoubleTap = { col, row ->
                        // Double-tap a room to frame it; double-tap empty to fit everything.
                        val room = roomAt(p, col, row)
                        fitTarget = room?.let { intArrayOf(it.col, it.row, it.cols, it.rows) }
                        fitToken++
                    },
                    onLongPress = { col, row ->
                        val tol = max(1, Math.round(0.3f / p.scaleMPerCell))
                        val feature = nearestFeatureWithin(p, col, row, tol)
                        val room = if (feature == null) roomAt(p, col, row) else null
                        when {
                            feature != null -> { selectedId = feature.id; contextFor = feature.id; drawer = DrawerMode.NONE }
                            room != null -> { selectedId = room.id; contextFor = room.id; drawer = DrawerMode.NONE }
                            clipboard != null -> {
                                val pasted = pasteAt(p, clipboard!!, col, row)
                                if (pasted != null) { commit(pasted); feedback = "Pasted." } else feedback = "5 rooms is the limit."
                            }
                            else -> feedback = "Long-press an item to copy it."
                        }
                    },
                    onDragCommit = { rect ->
                        previewRect = null
                        handleDragCommit(
                            p, tool, variant, rect, p.scaleMPerCell,
                            onCommit = { updated, note -> commit(updated); if (note != null) feedback = note },
                            onNewRoom = { pendingRoomRect = it },
                            onNote = { feedback = it },
                        )
                    },
                    onMoveCommit = { rect ->
                        previewRect = null
                        val room = p.rooms.find { it.id == selectedId }
                        val f = p.features.find { it.id == selectedId }
                        if (room != null) {
                            // Move the whole room, clamped inside the grid.
                            val c0 = rect[0].coerceIn(0, max(0, p.gridCols - room.cols))
                            val r0 = rect[1].coerceIn(0, max(0, p.gridRows - room.rows))
                            commit(p.copy(rooms = p.rooms.map {
                                if (it.id == room.id) it.copy(col = c0, row = r0) else it
                            }))
                            feedback = "Moved."
                        } else if (f != null) {
                            if (f.type == FeatureType.WINDOW || f.type == FeatureType.DOOR) {
                                // Openings live on edges: a move re-snaps onto the nearest
                                // room edge at the drop point, keeping identity + variant.
                                val cC = rect[0] + rect[2] / 2
                                val cR = rect[1] + rect[3] / 2
                                val placed = placeOnNearestEdge(p, cC, cR, f.type, f.variant, p.scaleMPerCell)
                                if (placed == null) feedback = "Drop it near a room edge."
                                else {
                                    commit(p.copy(features = p.features.map {
                                        if (it.id == f.id) placed.copy(id = f.id) else it
                                    }))
                                    feedback = "Snapped to the edge."
                                }
                            } else {
                                val cC = rect[0] + rect[2] / 2
                                val cR = rect[1] + rect[3] / 2
                                val room = roomAt(p, cC, cR)
                                val tol = max(1, Math.round(0.35f / p.scaleMPerCell))
                                val o = PlanMath.snapMovedRect(
                                    room, p.features.filterNot { it.id == f.id }, f.type,
                                    rect[0], rect[1], rect[2], rect[3], tol,
                                )
                                commit(p.copy(features = p.features.map {
                                    if (it.id == f.id) it.copy(col = o.col, row = o.row, cols = o.cols, rows = o.rows, horizontal = o.cols >= o.rows) else it
                                }))
                                feedback = if (o.snapped) "Snapped to the wall." else "Moved."
                            }
                        }
                    },
                    onResizeCommit = { rect ->
                        previewRect = null
                        val room = p.rooms.find { it.id == selectedId }
                        val f = p.features.find { it.id == selectedId }
                        if (room != null) {
                            val minCells = max(1, Math.round(0.5f / p.scaleMPerCell))
                            val cols = rect[2].coerceIn(minCells, p.gridCols - room.col)
                            val rows = rect[3].coerceIn(minCells, p.gridRows - room.row)
                            commit(p.copy(rooms = p.rooms.map {
                                if (it.id == room.id) it.copy(cols = cols, rows = rows) else it
                            }))
                            feedback = String.format(Locale.US, "%.2f x %.2f m", cols * p.scaleMPerCell, rows * p.scaleMPerCell)
                        } else if (f != null && f.type != FeatureType.WINDOW && f.type != FeatureType.DOOR) {
                            // Walls/obstacles resize freely (>=1 cell); openings keep their
                            // edge-snapped span and are resized via variant instead.
                            val cols = rect[2].coerceIn(1, p.gridCols - f.col)
                            val rows = rect[3].coerceIn(1, p.gridRows - f.row)
                            commit(p.copy(features = p.features.map {
                                if (it.id == f.id) it.copy(cols = cols, rows = rows, horizontal = cols >= rows) else it
                            }))
                            feedback = "Resized."
                        } else if (f != null) {
                            feedback = "Windows and doors resize by variant."
                        }
                    },
                    onRotate = {
                        // 90-degree flip: swap width/height about the top-left. Free rotation
                        // would break the axis-aligned grid the walk path + bundle assume, so
                        // rotate is a quarter-turn only.
                        val f = p.features.find { it.id == selectedId }
                        val room = p.rooms.find { it.id == selectedId }
                        when {
                            f != null && f.type != FeatureType.WINDOW && f.type != FeatureType.DOOR -> {
                                val cols = f.rows.coerceAtMost(p.gridCols - f.col)
                                val rows = f.cols.coerceAtMost(p.gridRows - f.row)
                                commit(p.copy(features = p.features.map {
                                    if (it.id == f.id) it.copy(cols = cols, rows = rows, horizontal = cols >= rows) else it
                                }))
                                feedback = "Rotated 90°."
                            }
                            room != null -> {
                                val cols = room.rows.coerceAtMost(p.gridCols - room.col)
                                val rows = room.cols.coerceAtMost(p.gridRows - room.row)
                                commit(p.copy(rooms = p.rooms.map {
                                    if (it.id == room.id) it.copy(cols = cols, rows = rows) else it
                                }))
                                feedback = "Rotated 90°."
                            }
                            else -> feedback = "Windows and doors can't rotate."
                        }
                    },
                )

                ToolRail(
                    pal = pal,
                    selected = tool,
                    onSelect = {
                        tool = it
                        variant = it.defaultVariant
                        stampWM = 0f; stampHM = 0f
                        previewRect = null
                        if (it != SketchTool.SELECT) { selectedId = null; drawer = DrawerMode.NONE }
                        feedback = it.caption
                    },
                    onStamps = { drawer = if (drawer == DrawerMode.STAMPS) DrawerMode.NONE else DrawerMode.STAMPS },
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                )

                PlaceHintPill(
                    pal = pal,
                    text = feedback ?: tool.hint,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                )

                ScaleChipButton(
                    pal = pal,
                    label = "1 cell = " + scaleLabel(p.scaleMPerCell),
                    onClick = {
                        val room = p.rooms.find { it.id == selectedId }
                        if (room != null) scaleDialog = true else {
                            val next = when (p.scaleMPerCell) { 0.25f -> 0.5f; 0.5f -> 1.0f; else -> 0.25f }
                            commit(p.copy(scaleMPerCell = next)); feedback = "1 cell = " + scaleLabel(next)
                        }
                    },
                    onLongPress = {
                        val next = when (p.scaleMPerCell) { 0.25f -> 0.5f; 0.5f -> 1.0f; else -> 0.25f }
                        commit(p.copy(scaleMPerCell = next)); feedback = "1 cell = " + scaleLabel(next)
                    },
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                )

                // Fit-to-view button (double-tap empty space does the same thing).
                SketchIconButton(
                    pal = pal,
                    glyph = "⊡",
                    onClick = { fitTarget = null; fitToken++ },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                )

                // The one big obvious way in: no hunting for a room tap. Targets the
                // selected room, else the first room that still needs a scan. New scans
                // route through the start-position picker first.
                if (p.rooms.isNotEmpty() && contextFor == null && drawer == DrawerMode.NONE) {
                    val target = p.rooms.find { it.id == selectedId }
                        ?: p.rooms.firstOrNull { r -> r.scanId?.let { Scans.repo.getScan(it)?.status } == null }
                        ?: p.rooms.first()
                    val status = target.scanId?.let { Scans.repo.getScan(it)?.status }
                    val label = when (status) {
                        null -> "Start scan: " + target.name
                        ScanStatus.IN_PROGRESS -> "Resume scan: " + target.name
                        ScanStatus.REVIEWED -> "Open bundle: " + target.name
                        ScanStatus.BUNDLED, ScanStatus.DONE -> "Open result: " + target.name
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .fillMaxWidth(0.64f),
                    ) {
                        SketchPrimaryButton(pal, label, onClick = {
                            if (status == null) startSetupRoom = target
                            else startScanForRoom(nav, p, target) { commit(it) }
                        })
                    }
                }

                // Long-press context menu for the touched object.
                contextFor?.let { id ->
                    ContextActionBar(
                        pal = pal,
                        onEdit = {
                            selectedId = id
                            drawer = DrawerMode.PROPS
                            contextFor = null
                        },
                        onCopy = {
                            clipboard = clipItemFor(p, id)
                            contextFor = null
                            feedback = "Copied. Long-press empty space to paste."
                        },
                        onDuplicate = {
                            val clip = clipItemFor(p, id)
                            if (clip != null) {
                                val src = p.rooms.find { it.id == id }
                                val srcF = p.features.find { it.id == id }
                                val col = (src?.col ?: srcF?.col ?: 0) + 10
                                val row = (src?.row ?: srcF?.row ?: 0) + 10
                                pasteAt(p, clip, col, row)?.let { commit(it); feedback = "Duplicated." } ?: run { feedback = "5 rooms is the limit." }
                            }
                            contextFor = null
                        },
                        onDelete = {
                            commit(p.copy(rooms = p.rooms.filterNot { it.id == id }, features = p.features.filterNot { it.id == id }))
                            if (selectedId == id) selectedId = null
                            contextFor = null
                            feedback = "Deleted."
                        },
                        onCancel = { contextFor = null },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                    )
                }

                // Right-side drawer: stamps or properties.
                if (drawer != DrawerMode.NONE) {
                    val room = p.rooms.find { it.id == selectedId }
                    val feature = p.features.find { it.id == selectedId }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                val wasProps = drawer == DrawerMode.PROPS
                                drawer = DrawerMode.NONE
                                if (wasProps) selectedId = null
                            }
                            .background(Color.Black.copy(alpha = 0.32f)),
                    )
                    SketchDrawerPanel(
                        pal = pal,
                        title = when (drawer) {
                            DrawerMode.STAMPS -> "Stamps"
                            else -> if (room != null) "Room" else "Item"
                        },
                        onClose = { val wasProps = drawer == DrawerMode.PROPS; drawer = DrawerMode.NONE; if (wasProps) selectedId = null },
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.82f),
                    ) {
                        when {
                            drawer == DrawerMode.STAMPS -> StampPaletteBody(pal, variant) { stamp ->
                                tool = toolFor(stamp.type)
                                variant = stamp.variant
                                stampWM = stamp.wM
                                stampHM = stamp.hM
                                selectedId = null
                                drawer = DrawerMode.NONE
                                feedback = "Placing: " + stamp.label
                            }
                            room != null -> RoomDrawerBody(
                                pal, p, room, showPath,
                                onTogglePath = { showPath = it },
                                onRename = { name -> commit(p.copy(rooms = p.rooms.map { if (it.id == room.id) it.copy(name = name) else it })) },
                                onDelete = { commit(p.copy(rooms = p.rooms.filterNot { it.id == room.id })); selectedId = null; drawer = DrawerMode.NONE; feedback = "Room removed." },
                                onStartScan = { startScanForRoom(nav, p, room) { commit(it) } },
                            )
                            feature != null -> FeaturePropertiesBody(
                                pal, feature.type, feature.variant, STAMP_GROUPS.flatMap { it.second }.filter { it.type == feature.type },
                                onVariant = { v -> commit(p.copy(features = p.features.map { if (it.id == feature.id) it.copy(variant = v) else it })) },
                                onDelete = { commit(p.copy(features = p.features.filterNot { it.id == feature.id })); selectedId = null; drawer = DrawerMode.NONE; feedback = "Removed." },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingRoomRect?.let { rect ->
        NameRoomDialog(
            pal = pal,
            defaultName = "Room " + ((plan?.rooms?.size ?: 0) + 1),
            onConfirm = { name ->
                plan?.let { p ->
                    val room = PlanRoom(UUID.randomUUID().toString(), name.ifBlank { "Room" }, rect[0], rect[1], rect[2], rect[3], null)
                    commit(p.copy(rooms = p.rooms + room)); feedback = "Saved: " + room.name
                }
                pendingRoomRect = null
            },
            onDismiss = { pendingRoomRect = null },
        )
    }

    startSetupRoom?.let { room ->
        plan?.let { p ->
            StartScanSetupDialog(
                pal = pal,
                plan = p,
                room = room,
                onConfirm = { cell, dirDeg ->
                    val updated = p.copy(rooms = p.rooms.map {
                        if (it.id == room.id) it.copy(startCol = cell.first, startRow = cell.second, startDirDeg = dirDeg) else it
                    })
                    commit(updated)
                    startSetupRoom = null
                    startScanForRoom(nav, updated, updated.rooms.first { it.id == room.id }) { commit(it) }
                },
                onDismiss = { startSetupRoom = null },
            )
        }
    }

    if (scaleDialog) {
        val room = plan?.rooms?.find { it.id == selectedId }
        if (room == null) scaleDialog = false else {
            ScaleByReferenceDialog(
                pal = pal,
                currentMPerCell = plan!!.scaleMPerCell,
                referenceRoomWidthCells = room.cols,
                onConfirm = { m -> plan?.let { commit(it.copy(scaleMPerCell = m)) }; feedback = "1 cell = " + scaleLabel(m); scaleDialog = false },
                onDismiss = { scaleDialog = false },
            )
        }
    }
}

@Composable
private fun SketchTopBar(
    pal: SketchPalette,
    plan: FloorPlan?,
    selectedRoom: PlanRoom?,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit,
    onScaleTap: () -> Unit,
    onScaleLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(pal.panel)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) {
            Text("Back", fontFamily = PlexSans, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.accent)
        }
        Spacer(Modifier.width(2.dp))
        Text(
            "Plan the walk",
            fontFamily = PlexSans,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.15).sp,
            color = pal.ink,
        )
        Spacer(Modifier.weight(1f))
        Text(
            String.format(Locale.US, "%d/5", plan?.rooms?.size ?: 0),
            fontFamily = PlexMono,
            fontSize = 11.sp,
            color = pal.muted,
            modifier = Modifier.padding(end = 8.dp),
        )
        // Export the drawing as a .json that rides alongside the capture.
        SketchIconButton(pal, "⇪", onClick = onExport)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.alpha(if (canUndo) 1f else 0.35f)) {
            SketchIconButton(pal, "↶", onClick = { if (canUndo) onUndo() })
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.alpha(if (canRedo) 1f else 0.35f)) {
            SketchIconButton(pal, "↷", onClick = { if (canRedo) onRedo() })
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
private fun SketchMessage(pal: SketchPalette, text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, fontFamily = PlexSans, fontSize = 14.sp, color = pal.muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ToolRail(
    pal: SketchPalette,
    selected: SketchTool,
    onSelect: (SketchTool) -> Unit,
    onStamps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SketchTool.entries.forEach { t ->
            SketchToolButton(pal, t.glyph, t.caption, selected = t == selected, onClick = { onSelect(t) })
        }
        SketchToolButton(pal, "⊞", "Stamps", selected = false, onClick = onStamps)
    }
}

@Composable
private fun SketchCanvasArea(
    plan: FloorPlan,
    pal: SketchPalette,
    tool: SketchTool,
    zoom: Float,
    pan: Offset,
    selectedId: String?,
    pathCells: List<Pair<Int, Int>>?,
    previewRect: IntArray?,
    isRubberBand: Boolean,
    // The selected room-or-feature rect (col,row,cols,rows). With the SELECT tool: a drag
    // starting inside it MOVES the object, a drag from the bottom-right grip RESIZES it, and
    // a tap on the rotate grip flips it. Null = nothing selected/editable.
    selectedMoveRect: IntArray?,
    fitToken: Int,
    fitTarget: IntArray?,
    onFit: (Float, Offset) -> Unit,
    onPreview: (IntArray?) -> Unit,
    onTransform: (centroid: Offset, panDelta: Offset, zoomChange: Float) -> Unit,
    onTap: (Int, Int) -> Unit,
    onDoubleTap: (Int, Int) -> Unit,
    onLongPress: (Int, Int) -> Unit,
    onDragCommit: (IntArray) -> Unit,
    onMoveCommit: (IntArray) -> Unit,
    onResizeCommit: (IntArray) -> Unit,
    onRotate: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        // Fixed on-screen cell size (4dp at zoom 1) — independent of grid dimensions, so a huge
        // grid stays a huge PANNABLE canvas with visible squares instead of a microscopic fit.
        val baseCell = with(density) { 4.dp.toPx() }
        val cellPx = baseCell * zoom

        // Best-fit: on first show and whenever fitToken changes, frame fitTarget (or all rooms,
        // or a sensible default region for an empty plan) so the grid fills the screen.
        LaunchedEffect(fitToken, wPx, hPx) {
            if (wPx <= 0f || hPx <= 0f) return@LaunchedEffect
            val bbox = fitTarget ?: if (plan.rooms.isEmpty()) {
                intArrayOf(0, 0, 120, 160)
            } else {
                val minC = plan.rooms.minOf { it.col }
                val minR = plan.rooms.minOf { it.row }
                val maxC = plan.rooms.maxOf { it.col + it.cols }
                val maxR = plan.rooms.maxOf { it.row + it.rows }
                intArrayOf(minC, minR, maxC - minC, maxR - minR)
            }
            val pad = max(6, (max(bbox[2], bbox[3]) * 0.08f).toInt())
            val bc = bbox[0] - pad
            val br = bbox[1] - pad
            val bw = (bbox[2] + pad * 2).coerceAtLeast(1)
            val bh = (bbox[3] + pad * 2).coerceAtLeast(1)
            val z = min(wPx / (bw * baseCell), hPx / (bh * baseCell)).coerceIn(0.15f, 16f)
            val panX = (wPx - bw * baseCell * z) / 2f - bc * baseCell * z
            val panY = (hPx - bh * baseCell * z) / 2f - br * baseCell * z
            onFit(z, Offset(panX, panY))
        }

        // The gesture loop runs across many recompositions; read the latest pan/zoom
        // through updated-state so pinching mid-gesture stays correct without restarting.
        val zoomState = rememberUpdatedState(zoom)
        val panState = rememberUpdatedState(pan)
        val moveRectState = rememberUpdatedState(selectedMoveRect)
        fun liveCellOf(o: Offset): Pair<Int, Int> {
            val cp = baseCell * zoomState.value
            val p = panState.value
            return Pair(
                ((o.x - p.x) / cp).toInt().coerceIn(0, plan.gridCols - 1),
                ((o.y - p.y) / cp).toInt().coerceIn(0, plan.gridRows - 1),
            )
        }
        // Screen-space positions of the selected object's edit grips, for hit-testing.
        val rotateGripPx = with(density) { 22.dp.toPx() }
        val gripGrab = with(density) { 26.dp.toPx() }
        fun resizeGripAt(): Offset? {
            val mr = moveRectState.value ?: return null
            val cp = baseCell * zoomState.value
            val p = panState.value
            return Offset((mr[0] + mr[2]) * cp + p.x, (mr[1] + mr[3]) * cp + p.y)
        }
        fun rotateGripAt(): Offset? {
            val mr = moveRectState.value ?: return null
            val cp = baseCell * zoomState.value
            val p = panState.value
            return Offset((mr[0] + mr[2] / 2f) * cp + p.x, mr[1] * cp + p.y - rotateGripPx)
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // Taps, double-taps and long-presses (built-in detector, correct timing).
                // Tap = place/select, double-tap = fit-to-view, long-press = context menu.
                // A tap on the rotate grip flips the selected object instead of selecting.
                .pointerInput(tool, baseCell) {
                    detectTapGestures(
                        onTap = { o ->
                            val grip = if (tool == SketchTool.SELECT) rotateGripAt() else null
                            if (grip != null && (o - grip).getDistance() <= gripGrab) {
                                onRotate()
                            } else {
                                val (c, r) = liveCellOf(o); onTap(c, r)
                            }
                        },
                        onDoubleTap = { o -> val (c, r) = liveCellOf(o); onDoubleTap(c, r) },
                        onLongPress = { o -> val (c, r) = liveCellOf(o); onLongPress(c, r) },
                    )
                }
                // Drag + pinch. Two fingers = pinch-zoom + pan. One finger: rubber-band tools
                // (room/wall/generic obstacle) draw a rectangle; every other tool pans the
                // canvas (maps-style) — so a stray drag never places or moves an object.
                .pointerInput(tool, baseCell, isRubberBand) {
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        val slop = viewConfiguration.touchSlop
                        val startCell = liveCellOf(first.position)
                        // SELECT-tool drags on the selected object: from the corner grip =
                        // RESIZE, from inside = MOVE, else pan. Grips are hit-tested in
                        // screen space so they stay grabbable at any zoom.
                        val moveRect = moveRectState.value
                        val onSelect = tool == SketchTool.SELECT && moveRect != null
                        val isResize = onSelect &&
                            resizeGripAt()?.let { (first.position - it).getDistance() <= gripGrab } == true
                        // onSelect already carries moveRect != null, so the compiler
                        // keeps the smart cast without repeating the check here.
                        val isMove = onSelect && !isResize &&
                            startCell.first in (moveRect[0] - 1)..(moveRect[0] + moveRect[2]) &&
                            startCell.second in (moveRect[1] - 1)..(moveRect[1] + moveRect[3])
                        var isTransform = false
                        var isDrag = false
                        var curRect: IntArray? = null
                        var prevCentroid = first.position
                        var prevDist = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                val c = averageOffset(pressed.map { it.position })
                                val d = averageDistance(pressed.map { it.position }, c)
                                if (!isTransform) {
                                    isTransform = true
                                    onPreview(null); curRect = null
                                    prevCentroid = c; prevDist = d
                                } else {
                                    val zc = if (prevDist > 0.01f) d / prevDist else 1f
                                    onTransform(c, Offset(c.x - prevCentroid.x, c.y - prevCentroid.y), zc)
                                    prevCentroid = c; prevDist = d
                                }
                                pressed.forEach { it.consume() }
                            } else if (!isTransform) {
                                val ch = pressed.first()
                                if (!isDrag && (ch.position - first.position).getDistance() > slop) isDrag = true
                                if (isDrag) {
                                    if (isResize) {
                                        // Grow/shrink from the fixed top-left corner.
                                        val cc = liveCellOf(ch.position)
                                        val rect = intArrayOf(
                                            moveRect[0], moveRect[1],
                                            (cc.first - moveRect[0] + 1).coerceAtLeast(1),
                                            (cc.second - moveRect[1] + 1).coerceAtLeast(1),
                                        )
                                        curRect = rect
                                        onPreview(rect)
                                    } else if (isMove) {
                                        val cc = liveCellOf(ch.position)
                                        val rect = intArrayOf(
                                            moveRect[0] + (cc.first - startCell.first),
                                            moveRect[1] + (cc.second - startCell.second),
                                            moveRect[2], moveRect[3],
                                        )
                                        curRect = rect
                                        onPreview(rect)
                                    } else if (isRubberBand) {
                                        val cc = liveCellOf(ch.position)
                                        val rect = intArrayOf(
                                            min(startCell.first, cc.first), min(startCell.second, cc.second),
                                            abs(cc.first - startCell.first) + 1, abs(cc.second - startCell.second) + 1,
                                        )
                                        curRect = rect
                                        onPreview(rect)
                                    } else {
                                        onTransform(ch.position, ch.positionChange(), 1f)
                                    }
                                    ch.consume()
                                }
                            }
                        }
                        if (isTransform) onPreview(null)
                        else if (isDrag && isResize) { onPreview(null); curRect?.let(onResizeCommit) }
                        else if (isDrag && isMove) { onPreview(null); curRect?.let(onMoveCommit) }
                        else if (isDrag && isRubberBand) curRect?.let(onDragCommit)
                    }
                },
        ) {
            drawSketch(
                plan, SketchViewport(cellPx, pan.x, pan.y), pal, selectedId, pathCells, previewRect,
                showGrid = true,
                showHandles = tool == SketchTool.SELECT && selectedMoveRect != null,
            )
        }

        // Room name labels (the renderer leaves text to the screen).
        plan.rooms.forEach { room ->
            val cxPx = (room.col + room.cols / 2f) * cellPx + pan.x
            val cyPx = (room.row + room.rows / 2f) * cellPx + pan.y
            val cx = with(density) { cxPx.toDp() }
            val cy = with(density) { cyPx.toDp() }
            Text(
                text = room.name,
                fontFamily = PlexSans,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = pal.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = cx - 60.dp, y = cy - 8.dp)
                    .width(120.dp),
            )
        }
    }
}


// ---- Room drawer body (walk path + estimates + start scan) --------------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.RoomDrawerBody(
    pal: SketchPalette,
    plan: FloorPlan,
    room: PlanRoom,
    showPath: Boolean,
    onTogglePath: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onStartScan: () -> Unit,
) {
    val widthM = room.cols * plan.scaleMPerCell
    val heightM = room.rows * plan.scaleMPerCell
    val areaM2 = widthM * heightM
    val shots = PlanMath.estimatedShots(room, plan.features, plan.scaleMPerCell)
    val minutes = PlanMath.estimatedMinutes(shots)
    val status = room.scanId?.let { Scans.repo.getScan(it)?.status }
    var name by remember(room.id) { mutableStateOf(room.name) }

    Text("NAME", fontFamily = PlexMono, fontSize = 11.sp, color = pal.muted, modifier = Modifier.padding(bottom = 6.dp))
    OutlinedTextField(
        value = name,
        onValueChange = { name = it; onRename(it) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = pal.accent, unfocusedBorderColor = pal.line, cursorColor = pal.accent,
            focusedTextColor = pal.ink, unfocusedTextColor = pal.ink,
        ),
    )
    Spacer(Modifier.height(16.dp))
    Text("DIMENSIONS", fontFamily = PlexMono, fontSize = 11.sp, color = pal.muted, modifier = Modifier.padding(bottom = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DimensionCapsule(pal, String.format(Locale.US, "%.2f", widthM), "m")
        DimensionCapsule(pal, String.format(Locale.US, "%.2f", heightM), "m")
        DimensionCapsule(pal, String.format(Locale.US, "%.1f", areaM2), "m²")
    }
    Spacer(Modifier.height(16.dp))
    Text("COVERAGE (ESTIMATE)", fontFamily = PlexMono, fontSize = 11.sp, color = pal.muted, modifier = Modifier.padding(bottom = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DimensionCapsule(pal, "~$shots", "photos")
        DimensionCapsule(pal, "~$minutes", "min")
    }
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Show walk path", fontFamily = PlexSans, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = pal.ink)
        Switch(
            checked = showPath,
            onCheckedChange = onTogglePath,
            colors = SwitchDefaults.colors(
                checkedTrackColor = pal.accent, checkedThumbColor = pal.accentInk,
                uncheckedTrackColor = pal.chip, uncheckedThumbColor = pal.muted, uncheckedBorderColor = pal.line,
            ),
        )
    }
    Spacer(Modifier.height(16.dp))
    val startLabel = when (status) {
        null -> "Start scan"
        ScanStatus.IN_PROGRESS -> "Resume scan"
        ScanStatus.REVIEWED -> "Open bundle"
        ScanStatus.BUNDLED, ScanStatus.DONE -> "Open result"
    }
    SketchPrimaryButton(pal, startLabel, onClick = onStartScan)
    Spacer(Modifier.height(10.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth().height(44.dp)
            .background(pal.danger, RoundedCornerShape(10.dp))
            .clickable(onClick = onDelete),
        contentAlignment = Alignment.Center,
    ) {
        Text("Delete room", color = Color.White, fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "Deleting keeps any scan you already captured.",
        fontFamily = PlexSans, fontSize = 11.sp, color = pal.muted,
    )
}

/** "Where do you stand, and what do you face?" — first tap places the start dot, second
 *  tap points the facing arrow; the walk path re-plans live from the chosen start. The
 *  facing direction calibrates the capture minimap's arrow to the sketch. */
@Composable
private fun StartScanSetupDialog(
    pal: SketchPalette,
    plan: FloorPlan,
    room: PlanRoom,
    onConfirm: (Pair<Int, Int>, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var startCell by remember {
        mutableStateOf(
            if (room.startCol != null && room.startRow != null) room.startCol!! to room.startRow!! else null,
        )
    }
    var dirDeg by remember { mutableStateOf(room.startDirDeg) }
    val path = remember(startCell) {
        PlanMath.walkPath(room, plan.features, plan.scaleMPerCell, startCell)
    }
    val stations = remember(startCell) {
        com.orbitstudio.capture.capture.PanoSweep.stationIndices(path, plan.scaleMPerCell)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = pal.panel,
        title = {
            Text(
                "Set your start",
                fontFamily = PlexSans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink,
            )
        },
        text = {
            Column {
                Text(
                    if (startCell == null) "Tap where you will stand in ${room.name}."
                    else "Now tap what you will face. The route starts at your dot.",
                    fontFamily = PlexSans, fontSize = 12.sp, color = pal.muted,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .background(pal.bg, RoundedCornerShape(12.dp))
                        .pointerInput(startCell) {
                            detectTapGestures(onTap = { o ->
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val cell = min((w - 32f) / room.cols, (h - 32f) / room.rows)
                                val ox = (w - room.cols * cell) / 2f
                                val oy = (h - room.rows * cell) / 2f
                                val col = room.col + ((o.x - ox) / cell).toInt().coerceIn(0, room.cols - 1)
                                val row = room.row + ((o.y - oy) / cell).toInt().coerceIn(0, room.rows - 1)
                                val sc = startCell
                                if (sc == null) {
                                    startCell = col to row
                                } else {
                                    val dx = (col - sc.first).toFloat()
                                    val dy = (row - sc.second).toFloat()
                                    if (dx != 0f || dy != 0f) {
                                        dirDeg = Math.toDegrees(kotlin.math.atan2(dx, -dy).toDouble()).toFloat()
                                    }
                                }
                            })
                        },
                ) {
                    val cell = min((size.width - 32f) / room.cols, (size.height - 32f) / room.rows)
                    val ox = (size.width - room.cols * cell) / 2f
                    val oy = (size.height - room.rows * cell) / 2f
                    fun px(c: Int, r: Int) = Offset(ox + (c - room.col + 0.5f) * cell, oy + (r - room.row + 0.5f) * cell)

                    drawRect(
                        color = pal.line,
                        topLeft = Offset(ox, oy),
                        size = androidx.compose.ui.geometry.Size(room.cols * cell, room.rows * cell),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                    )
                    if (path.size > 1) {
                        val poly = androidx.compose.ui.graphics.Path()
                        val f = px(path.first().first, path.first().second)
                        poly.moveTo(f.x, f.y)
                        path.drop(1).forEach { (c, r) -> val q = px(c, r); poly.lineTo(q.x, q.y) }
                        drawPath(poly, color = pal.muted.copy(alpha = 0.45f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
                    }
                    stations.forEachIndexed { i, idx ->
                        val (c, r) = path[idx]
                        drawCircle(if (i == 0) pal.accent else pal.muted, radius = 10f, center = px(c, r))
                    }
                    startCell?.let { (c, r) ->
                        val at = px(c, r)
                        drawCircle(pal.accent, radius = 14f, center = at)
                        val ang = Math.toRadians(((dirDeg ?: 0f) - 90f).toDouble())
                        val tip = Offset(
                            at.x + (kotlin.math.cos(ang) * 36f).toFloat(),
                            at.y + (kotlin.math.sin(ang) * 36f).toFloat(),
                        )
                        drawLine(pal.accent, at, tip, strokeWidth = 5f)
                        drawCircle(pal.accent, radius = 6f, center = tip)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { startCell?.let { onConfirm(it, dirDeg ?: 0f) } },
                enabled = startCell != null,
            ) {
                Text(
                    "Start scan",
                    color = if (startCell != null) pal.accent else pal.muted,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { startCell = null; dirDeg = null }) { Text("Reset", color = pal.muted) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = pal.muted) }
            }
        },
    )
}

@Composable
private fun NameRoomDialog(pal: SketchPalette, defaultName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = pal.panel,
        title = { Text("Name the room", fontFamily = PlexSans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = pal.accent, unfocusedBorderColor = pal.line, cursorColor = pal.accent,
                    focusedTextColor = pal.ink, unfocusedTextColor = pal.ink,
                ),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("Save", color = pal.accent, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = pal.muted) } },
    )
}

// ---- mutation logic ------------------------------------------------------

private fun toolFor(type: FeatureType): SketchTool = when (type) {
    FeatureType.WALL -> SketchTool.WALL
    FeatureType.WINDOW -> SketchTool.WINDOW
    FeatureType.DOOR -> SketchTool.DOOR
    FeatureType.OBSTACLE -> SketchTool.OBSTACLE
}

private fun scaleLabel(scaleM: Float): String =
    if (scaleM == 0.25f) "0.25 m" else String.format(Locale.US, "%.1f m", scaleM)

private fun handleTap(
    plan: FloorPlan,
    tool: SketchTool,
    variant: String,
    col: Int,
    row: Int,
    scaleMPerCell: Float,
    stampWM: Float,
    stampHM: Float,
    onCommit: (FloorPlan, String?) -> Unit,
    onSelect: (String?) -> Unit,
    onNote: (String) -> Unit,
) {
    when (tool) {
        SketchTool.SELECT -> {
            // Thin doors/windows are one cell wide — grab anything within ~0.3 m of the tap.
            val tol = max(1, Math.round(0.3f / scaleMPerCell))
            val feature = nearestFeatureWithin(plan, col, row, tol)
            onSelect(feature?.id ?: roomAt(plan, col, row)?.id)
        }
        SketchTool.WINDOW, SketchTool.DOOR -> {
            val type = if (tool == SketchTool.WINDOW) FeatureType.WINDOW else FeatureType.DOOR
            val placed = placeOnNearestEdge(plan, col, row, type, variant, scaleMPerCell)
            if (placed == null) onNote("Tap close to a room edge.")
            else onCommit(plan.copy(features = plan.features + placed), if (type == FeatureType.WINDOW) "Window placed." else "Door placed.")
        }
        SketchTool.ERASE -> {
            val tol = max(1, Math.round(0.3f / scaleMPerCell))
            val feature = nearestFeatureWithin(plan, col, row, tol)
            if (feature != null) onCommit(plan.copy(features = plan.features.filterNot { it.id == feature.id }), "Removed.")
            else {
                val room = roomAt(plan, col, row)
                if (room != null) onCommit(plan.copy(rooms = plan.rooms.filterNot { it.id == room.id }), "Room removed.")
                else onNote("Nothing here to remove.")
            }
        }
        SketchTool.OBSTACLE -> {
            // Fixed-size furniture stamp (queen bed, chair, ...) drops centered on the tap;
            // a generic obstacle (no stamp size) is drag-drawn instead, so a tap does nothing.
            if (stampWM > 0f) {
                val wCells = max(1, Math.round(stampWM / scaleMPerCell))
                val hCells = max(1, Math.round(stampHM / scaleMPerCell))
                val col0 = (col - wCells / 2).coerceIn(0, max(0, plan.gridCols - wCells))
                val row0 = (row - hCells / 2).coerceIn(0, max(0, plan.gridRows - hCells))
                onCommit(
                    plan.copy(features = plan.features + PlanFeature(UUID.randomUUID().toString(), FeatureType.OBSTACLE, col0, row0, wCells, hCells, wCells >= hCells, variant)),
                    "Placed.",
                )
            }
        }
        else -> Unit // ROOM/WALL are drag tools; PAN handled by the pan gesture
    }
}

private fun handleDragCommit(
    plan: FloorPlan,
    tool: SketchTool,
    variant: String,
    rect: IntArray,
    scaleMPerCell: Float,
    onCommit: (FloorPlan, String?) -> Unit,
    onNewRoom: (IntArray) -> Unit,
    onNote: (String) -> Unit,
) {
    when (tool) {
        SketchTool.ROOM -> {
            // Minimum room ~0.5 m per side, in meters not cells, so it holds at any grid.
            val minCells = max(1, Math.round(0.5f / scaleMPerCell))
            if (plan.rooms.size >= 5) onNote("5 rooms is the limit.")
            else if (rect[2] < minCells || rect[3] < minCells) onNote("Drag a bigger rectangle.")
            else onNewRoom(rect)
        }
        SketchTool.WALL -> {
            val room = roomAt(plan, rect[0], rect[1]) ?: return onNote("Walls go inside a room.")
            val horizontal = rect[2] >= rect[3]
            val snapped = if (horizontal) intArrayOf(rect[0], rect[1], rect[2], 1) else intArrayOf(rect[0], rect[1], 1, rect[3])
            val clipped = clipToRoom(snapped, room) ?: return onNote("Walls go inside a room.")
            onCommit(
                plan.copy(features = plan.features + PlanFeature(UUID.randomUUID().toString(), FeatureType.WALL, clipped[0], clipped[1], clipped[2], clipped[3], horizontal, "wall")),
                "Wall added.",
            )
        }
        SketchTool.OBSTACLE -> {
            val room = roomAt(plan, rect[0], rect[1]) ?: return onNote("Obstacles go inside a room.")
            val clipped = clipToRoom(rect, room) ?: return onNote("Obstacles go inside a room.")
            onCommit(
                plan.copy(features = plan.features + PlanFeature(UUID.randomUUID().toString(), FeatureType.OBSTACLE, clipped[0], clipped[1], clipped[2], clipped[3], clipped[2] >= clipped[3], variant.ifBlank { "generic" })),
                "Obstacle marked.",
            )
        }
        else -> Unit
    }
}

private fun inRect(col: Int, row: Int, rc: Int, rr: Int, rcols: Int, rrows: Int): Boolean =
    col in rc until rc + rcols && row in rr until rr + rrows

/** Closest feature whose rect is within [tol] cells of (col,row) — Chebyshev distance, 0 if
 *  inside. Later (topmost) features win ties, so thin doors/windows are easy to grab. */
private fun nearestFeatureWithin(plan: FloorPlan, col: Int, row: Int, tol: Int): PlanFeature? {
    var best: PlanFeature? = null
    var bestD = Int.MAX_VALUE
    for (f in plan.features) {
        val dx = when {
            col < f.col -> f.col - col
            col >= f.col + f.cols -> col - (f.col + f.cols - 1)
            else -> 0
        }
        val dy = when {
            row < f.row -> f.row - row
            row >= f.row + f.rows -> row - (f.row + f.rows - 1)
            else -> 0
        }
        val d = max(dx, dy)
        if (d <= tol && d <= bestD) { bestD = d; best = f }
    }
    return best
}

private fun roomAt(plan: FloorPlan, col: Int, row: Int): PlanRoom? =
    plan.rooms.lastOrNull { inRect(col, row, it.col, it.row, it.cols, it.rows) }

private fun clipToRoom(rect: IntArray, room: PlanRoom): IntArray? {
    val c0 = max(rect[0], room.col); val r0 = max(rect[1], room.row)
    val c1 = min(rect[0] + rect[2], room.col + room.cols); val r1 = min(rect[1] + rect[3], room.row + room.rows)
    if (c1 <= c0 || r1 <= r0) return null
    return intArrayOf(c0, r0, c1 - c0, r1 - r0)
}

// Real-world widths converted to cells, so a window is ~1.2 m whether the grid is coarse or fine.
private fun spanFor(type: FeatureType, variant: String, scaleMPerCell: Float): Int {
    val meters = when (type) {
        FeatureType.WINDOW -> when (variant) { "small" -> 0.6f; "bay" -> 1.8f; else -> 1.2f }
        FeatureType.DOOR -> when (variant) { "double" -> 1.5f; else -> 0.9f }
        else -> 0.5f
    }
    return max(1, Math.round(meters / scaleMPerCell))
}

private fun placeOnNearestEdge(plan: FloorPlan, col: Int, row: Int, type: FeatureType, variant: String, scaleMPerCell: Float): PlanFeature? {
    val tol = max(1, Math.round(0.5f / scaleMPerCell)) // "near an edge" = within ~0.5 m
    val room = roomAt(plan, col, row)
        ?: plan.rooms.lastOrNull { col in it.col - tol..it.col + it.cols + tol && row in it.row - tol..it.row + it.rows + tol }
        ?: return null
    val span = spanFor(type, variant, scaleMPerCell)
    val dTop = abs(row - room.row)
    val dBottom = abs(row - (room.row + room.rows - 1))
    val dLeft = abs(col - room.col)
    val dRight = abs(col - (room.col + room.cols - 1))
    val nearest = minOf(dTop, dBottom, dLeft, dRight)
    if (nearest > tol) return null
    return when (nearest) {
        dTop -> edgeH(room, room.row, col, span, type, variant)
        dBottom -> edgeH(room, room.row + room.rows - 1, col, span, type, variant)
        dLeft -> edgeV(room, room.col, row, span, type, variant)
        else -> edgeV(room, room.col + room.cols - 1, row, span, type, variant)
    }
}

private fun edgeH(room: PlanRoom, edgeRow: Int, tapCol: Int, span: Int, type: FeatureType, variant: String) =
    PlanFeature(UUID.randomUUID().toString(), type, (tapCol - span / 2).coerceIn(room.col, room.col + room.cols - span), edgeRow, span, 1, true, variant)

private fun edgeV(room: PlanRoom, edgeCol: Int, tapRow: Int, span: Int, type: FeatureType, variant: String) =
    PlanFeature(UUID.randomUUID().toString(), type, edgeCol, (tapRow - span / 2).coerceIn(room.row, room.row + room.rows - span), 1, span, false, variant)

private fun startScanForRoom(nav: NavController, plan: FloorPlan, room: PlanRoom, save: (FloorPlan) -> Unit) {
    val scanId = room.scanId
    if (scanId == null) {
        val scan = Scans.repo.createScan(room.name)
        save(plan.copy(rooms = plan.rooms.map { if (it.id == room.id) it.copy(scanId = scan.id) else it }))
        nav.navigate("capture/" + scan.id)
        return
    }
    when (Scans.repo.getScan(scanId)?.status) {
        ScanStatus.IN_PROGRESS -> nav.navigate("capture/" + scanId)
        ScanStatus.REVIEWED -> nav.navigate("bundle/" + scanId)
        ScanStatus.BUNDLED, ScanStatus.DONE -> nav.navigate("done/" + scanId)
        null -> {
            val scan = Scans.repo.createScan(room.name)
            save(plan.copy(rooms = plan.rooms.map { if (it.id == room.id) it.copy(scanId = scan.id) else it }))
            nav.navigate("capture/" + scan.id)
        }
    }
}


// ---- pinch/pan gesture math ----------------------------------------------

private fun averageOffset(points: List<Offset>): Offset {
    if (points.isEmpty()) return Offset.Zero
    var x = 0f; var y = 0f
    for (p in points) { x += p.x; y += p.y }
    return Offset(x / points.size, y / points.size)
}

private fun averageDistance(points: List<Offset>, center: Offset): Float {
    if (points.isEmpty()) return 0f
    var sum = 0f
    for (p in points) {
        val dx = p.x - center.x; val dy = p.y - center.y
        sum += sqrt(dx * dx + dy * dy)
    }
    return sum / points.size
}

// ---- copy / paste + context menu -----------------------------------------

private data class ClipItem(
    val isRoom: Boolean,
    val cols: Int,
    val rows: Int,
    val type: FeatureType?,
    val variant: String,
    val horizontal: Boolean,
    val name: String,
)

private fun clipItemFor(plan: FloorPlan, id: String): ClipItem? {
    plan.rooms.find { it.id == id }?.let { return ClipItem(true, it.cols, it.rows, null, "", false, it.name) }
    plan.features.find { it.id == id }?.let { return ClipItem(false, it.cols, it.rows, it.type, it.variant, it.horizontal, "") }
    return null
}

/** Places a copy of [clip] with its top-left at (col,row), clipped to the grid. Rooms respect
 *  the 5-room cap (returns null if full). */
private fun pasteAt(plan: FloorPlan, clip: ClipItem, col: Int, row: Int): FloorPlan? {
    val c0 = col.coerceIn(0, max(0, plan.gridCols - clip.cols))
    val r0 = row.coerceIn(0, max(0, plan.gridRows - clip.rows))
    return if (clip.isRoom) {
        if (plan.rooms.size >= 5) null
        else plan.copy(
            rooms = plan.rooms + PlanRoom(
                UUID.randomUUID().toString(),
                clip.name.ifBlank { "Room" } + " copy",
                c0, r0, clip.cols, clip.rows, null,
            ),
        )
    } else {
        plan.copy(
            features = plan.features + PlanFeature(
                UUID.randomUUID().toString(),
                clip.type ?: FeatureType.OBSTACLE,
                c0, r0, clip.cols, clip.rows, clip.horizontal, clip.variant,
            ),
        )
    }
}

@Composable
private fun ContextActionBar(
    pal: SketchPalette,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(pal.panel, RoundedCornerShape(14.dp))
            .border(1.dp, pal.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        TextButton(onClick = onEdit) { Text("Edit", color = pal.accent, fontFamily = PlexSans, fontSize = 13.sp) }
        TextButton(onClick = onCopy) { Text("Copy", color = pal.accent, fontFamily = PlexSans, fontSize = 13.sp) }
        TextButton(onClick = onDuplicate) { Text("Duplicate", color = pal.accent, fontFamily = PlexSans, fontSize = 13.sp) }
        TextButton(onClick = onDelete) { Text("Delete", color = pal.danger, fontFamily = PlexSans, fontSize = 13.sp) }
        TextButton(onClick = onCancel) { Text("Cancel", color = pal.muted, fontFamily = PlexSans, fontSize = 13.sp) }
    }
}

