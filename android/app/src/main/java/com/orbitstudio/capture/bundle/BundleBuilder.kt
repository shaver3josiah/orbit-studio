package com.orbitstudio.capture.bundle

import android.content.Context
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.orbitstudio.capture.data.FeatureType
import com.orbitstudio.capture.data.FloorPlan
import com.orbitstudio.capture.data.PlanCoach
import com.orbitstudio.capture.data.PlanFeature
import com.orbitstudio.capture.data.PlanRoom
import com.orbitstudio.capture.data.Scan
import com.orbitstudio.capture.data.ShotMeta
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val BUFFER_SIZE = 8192

// Ground truth: pipeline/bundle.py. manifest.json first, then crops/*.jpg, every
// entry ZIP_STORED (uncompressed) so the pipeline can mmap crops without inflating.
object BundleBuilder {

    /** Preview-friendly: manifest only, no zip I/O. Used by BundleScreen before the user builds.
     *  [context] is optional and only needed to attach the "plan" rig block for scans linked
     *  to a floor-plan room; omitting it (existing call sites) yields today's manifest exactly. */
    fun buildManifestJson(scan: Scan, photosDir: File, context: Context? = null): JSONObject =
        manifestFor(scan, photosDir, existingShots(scan, photosDir), context)

    fun build(
        scan: Scan,
        photosDir: File,
        outputStream: OutputStream,
        onProgress: (Int) -> Unit,
        context: Context? = null,
    ) {
        val shots = existingShots(scan, photosDir)
        val manifestBytes = manifestFor(scan, photosDir, shots, context).toString(2).toByteArray(Charsets.UTF_8)
        val total = 1 + shots.size
        var done = 0

        ZipOutputStream(outputStream).use { zip ->
            zip.putNextEntry(storedEntry("manifest.json", manifestBytes))
            zip.write(manifestBytes)
            zip.closeEntry()
            done++; onProgress((done * 100) / total)

            shots.forEach { shot ->
                val file = File(photosDir, shot.fileName)
                // STORED entries need crc/size before putNextEntry, so read the file twice
                // (crc+size pass, then copy pass) instead of buffering it whole in memory.
                val buffer = ByteArray(BUFFER_SIZE)
                val crc = CRC32()
                var size = 0L
                FileInputStream(file).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        crc.update(buffer, 0, read)
                        size += read
                    }
                }
                zip.putNextEntry(ZipEntry("crops/${shot.fileName}").apply {
                    method = ZipEntry.STORED
                    this.size = size
                    compressedSize = size
                    this.crc = crc.value
                })
                FileInputStream(file).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
                done++; onProgress((done * 100) / total)
            }
        }
    }

    private fun existingShots(scan: Scan, photosDir: File): List<ShotMeta> =
        scan.shots.filter { File(photosDir, it.fileName).isFile }

    private fun manifestFor(scan: Scan, photosDir: File, shots: List<ShotMeta>, context: Context?): JSONObject {
        val panoShots = shots.filter { it.kind != "still" }
        val rig = JSONObject().apply {
            put("lane", "phone")
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            focalLength35mm(shots, photosDir)?.let { put("focal_length_35mm_equiv_mm", it) }
            put("exposure_locked", scan.exposureLocked)
            put("capture_pattern", if (panoShots.isEmpty()) "perimeter+interior+loop" else "pano-stations")
            put("photo_count", shots.size)
            // Additive block (same discipline as rig.plan): per-frame attitude for sweep
            // captures, consumed by tools/stitch_panos.py to reproject frames onto an
            // equirect canvas and auto-level the horizon from the recorded pitch/roll.
            // Absent entirely for plain still scans, so old bundles are byte-identical.
            if (panoShots.isNotEmpty()) {
                put("attitude", JSONArray().apply {
                    panoShots.forEach { s ->
                        put(JSONObject().apply {
                            put("file", s.fileName)
                            put("yaw_deg", s.yawDeg.toDouble())
                            put("pitch_deg", s.pitchDeg.toDouble())
                            put("roll_deg", s.rollDeg.toDouble())
                            put("kind", s.kind)
                            put("station", s.station)
                        })
                    }
                })
            }
            context?.let { planJson(it, scan.id) }?.let { put("plan", it) }
        }
        return JSONObject().apply {
            put("app", "orbit-studio")
            put("project", scan.name.lowercase().replace(' ', '-'))
            put("crops", shots.size)
            put("rig", rig)
        }
    }

    // Frozen contract 6: optional rig.plan block, present only when this scan is linked to a
    // floor-plan room. Coordinates are room-relative (feature.col - room.col, etc.) and
    // features are clipped to those intersecting the room's bounds.
    private fun planJson(context: Context, scanId: String): JSONObject? {
        val (plan, room) = PlanCoach.linkedRoom(context, scanId) ?: return null
        val relevant = plan.features.filter { it.intersectsRoom(room) }
        fun byType(type: FeatureType) = JSONArray().apply {
            relevant.filter { it.type == type }.forEach { f ->
                // Clip geometrically to [0, room.cols) x [0, room.rows): a feature
                // straddling two rooms must not export negative or oversized coords.
                val colStart = maxOf(f.col, room.col) - room.col
                val rowStart = maxOf(f.row, room.row) - room.row
                val colEnd = minOf(f.col + f.cols, room.col + room.cols) - room.col
                val rowEnd = minOf(f.row + f.rows, room.row + room.rows) - room.row
                if (colEnd > colStart && rowEnd > rowStart) {
                    put(JSONArray().apply {
                        put(colStart)
                        put(rowStart)
                        put(colEnd - colStart)
                        put(rowEnd - rowStart)
                        put(f.horizontal)
                    })
                }
            }
        }
        return JSONObject().apply {
            put("grid_cell_m", plan.scaleMPerCell)
            put("room", JSONObject().apply {
                put("cols", room.cols)
                put("rows", room.rows)
            })
            put("windows", byType(FeatureType.WINDOW))
            put("doors", byType(FeatureType.DOOR))
            put("obstacles", byType(FeatureType.OBSTACLE))
            put("walls", byType(FeatureType.WALL))
        }
    }

    private fun PlanFeature.intersectsRoom(room: PlanRoom): Boolean =
        col < room.col + room.cols && col + cols > room.col &&
            row < room.row + room.rows && row + rows > room.row

    private fun focalLength35mm(shots: List<ShotMeta>, photosDir: File): Int? {
        val first = shots.firstOrNull() ?: return null
        val file = File(photosDir, first.fileName)
        if (!file.isFile) return null
        return try {
            val value = ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
            if (value > 0) value else null
        } catch (e: IOException) {
            null
        }
    }

    private fun storedEntry(name: String, bytes: ByteArray): ZipEntry = ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = bytes.size.toLong()
        compressedSize = bytes.size.toLong()
        crc = CRC32().apply { update(bytes) }.value
    }
}
