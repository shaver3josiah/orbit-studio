package com.orbitstudio.capture.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

// ponytail: one JSON file (plans.json) holding every plan as an array, mirroring
// FileScanRepository's per-entity-file pattern but flattened since v1 has exactly one
// plan — upgrade to one-file-per-plan if plan counts ever grow large.
class FloorPlanRepository(context: android.content.Context) {

    private val file: File = File(context.filesDir, "plans.json")
    private val lock = Any()

    fun listPlans(): List<FloorPlan> = synchronized(lock) { readAll() }

    fun createPlan(name: String): FloorPlan = synchronized(lock) {
        val plan = FloorPlan(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAtMs = System.currentTimeMillis(),
            rooms = emptyList(),
        )
        writeAll(readAll() + plan)
        plan
    }

    fun getPlan(id: String): FloorPlan? = synchronized(lock) {
        readAll().find { it.id == id }
    }

    fun updatePlan(plan: FloorPlan) = synchronized(lock) {
        // The one-scene-per-bundle pipeline assumption caps rooms at 5; the invariant
        // lives here so every caller inherits it, not just the drag gate in the UI.
        require(plan.rooms.size <= 5) { "A floor plan holds at most 5 rooms." }
        val all = readAll().toMutableList()
        val idx = all.indexOfFirst { it.id == plan.id }
        if (idx >= 0) all[idx] = plan else all.add(plan)
        writeAll(all)
    }

    fun deletePlan(id: String) = synchronized(lock) {
        writeAll(readAll().filterNot { it.id == id })
    }

    private fun readAll(): List<FloorPlan> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i -> parsePlan(arr.optJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parsePlan(json: JSONObject?): FloorPlan? {
        if (json == null) return null
        return try {
            val roomsJson = json.optJSONArray("rooms") ?: JSONArray()
            val rooms = (0 until roomsJson.length()).map { i ->
                val r = roomsJson.getJSONObject(i)
                PlanRoom(
                    id = r.optString("id", ""),
                    name = r.optString("name", ""),
                    col = r.optInt("col", 0),
                    row = r.optInt("row", 0),
                    cols = r.optInt("cols", 1).coerceAtLeast(1),
                    rows = r.optInt("rows", 1).coerceAtLeast(1),
                    scanId = if (r.has("scanId") && !r.isNull("scanId")) r.optString("scanId") else null,
                    startCol = if (r.has("startCol") && !r.isNull("startCol")) r.optInt("startCol") else null,
                    startRow = if (r.has("startRow") && !r.isNull("startRow")) r.optInt("startRow") else null,
                    startDirDeg = if (r.has("startDirDeg") && !r.isNull("startDirDeg")) r.optDouble("startDirDeg").toFloat() else null,
                )
            }
            val featuresJson = json.optJSONArray("features") ?: JSONArray()
            val features = (0 until featuresJson.length()).mapNotNull { i ->
                parseFeature(featuresJson.optJSONObject(i))
            }
            FloorPlan(
                id = json.optString("id", ""),
                name = json.optString("name", ""),
                createdAtMs = json.optLong("createdAtMs", 0L),
                rooms = rooms,
                features = features,
                gridCols = json.optInt("gridCols", 16),
                gridRows = json.optInt("gridRows", 20),
                scaleMPerCell = json.optDouble("scaleMPerCell", 0.5).toFloat(),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseFeature(f: JSONObject?): PlanFeature? {
        if (f == null) return null
        val type = try {
            FeatureType.valueOf(f.optString("type", ""))
        } catch (e: IllegalArgumentException) {
            return null
        }
        return PlanFeature(
            id = f.optString("id", ""),
            type = type,
            col = f.optInt("col", 0),
            row = f.optInt("row", 0),
            cols = f.optInt("cols", 1).coerceAtLeast(1),
            rows = f.optInt("rows", 1).coerceAtLeast(1),
            horizontal = f.optBoolean("horizontal", true),
            variant = f.optString("variant", ""),
        )
    }

    private fun writeAll(plans: List<FloorPlan>) {
        val arr = JSONArray().apply {
            plans.forEach { plan ->
                put(JSONObject().apply {
                    put("id", plan.id)
                    put("name", plan.name)
                    put("createdAtMs", plan.createdAtMs)
                    put("rooms", JSONArray().apply {
                        plan.rooms.forEach { room ->
                            put(JSONObject().apply {
                                put("id", room.id)
                                put("name", room.name)
                                put("col", room.col)
                                put("row", room.row)
                                put("cols", room.cols)
                                put("rows", room.rows)
                                put("scanId", room.scanId)
                                put("startCol", room.startCol)
                                put("startRow", room.startRow)
                                put("startDirDeg", room.startDirDeg?.toDouble())
                            })
                        }
                    })
                    put("features", JSONArray().apply {
                        plan.features.forEach { feature ->
                            put(JSONObject().apply {
                                put("id", feature.id)
                                put("type", feature.type.name)
                                put("col", feature.col)
                                put("row", feature.row)
                                put("cols", feature.cols)
                                put("rows", feature.rows)
                                put("horizontal", feature.horizontal)
                                put("variant", feature.variant)
                            })
                        }
                    })
                    put("gridCols", plan.gridCols)
                    put("gridRows", plan.gridRows)
                    put("scaleMPerCell", plan.scaleMPerCell.toDouble())
                })
            }
        }
        file.writeText(arr.toString())
    }
}

// Self-initializing singleton: MainActivity is owned by another agent this round, so
// this can't be wired up the way Scans.repo is. Lazily builds one FloorPlanRepository
// keyed on applicationContext instead (same effective lifetime as a field set in onCreate).
object Plans {
    @Volatile private var instance: FloorPlanRepository? = null

    fun repo(context: android.content.Context): FloorPlanRepository =
        instance ?: synchronized(this) {
            instance ?: FloorPlanRepository(context.applicationContext).also { instance = it }
        }
}
