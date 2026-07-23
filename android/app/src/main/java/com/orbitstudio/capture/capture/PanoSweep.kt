package com.orbitstudio.capture.capture

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt

// Pure Kotlin (no Android imports) so it's usable from a plain JVM/kotlinc check, like PlanMath.
//
// A "panorama" here is one STATION: the operator stands on one treasure-map dot and sweeps
// full circles at each pitch band (photosphere-style), then shoots the ceiling and floor as
// individual stills. Android exposes no public intent or API to invoke the stock camera's
// panorama/photosphere mode, so this app guides its own sweep and the frames are stitched
// on the laptop (tools/stitch_panos.py) using the per-frame yaw/pitch/roll recorded at
// capture — which is also what lets the stitcher level the horizon automatically.
object PanoSweep {

    enum class TargetKind { PANO_FRAME, ZENITH, NADIR }

    data class SweepTarget(
        val index: Int,          // 0-based position in the station's ordered target list
        val yawDeg: Float,       // 0..360, heading-space (same space as CoachSensors.headingDeg)
        val pitchDeg: Float,     // 0 = horizon, + = up
        val band: Int,           // which pitch band this frame belongs to; -1 for zenith/nadir
        val kind: TargetKind,
    )

    // Sweep geometry. Bands at 0 / +45 / -45 with a portrait phone's tall FOV cover the
    // sphere's belt; the caps come from zenith/nadir stills ("individual photos are mostly
    // for above and below"). Yaw step widens by 1/cos(pitch) on the high/low rings because
    // a latitude circle shrinks by cos(pitch), so the same overlap needs fewer frames.
    // Within a station frames are rotation-only (no baseline), so the SfM 70-80% overlap
    // rule does not apply here - the 20-35% stitcher band does. 30 degrees at ~50 degree
    // hFOV = 40% overlap, the Photo Sphere pattern (~12 frames on the equator ring).
    const val H_FOV_DEG = 50f            // portrait horizontal FOV, typical phone main camera
    const val YAW_STEP_DEG = 30f         // ~40% frame-to-frame overlap at 50 degree hFOV
    val PITCH_BANDS = listOf(0f, 45f, -45f)
    const val ZENITH_SHOTS = 2
    const val NADIR_SHOTS = 2
    const val YAW_TOL_DEG = 7f           // alignment window for auto-fire
    const val PITCH_TOL_DEG = 8f
    const val SETTLED_DEG_PER_SEC = 15f  // yaw rate below this counts as "held still"
    // Poles are different: yaw gimbal-locks near +/-90 pitch, so the yaw-rate reading
    // spikes even in a steady hand — the settle gate must not use it there. Pitch is
    // also noisiest at the poles, so the window opens wider.
    const val POLE_PITCH_TOL_DEG = 20f

    /** Ordered targets for one station: horizon ring first, then up ring, then down ring,
     *  then zenith and nadir stills. Rings start at [startYawDeg] (wherever the operator is
     *  already facing) so the first frame never asks for a turn. */
    fun stationTargets(startYawDeg: Float = 0f): List<SweepTarget> {
        val targets = mutableListOf<SweepTarget>()
        var i = 0
        for ((band, pitch) in PITCH_BANDS.withIndex()) {
            val step = YAW_STEP_DEG / cos(Math.toRadians(pitch.toDouble())).toFloat()
            val count = ceil(360f / step).toInt()
            val actualStep = 360f / count // spread evenly so the ring closes exactly
            for (k in 0 until count) {
                targets.add(
                    SweepTarget(
                        index = i++,
                        yawDeg = normalize360(startYawDeg + k * actualStep),
                        pitchDeg = pitch,
                        band = band,
                        kind = TargetKind.PANO_FRAME,
                    ),
                )
            }
        }
        repeat(ZENITH_SHOTS) {
            targets.add(SweepTarget(i++, 0f, 90f, -1, TargetKind.ZENITH))
        }
        repeat(NADIR_SHOTS) {
            targets.add(SweepTarget(i++, 0f, -90f, -1, TargetKind.NADIR))
        }
        return targets
    }

    /** True when the phone is pointed at [target] steadily enough to auto-fire.
     *  Zenith/nadir ignore yaw AND the yaw-rate settle check entirely: heading is
     *  gimbal-unstable straight up/down (rate spikes in a steady hand), any yaw shows
     *  the same scene, and the pitch window is deliberately generous there. */
    fun aligned(target: SweepTarget, yawDeg: Float, pitchDeg: Float, yawRateDegPerSec: Float): Boolean {
        if (target.kind != TargetKind.PANO_FRAME) {
            return abs(pitchDeg - target.pitchDeg) <= POLE_PITCH_TOL_DEG
        }
        if (abs(yawRateDegPerSec) > SETTLED_DEG_PER_SEC) return false
        if (abs(pitchDeg - target.pitchDeg) > PITCH_TOL_DEG) return false
        return angleDelta(yawDeg, target.yawDeg) <= YAW_TOL_DEG
    }

