package com.orbitstudio.capture.data

enum class ScanStage { PERIMETER, INTERIOR, LOOP_CLOSED }
enum class ScanStatus { IN_PROGRESS, REVIEWED, BUNDLED, DONE }

data class ShotMeta(
    val fileName: String,
    val takenAtMs: Long,
    val blurScore: Double,   // Laplacian variance, higher = sharper; < 80.0 flags weak
    val overlapPct: Int,     // 0..100 heuristic vs previous shot
    val stage: ScanStage,
    // Attitude at capture, heading-space degrees. Recorded for pano-sweep frames so the
    // laptop stitcher can reproject to equirect and auto-level the horizon; 0/0/0 on old
    // scans and plain stills, which the stitcher ignores. kind: "still" | "pano" |
    // "zenith" | "nadir"; station: treasure-map dot index, -1 when not station-bound.
    val yawDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val kind: String = "still",
    val station: Int = -1,
)

data class Scan(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val status: ScanStatus,
    val shots: List<ShotMeta>,
    val captureSeconds: Long,
    val exposureLocked: Boolean,
)

interface ScanRepository {
    fun listScans(): List<Scan>
    fun createScan(name: String): Scan
    fun getScan(id: String): Scan?
    fun updateScan(scan: Scan)
    fun photosDir(scanId: String): java.io.File   // filesDir/scans/<id>/photos, created on demand
    fun deleteShot(scanId: String, fileName: String)
    fun deleteScan(scanId: String)
    fun clearShots(scanId: String)   // wipes photos + resets shots/captureSeconds/status for a redo, keeps id/name/exposureLocked
}
