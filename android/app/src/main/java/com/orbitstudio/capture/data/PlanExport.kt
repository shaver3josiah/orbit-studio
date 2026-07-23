package com.orbitstudio.capture.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// Building-level JSON export of a floor-plan sketch, meant to help a 3D world-building
// pipeline reconstruct room boxes and wall/window/door/obstacle features. This is a
// hand-drawn advisory sketch, not a laser-measured floor plan, hence sketch_partial+note.
object PlanExport {

    fun buildJson(plan: FloorPlan): String {
        val scale = plan.scaleMPerCell
        val root = JSONObject().apply {
            put("app", "orbit-studio")
            put("kind", "floor-plan")
            put("version", 1)
            put("scale_m_per_cell", round3(scale))
            put("grid", JSONObject().apply {
                put("cols", plan.gridCols)
                put("rows", plan.gridRows)
            })
            put("sketch_partial", true)
            put(
                "note",
                "This sketch is an advisory guide for reconstruction. Obstacles may be " +
                    "incomplete; absence of an obstacle does not mean the space is clear.",
            )
            put("rooms", JSONArray().apply {
                plan.rooms.forEach { r ->
                    put(JSONObject().apply {
                        put("name", r.name)
                        put("x_m", round3(r.col * scale))
                        put("y_m", round3(r.row * scale))
                        put("w_m", round3(r.cols * scale))
                        put("h_m", round3(r.rows * scale))
                        put("scan_id", r.scanId ?: JSONObject.NULL)
                    })
                }
            })
            put("features", JSONArray().apply {
                plan.features.forEach { f ->
                    put(JSONObject().apply {
                        put("type", f.type.name.lowercase())
                        put("variant", f.variant)
                        put("x_m", round3(f.col * scale))
                        put("y_m", round3(f.row * scale))
                        put("w_m", round3(f.cols * scale))
                        put("h_m", round3(f.rows * scale))
                        put("horizontal", f.horizontal)
                    })
                }
            })
            val building = PlanMath.buildingStepPlan(plan)
            put("walk_total_shots", building.totalShots)
            put("walk_total_minutes", building.totalMinutes)
            put("walk_order", JSONArray().apply {
                building.steps.forEach { s ->
                    put(JSONObject().apply {
                        put("order", s.order)
                        put("room", s.roomName)
                        put("shots", s.shots)
                        put("minutes", s.minutes)
                        put("notes", JSONArray().apply { s.notes.forEach { put(it) } })
                        put("next_room", s.nextRoom ?: JSONObject.NULL)
                        put("next_dir", s.nextDir)
                    })
                }
            })
        }
        return root.toString(2)
    }

    /** Human-readable walk checklist a person can follow room-by-room through the building. */
    fun buildWalkChecklist(plan: FloorPlan): String {
        val b = PlanMath.buildingStepPlan(plan)
        val sb = StringBuilder()
        sb.append("ORBIT CAPTURE — WALK PLAN: ${plan.name}\n")
        sb.append("${b.steps.size} rooms · ~${b.totalShots} shots · ~${b.totalMinutes} min total\n")
        sb.append("=".repeat(40)).append("\n\n")
        if (b.steps.isEmpty()) {
            sb.append("No rooms drawn yet. Sketch the rooms first, then export.\n")
            return sb.toString()
        }
        b.steps.forEach { s ->
            sb.append("[ ] ${s.order}. ${s.roomName} — ~${s.shots} shots, ~${s.minutes} min\n")
            s.notes.forEach { sb.append("      - $it\n") }
            if (s.nextRoom != null) sb.append("      -> then go ${s.nextDir} to ${s.nextRoom}\n")
            sb.append("\n")
        }
        sb.append("Tip: hold exposure steady within each room; re-check it when the light changes.\n")
        return sb.toString()
    }

    /** Writes the export to shared Downloads (or an app-storage fallback), then hands the
     *  same bytes to FileProvider for an ACTION_SEND chooser. Never throws; [onResult] always
     *  gets a short status, even on failure. */
    fun exportAndShare(context: Context, plan: FloorPlan, onResult: (String) -> Unit) {
        try {
            val bytes = buildJson(plan).toByteArray(Charsets.UTF_8)
            val fileName = "${slug(plan.name)}-plan.json"
            val status = saveCanonical(context, fileName, bytes)

            // Also drop a human-followable walk checklist next to the JSON. Best-effort: a
            // failure here must never sink the JSON export the user actually asked for.
            runCatching {
                val walk = buildWalkChecklist(plan).toByteArray(Charsets.UTF_8)
                saveCanonical(context, "${slug(plan.name)}-walk.txt", walk)
            }

            // ponytail: always mirror into filesDir for the share uri rather than branching on
            // where saveCanonical landed — one extra small JSON write, no uri-type juggling.
            val shareCopy = File(context.filesDir, "exports/$fileName").apply { parentFile?.mkdirs() }
            FileOutputStream(shareCopy).use { it.write(bytes) }
            val shareUri = FileProvider.getUriForFile(context, "com.orbitstudio.capture.fileprovider", shareCopy)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share floor plan").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            onResult(status)
        } catch (e: Exception) {
            onResult("Couldn't export the floor plan.")
        }
    }

    /** Saves the raw bytes to public Downloads via MediaStore on API 29+, falling back to
     *  app-owned storage when the OEM insert refuses (null uri or SecurityException) or the
     *  write itself fails partway. Returns a short human status describing where it landed. */
    private fun saveCanonical(context: Context, fileName: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/OrbitStudio")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = try {
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } catch (e: SecurityException) {
                null
            }
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IOException("Couldn't open the export for writing.")
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    return "Saved to Downloads/OrbitStudio"
                } catch (e: Exception) {
                    // Clean up the half-created pending row so failed exports don't
                    // accumulate orphaned entries, then fall through to app storage.
                    runCatching { resolver.delete(uri, null, null) }
                }
            }
        }
        val dir = (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir).apply { mkdirs() }
        FileOutputStream(File(dir, fileName)).use { it.write(bytes) }
        return "Saved to the app folder"
    }

    private fun slug(name: String): String =
        name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "floor-plan" }

    private fun round3(v: Float): Double = Math.round(v * 1000.0) / 1000.0
}