    data class Progress(
        val frameDone: Int,       // pano frames captured at this station
        val frameTotal: Int,      // pano frames in the station plan
        val stillsDone: Int,      // zenith+nadir stills captured
        val stillsTotal: Int,
        val band: Int,            // current band index, -1 once rings are finished
        val stationComplete: Boolean,
    )

    fun progress(targets: List<SweepTarget>, capturedCount: Int): Progress {
        val frames = targets.count { it.kind == TargetKind.PANO_FRAME }
        val stills = targets.size - frames
        val done = capturedCount.coerceIn(0, targets.size)
        val frameDone = done.coerceAtMost(frames)
        val stillsDone = (done - frames).coerceAtLeast(0)
        val current = targets.getOrNull(done)
        return Progress(
            frameDone = frameDone,
            frameTotal = frames,
            stillsDone = stillsDone,
            stillsTotal = stills,
            band = current?.band ?: -1,
            stationComplete = done >= targets.size,
        )
    }

    // ---- Treasure-map stations -----------------------------------------------------------

    /** Picks [count] pano stations spread evenly by walked distance along the route
     *  (at 0, 1/count, 2/count ... of total length — the route closes a loop, so the
     *  end is skipped). Three stations per room is the operator's working default.
     *  Panos from a single spot have zero baseline, so the spread between stations IS
     *  the parallax the splat trains on. Returns INDICES into [path]. */
    fun stationIndices(
        path: List<Pair<Int, Int>>,
        scaleMPerCell: Float,
        count: Int = 3,
    ): List<Int> {
        if (path.isEmpty() || count < 1) return emptyList()
        if (path.size == 1 || count == 1) return listOf(0)
        // Cumulative length along the path, in meters.
        val cum = FloatArray(path.size)
        for (i in 1 until path.size) {
            val dx = (path[i].first - path[i - 1].first) * scaleMPerCell
            val dy = (path[i].second - path[i - 1].second) * scaleMPerCell
            cum[i] = cum[i - 1] + sqrt(dx * dx + dy * dy)
        }
        val total = cum.last()
        if (total <= 0f) return listOf(0)
        val stations = mutableListOf<Int>()
        for (k in 0 until count) {
            val targetLen = total * k / count
            var best = 0
            var bestD = Float.MAX_VALUE
            for (i in path.indices) {
                val d = kotlin.math.abs(cum[i] - targetLen)
                if (d < bestD) { bestD = d; best = i }
            }
            if (stations.lastOrNull() != best) stations.add(best)
        }
        return stations
    }

    /** Fractional cell position [metersWalked] along the path from [fromIndex] toward
     *  [toIndex], following the route segment by segment (the minimap marker's dead
     *  reckoning). Clamps at [toIndex]; walking backward is not modeled. */
    fun positionAlongPath(
        path: List<Pair<Int, Int>>,
        scaleMPerCell: Float,
        fromIndex: Int,
        toIndex: Int,
        metersWalked: Float,
    ): Pair<Float, Float> {
        if (path.isEmpty()) return 0f to 0f
        val from = fromIndex.coerceIn(0, path.size - 1)
        val to = toIndex.coerceIn(0, path.size - 1)
        if (to <= from || metersWalked <= 0f) {
            return path[from].let { it.first.toFloat() to it.second.toFloat() }
        }
        var remaining = metersWalked
        for (i in from until to) {
            val (c1, r1) = path[i]
            val (c2, r2) = path[i + 1]
            val dx = (c2 - c1) * scaleMPerCell
            val dy = (r2 - r1) * scaleMPerCell
            val segment = sqrt(dx * dx + dy * dy)
            if (segment <= 0f) continue
            if (remaining < segment) {
                val t = remaining / segment
                return (c1 + (c2 - c1) * t) to (r1 + (r2 - r1) * t)
            }
            remaining -= segment
        }
        return path[to].let { it.first.toFloat() to it.second.toFloat() }
    }

    private fun angleDelta(from: Float, to: Float): Float {
        var d = abs(to - from) % 360f
        if (d > 180f) d = 360f - d
        return d
    }

    private fun normalize360(deg: Float): Float = ((deg % 360f) + 360f) % 360f
}
