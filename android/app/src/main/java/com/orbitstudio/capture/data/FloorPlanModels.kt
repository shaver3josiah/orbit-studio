package com.orbitstudio.capture.data

// col/row/cols/rows are grid cell indices, not pixels, so the layout survives any screen
// size. The grid dimensions and cell size live on FloorPlan (gridCols/gridRows/scaleMPerCell);
// new plans default to a fine 7200x9000 grid at 0.05 m/cell (a whole floor of rooms).
data class PlanRoom(
    val id: String,
    val name: String,
    val col: Int,
    val row: Int,
    val cols: Int,
    val rows: Int,
    val scanId: String? = null,
    // Operator-chosen scan start: absolute grid cell + facing direction in sketch
    // coordinates (0 = toward the top of the sheet, clockwise). Null = walk path
    // default start / uncalibrated map heading. Additive; old plans parse fine.
    val startCol: Int? = null,
    val startRow: Int? = null,
    val startDirDeg: Float? = null,
)

enum class FeatureType { WALL, WINDOW, DOOR, OBSTACLE }

// col/row/cols/rows are absolute grid cell indices (same space as PlanRoom), not
// room-relative. Windows/doors are 1-cell-thick segments snapped onto a room edge,
// spanning via cols (horizontal=true) or rows (horizontal=false); obstacles are
// interior rects; walls are interior segments.
data class PlanFeature(
    val id: String,
    val type: FeatureType,
    val col: Int,
    val row: Int,
    val cols: Int,
    val rows: Int,
    val horizontal: Boolean,
    // Rendering hint only (e.g. door "double"/"sliding", window "small"/"bay", obstacle
    // "column"/"stairs"/"void"); "" means the default symbol for the type. Ignored by
    // bundle export and PlanMath, so it's safe for old data to omit it entirely.
    val variant: String = "",
)

data class FloorPlan(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val rooms: List<PlanRoom> = emptyList(),
    val features: List<PlanFeature> = emptyList(),
    val gridCols: Int = 7200,
    val gridRows: Int = 9000,
    val scaleMPerCell: Float = 0.05f,
)
