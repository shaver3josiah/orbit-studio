package com.orbitstudio.capture.data

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Pure Kotlin (no Android imports) so it's usable from a plain JVM/kotlinc check.
object PlanMath {

    /**
     * Ordered cell centers (absolute grid col,row) for a coverage walk of [room]:
     * a perimeter ring inset ~1m from the walls, sampled every ~0.35m (a shot spacing),
     * detouring around OBSTACLE/WALL cells, followed by an interior lattice (~1m row
     * spacing, boustrophedon), closed by repeating the first ring cell at the end.
     *
     * Meters-aware: [scaleMPerCell] converts the fixed real-world inset/spacing targets
     * into cell counts, so the walk stays physically sensible at any grid resolution.
     */
    fun walkPath(
        room: PlanRoom,
        features: List<PlanFeature>,
        scaleMPerCell: Float,
        // Operator-chosen start (absolute cell): the perimeter ring is rotated to begin
        // at its sample nearest this point, so the route starts where they stand.
        startNear: Pair<Int, Int>? = null,
    ): List<Pair<Int, Int>> {
        val (ring0, lattice) = ringAndLattice(room, features, scaleMPerCell)
        val ring = if (startNear == null || ring0.size < 2) ring0 else {
            var bestI = 0
            var bestD = Int.MAX_VALUE
            ring0.forEachIndexed { i, (c, r) ->
                val d = (c - startNear.first) * (c - startNear.first) + (r - startNear.second) * (r - startNear.second)
                if (d < bestD) { bestD = d; bestI = i }
            }
            ring0.subList(bestI, ring0.size) + ring0.subList(0, bestI)
        }
        val path = mutableListOf<Pair<Int, Int>>()
        path.addAll(ring)
        path.addAll(lattice)
        if (ring.size > 1) path.add(ring.first())
        return path
    }

    /** The perimeter ring and interior lattice of the coverage walk, separately, so the
     *  ring can be rotated to a chosen start before composition. */
    private fun ringAndLattice(
        room: PlanRoom,
        features: List<PlanFeature>,
        scaleMPerCell: Float,
    ): Pair<List<Pair<Int, Int>>, List<Pair<Int, Int>>> {
        if (room.cols < 3 || room.rows < 3) {
            return listOf((room.col + room.cols / 2) to (room.row + room.rows / 2)) to emptyList()
        }

        val cpm = max(1, Math.round(1f / scaleMPerCell)) // cells per metre
        val inset = cpm.coerceIn(1, (min(room.cols, room.rows) - 1) / 2) // ~1m in from the walls
        val step = max(1, Math.round(0.35f / scaleMPerCell)) // ~0.35m shot spacing
        val latticeStep = max(step, cpm) // ~1m between interior passes

        val innerColMin = room.col + inset
        val innerColMax = room.col + room.cols - 1 - inset
        val innerRowMin = room.row + inset
        val innerRowMax = room.row + room.rows - 1 - inset
        if (innerColMax <= innerColMin || innerRowMax <= innerRowMin) {
            return listOf((room.col + room.cols / 2) to (room.row + room.rows / 2)) to emptyList()
        }

        // The obstacle set is an advisory guide, not ground truth: features may be sparse
        // or absent entirely, so an empty/partial blocked set must still yield a clean
        // ring + lattice rather than failing.
        val blocked = blockedCells(features)
        fun isBlocked(cell: Pair<Int, Int>) = cell in blocked

        // Ring cells walked clockwise from the top-left inner corner, sampled every [step]
        // cells, each tagged with the direction that steps one cell further inward (used to
        // detour around a blocked cell rather than drawing the operator into a wall/obstacle).
        val ringPlan = mutableListOf<Pair<Pair<Int, Int>, Pair<Int, Int>>>()
        for (c in innerColMin..innerColMax step step) ringPlan.add((c to innerRowMin) to (0 to 1))
        for (r in innerRowMin + step..innerRowMax step step) ringPlan.add((innerColMax to r) to (-1 to 0))
        if (innerRowMax > innerRowMin) {
            for (c in innerColMax - step downTo innerColMin step step) ringPlan.add((c to innerRowMax) to (0 to -1))
        }
        if (innerColMax > innerColMin) {
            for (r in innerRowMax - step downTo innerRowMin + step step step) ringPlan.add((innerColMin to r) to (1 to 0))
        }

        // Detour: step inward repeatedly until an unblocked cell inside the inner rect
        // is found, so wide obstacles sitting on the ring get walked around, never
        // through. Only a fully blocked column/row drops the point.
        fun insideInner(cell: Pair<Int, Int>) =
            cell.first in innerColMin..innerColMax && cell.second in innerRowMin..innerRowMax

        val ring = mutableListOf<Pair<Int, Int>>()
        for ((cell, inward) in ringPlan) {
            var candidate = cell
            while (insideInner(candidate) && isBlocked(candidate)) {
                candidate = (candidate.first + inward.first) to (candidate.second + inward.second)
            }
            if (insideInner(candidate) && candidate != ring.lastOrNull()) ring.add(candidate)
        }
        val ringSet = ring.toSet()

        // Interior lattice: ~1m between rows, boustrophedon, sampled every [step] cells.
        val lattice = mutableListOf<Pair<Int, Int>>()
        var leftToRight = true
        var r = innerRowMin + latticeStep
        while (r <= innerRowMax - 1) {
            val cols = if (leftToRight) innerColMin..innerColMax step step else innerColMax downTo innerColMin step step
            for (c in cols) {
                val cell = c to r
                if (!isBlocked(cell) && cell !in ringSet) lattice.add(cell)
            }
            leftToRight = !leftToRight
            r += latticeStep
        }

        return ring to lattice
    }

