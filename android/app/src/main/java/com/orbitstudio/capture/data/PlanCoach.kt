package com.orbitstudio.capture.data

// Bridges the floor-plan side (FloorPlanRepository/PlanMath) to a scan: given a scanId,
// finds the room it's linked to (if any) and turns that room's geometry into a
// human-readable capture brief. All math is delegated to PlanMath; this is just lookup +
// packaging.
object PlanCoach {

    data class RoomBrief(
        val roomName: String,
        val notes: List<String>,
        val targetShots: Int,
        val minutes: Int,
    )

    // Reused by BundleBuilder to find the same plan+room for the bundle rig's "plan" block.
    fun linkedRoom(context: android.content.Context, scanId: String): Pair<FloorPlan, PlanRoom>? {
        Plans.repo(context).listPlans().forEach { plan ->
            val room = plan.rooms.find { it.scanId == scanId }
            if (room != null) return plan to room
        }
        return null
    }

    fun briefForScan(context: android.content.Context, scanId: String): RoomBrief? {
        val (plan, room) = linkedRoom(context, scanId) ?: return null
        val shots = PlanMath.estimatedShots(room, plan.features, plan.scaleMPerCell)
        return RoomBrief(
            roomName = room.name,
            notes = PlanMath.coachingNotes(room, plan.features),
            targetShots = shots,
            minutes = PlanMath.estimatedMinutes(shots),
        )
    }
}
