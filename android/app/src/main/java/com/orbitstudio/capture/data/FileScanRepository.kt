package com.orbitstudio.capture.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

// ponytail: one file per scan (scan.json) under filesDir/scans/<id>/, no DB —
// scan counts stay small (single-device capture app), upgrade to Room if that changes.
class FileScanRepository(context: android.content.Context) : ScanRepository {

    private val root: File = File(context.filesDir, "scans").apply { mkdirs() }
    private val lock = Any()

    private fun scanDir(id: String) = File(root, id)
    private fun scanFile(id: String) = File(scanDir(id), "scan.json")

    override fun listScans(): List<Scan> = synchronized(lock) {
        root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { readScan(it.name) }
            ?.sortedByDescending { it.createdAtMs }
            ?: emptyList()
    }

    override fun createScan(name: String): Scan = synchronized(lock) {
        val scan = Scan(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAtMs = System.currentTimeMillis(),
            status = ScanStatus.IN_PROGRESS,
            shots = emptyList(),
            captureSeconds = 0L,
            exposureLocked = false,
        )
        scanDir(scan.id).mkdirs()
        writeScan(scan)
        scan
    }

    override fun getScan(id: String): Scan? = synchronized(lock) { readScan(id) }

    override fun updateScan(scan: Scan) = synchronized(lock) {
        scanDir(scan.id).mkdirs()
        writeScan(scan)
    }

    override fun photosDir(scanId: String): File =
        File(scanDir(scanId), "photos").apply { mkdirs() }

    override fun deleteShot(scanId: String, fileName: String) = synchronized(lock) {
        File(photosDir(scanId), fileName).delete()
        val scan = readScan(scanId) ?: return@synchronized
        writeScan(scan.copy(shots = scan.shots.filterNot { it.fileName == fileName }))
    }

    override fun deleteScan(scanId: String) = synchronized(lock) {
        scanDir(scanId).deleteRecursively()
        Unit
    }

    override fun clearShots(scanId: String) = synchronized(lock) {
        val scan = readScan(scanId) ?: return@synchronized
        val dir = photosDir(scanId)
        // Wipe the whole photos dir, not just tracked shots, so an interrupted capture leaves
        // no stragglers — a rescan starts from a truly clean slate.
        dir.listFiles()?.forEach { f ->
            try {
                f.delete()
            } catch (e: Exception) {
                // ponytail: missing/locked file should never block a rescan
            }
        }
        writeScan(
            scan.copy(
                shots = emptyList(),
                captureSeconds = 0L,
                status = ScanStatus.IN_PROGRESS,
            ),
        )
    }

    private fun writeScan(scan: Scan) {
        val json = JSONObject().apply {
            put("id", scan.id)
            put("name", scan.name)
            put("createdAtMs", scan.createdAtMs)
            put("status", scan.status.name)
            put("captureSeconds", scan.captureSeconds)
            put("exposureLocked", scan.exposureLocked)
            put("shots", JSONArray().apply {
                scan.shots.forEach { shot ->
                    put(JSONObject().apply {
                        put("fileName", shot.fileName)
                        put("takenAtMs", shot.takenAtMs)
                        put("blurScore", shot.blurScore)
                        put("overlapPct", shot.overlapPct)
                        put("stage", shot.stage.name)
                        put("yawDeg", shot.yawDeg.toDouble())
                        put("pitchDeg", shot.pitchDeg.toDouble())
                        put("rollDeg", shot.rollDeg.toDouble())
                        put("kind", shot.kind)
                        put("station", shot.station)
                    })
                }
            })
        }
        scanFile(scan.id).writeText(json.toString())
    }

    private fun readScan(id: String): Scan? {
        val file = scanFile(id)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val shotsJson = json.optJSONArray("shots") ?: JSONArray()
            val shots = (0 until shotsJson.length()).map { i ->
                val s = shotsJson.getJSONObject(i)
                ShotMeta(
                    fileName = s.optString("fileName", ""),
                    takenAtMs = s.optLong("takenAtMs", 0L),
                    blurScore = s.optDouble("blurScore", 0.0),
                    overlapPct = s.optInt("overlapPct", 0),
                    stage = runCatching { ScanStage.valueOf(s.optString("stage")) }
                        .getOrDefault(ScanStage.PERIMETER),
                    yawDeg = s.optDouble("yawDeg", 0.0).toFloat(),
                    pitchDeg = s.optDouble("pitchDeg", 0.0).toFloat(),
                    rollDeg = s.optDouble("rollDeg", 0.0).toFloat(),
                    kind = s.optString("kind", "still"),
                    station = s.optInt("station", -1),
                )
            }
            Scan(
                id = json.optString("id", id),
                name = json.optString("name", id),
                createdAtMs = json.optLong("createdAtMs", 0L),
                status = runCatching { ScanStatus.valueOf(json.optString("status")) }
                    .getOrDefault(ScanStatus.IN_PROGRESS),
                shots = shots,
                captureSeconds = json.optLong("captureSeconds", 0L),
                exposureLocked = json.optBoolean("exposureLocked", false),
            )
        } catch (e: Exception) {
            null
        }
    }
}