    /** Path length in meters / 0.35m shot spacing, rounded up, clamped 10..200. */
    fun estimatedShots(room: PlanRoom, features: List<PlanFeature>, scaleMPerCell: Float): Int {
        val path = walkPath(room, features, scaleMPerCell)
        if (path.size < 2) return 10
        var meters = 0f
        for (i in 1 until path.size) {
            val (c1, r1) = path[i - 1]
            val (c2, r2) = path[i]
            val dc = (c2 - c1).toFloat()
            val dr = (r2 - r1).toFloat()
            meters += sqrt(dc * dc + dr * dr) * scaleMPerCell
        }
        return ceil(meters / 0.35f).toInt().coerceIn(10, 200)
    }

    /** shots / 20 per minute, rounded up, clamped 1..10. */
    fun estimatedMinutes(shots: Int): Int =
        ceil(shots / 20.0).toInt().coerceIn(1, 10)

    /** Engineer-terse coaching notes: windows first, then obstacles, when present. */
    fun coachingNotes(room: PlanRoom, features: List<PlanFeature>): List<String> {
        fun overlapsRoom(f: PlanFeature): Boolean {
            val fColEnd = f.col + f.cols
            val fRowEnd = f.row + f.rows
            val roomColEnd = room.col + room.cols
            val roomRowEnd = room.row + room.rows
            return f.col < roomColEnd && fColEnd > room.col && f.row < roomRowEnd && fRowEnd > room.row
        }

        val notes = mutableListOf<String>()
        val windowCount = features.count { it.type == FeatureType.WINDOW && overlapsRoom(it) }
        if (windowCount > 0) {
            notes.add("$windowCount windows here. Keep them out of frame. Never shoot straight into one.")
            // Windows are where exposure goes wrong: bright glare against a dim interior. Tie the
            // route note to the EV control the user already has, so they adjust before shooting.
            notes.add("Bright window light — lock exposure on a mid-tone wall, then nudge EV down if it blows out.")
        }
        val hasObstacle = features.any { it.type == FeatureType.OBSTACLE && overlapsRoom(it) }
        if (hasObstacle) {
            notes.add("Walk around the obstacles. Keep about a meter of standoff.")
        }
        return notes
    }

    // ---- Building-level walk plan --------------------------------------------------------
    // Turns a whole floor plan into an ORDERED walk through every room the way a person
    // actually maps their steps through a building: one room after another, back-tracking kept
    // low, each stop carrying its shot budget and the same window/obstacle/exposure coaching.

    data class WalkStep(
        val order: Int,          // 1-based position in the walk
        val roomName: String,
        val shots: Int,
        val minutes: Int,
        val notes: List<String>, // window / obstacle / exposure guidance for this room
        val nextRoom: String?,   // the room walked to after this one, null at the end
        val nextDir: String,     // "" for the last stop, else "right"/"left"/"toward the top"/...
    )

    data class BuildingPlan(
        val steps: List<WalkStep>,
        val totalShots: Int,
        val totalMinutes: Int,
    )

    /**
     * Ordered walk covering every room in [plan]. Rooms are visited by a greedy
     * nearest-neighbour tour that starts from the room nearest the sketch origin (top-left),
     * which keeps a hand-drawn plan's walk from criss-crossing the building.
     *
     * ponytail: greedy NN, O(n^2) over rooms — right for the app's 5-room cap; only worth
     * upgrading to 2-opt if plans ever hold dozens of rooms.
     */
    fun buildingStepPlan(plan: FloorPlan): BuildingPlan {
        val order = nearestNeighbourOrder(plan.rooms, plan.scaleMPerCell)
        val steps = order.mapIndexed { i, room ->
            val shots = estimatedShots(room, plan.features, plan.scaleMPerCell)
            val next = order.getOrNull(i + 1)
            WalkStep(
                order = i + 1,
                roomName = room.name,
                shots = shots,
                minutes = estimatedMinutes(shots),
                notes = coachingNotes(room, plan.features),
                nextRoom = next?.name,
                nextDir = if (next == null) "" else directionWord(room, next),
            )
        }
        return BuildingPlan(
            steps = steps,
            totalShots = steps.sumOf { it.shots },
            // Room-capture minutes plus a nominal ~1 min to move between rooms.
            totalMinutes = steps.sumOf { it.minutes } + (steps.size - 1).coerceAtLeast(0),
        )
    }

    private fun centroid(room: PlanRoom, scale: Float): Pair<Float, Float> =
        ((room.col + room.cols / 2f) * scale) to ((room.row + room.rows / 2f) * scale)

    private fun nearestNeighbourOrder(rooms: List<PlanRoom>, scale: Float): List<PlanRoom> {
        if (rooms.size <= 1) return rooms
        val remaining = rooms.toMutableList()
        // Start nearest the origin so the walk begins at a consistent corner of the sketch.
        var current = remaining.minByOrNull {
            val c = centroid(it, scale); c.first * c.first + c.second * c.second
        } ?: return rooms
        val ordered = mutableListOf<PlanRoom>()
        while (remaining.isNotEmpty()) {
            ordered.add(current)
            remaining.remove(current)
            val cc = centroid(current, scale)
            current = remaining.minByOrNull {
                val c = centroid(it, scale)
                val dx = c.first - cc.first
                val dy = c.second - cc.second
                dx * dx + dy * dy
            } ?: break
        }
        return ordered
    }

    /** Plan-relative direction from [from] to [to], phrased for someone reading the sketch. */
    private fun directionWord(from: PlanRoom, to: PlanRoom): String {
        val (fx, fy) = centroid(from, 1f)
        val (tx, ty) = centroid(to, 1f)
        val dx = tx - fx
        val dy = ty - fy
        return if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
            if (dx >= 0) "right" else "left"
        } else {
            if (dy >= 0) "toward the bottom" else "toward the top"
        }
    }

    // ---- Move snapping -------------------------------------------------------------------

    data class SnapOutcome(val col: Int, val row: Int, val cols: Int, val rows: Int, val snapped: Boolean)

    /**
     * Snaps a moved rect (an obstacle/wall being dragged) against its room and the other
     * features. Within ~[tolCells]: furniture (OBSTACLE) near a wall first ROTATES so its
     * long side lies along that wall, then edges pull flush — room walls and other objects'
     * edges (align or abut) both count. Finally the rect is clamped inside the room.
     *
     * ponytail: axis-aligned snapping only, no diagonal walls or free rotation — matches
     * the sketch's axis-aligned object model; free rotation is the upgrade path.
     */
    fun snapMovedRect(
        room: PlanRoom?,
        others: List<PlanFeature>,
        type: FeatureType,
        col: Int,
        row: Int,
        colsIn: Int,
        rowsIn: Int,
        tolCells: Int,
    ): SnapOutcome {
        var cols = colsIn
        var rows = rowsIn
        var snapped = false

        // Snap-orient: lay the long side along the nearest wall when close enough.
        if (type == FeatureType.OBSTACLE && room != null && cols != rows) {
            val dLeft = kotlin.math.abs(col - room.col)
            val dRight = kotlin.math.abs((room.col + room.cols) - (col + cols))
            val dTop = kotlin.math.abs(row - room.row)
            val dBottom = kotlin.math.abs((room.row + room.rows) - (row + rows))
            val nearest = minOf(dLeft, dRight, dTop, dBottom)
            if (nearest <= tolCells) {
                val verticalWall = nearest == dLeft || nearest == dRight
                if (verticalWall && cols > rows) { val t = cols; cols = rows; rows = t; snapped = true }
                if (!verticalWall && rows > cols) { val t = cols; cols = rows; rows = t; snapped = true }
            }
        }

        fun snapAxis(pos: Int, size: Int, roomLo: Int?, roomHi: Int?, edges: List<Int>): Int {
            val candidates = mutableListOf<Int>()
            if (roomLo != null) candidates.add(roomLo)                 // flush to near wall
            if (roomHi != null) candidates.add(roomHi - size)          // flush to far wall
            for (e in edges) {
                candidates.add(e)          // align leading edges
                candidates.add(e - size)   // abut before the edge
            }
            var best = pos
            var bestD = tolCells + 1
            for (c in candidates) {
                val d = kotlin.math.abs(c - pos)
                if (d < bestD) { bestD = d; best = c }
            }
            if (best != pos) snapped = true
            return best
        }

        val xEdges = others.flatMap { listOf(it.col, it.col + it.cols) }
        val yEdges = others.flatMap { listOf(it.row, it.row + it.rows) }
        var c0 = snapAxis(col, cols, room?.col, room?.let { it.col + it.cols }, xEdges)
        var r0 = snapAxis(row, rows, room?.row, room?.let { it.row + it.rows }, yEdges)

        if (room != null) {
            c0 = c0.coerceIn(room.col, maxOf(room.col, room.col + room.cols - cols))
            r0 = r0.coerceIn(room.row, maxOf(room.row, room.row + room.rows - rows))
        }
        return SnapOutcome(c0, r0, cols, rows, snapped)
    }

    private fun blockedCells(features: List<PlanFeature>): Set<Pair<Int, Int>> {
        val blocked = mutableSetOf<Pair<Int, Int>>()
        for (f in features) {
            if (f.type != FeatureType.OBSTACLE && f.type != FeatureType.WALL) continue
            for (c in f.col until f.col + f.cols) {
                for (r in f.row until f.row + f.rows) {
                    blocked.add(c to r)
                }
            }
        }
        return blocked
    }
}
