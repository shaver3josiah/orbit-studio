package com.orbitstudio.capture.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.roundToInt

// ponytail: raw rotation-vector math, no fused-sensor library — the rotation vector
// already fuses accel+gyro(+mag when available) on-device, which is all these coaching
// heuristics need. Level bubble only reads roll (left-right tilt), matching the single-axis
// bubble in the design preview; a 2-axis bubble is an upgrade path if pitch coaching is ever
// wanted.
class CoachSensors(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Budget MediaTek ISPs frequently drop TYPE_ROTATION_VECTOR entirely. Fall back to the
    // game rotation vector (accel+gyro only, no magnetometer) — same matrix math below, just
    // no absolute north, so heading drifts slowly. Fine for a 1-3 minute scan, and a live but
    // drifting compass beats a permanently dead one.
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    /** True iff this device has a usable rotation sensor. When false, the capture screen should
     * hide directional guidance (arrows, minimap) and fall back to non-directional coaching. */
    val available: Boolean = rotationSensor != null

    // Hardware step detector drives the treasure-map marker between station dots
    // (~0.7 m/step dead reckoning along the planned path). API 29+ gates it behind
    // ACTIVITY_RECOGNITION; denied or absent just means stepsAvailable stays false
    // and the marker anchors on dots only.
    private val stepSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    val stepsAvailable: Boolean = stepSensor != null

    /** Steps detected since start(). One event per step from the hardware detector. */
    var stepCount by mutableIntStateOf(0)
        private set

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    /** Left-right tilt off vertical, degrees. 0 = level. Drives the level bubble. */
    var rollDeg by mutableFloatStateOf(0f)
        private set

    /** Camera pitch, degrees. 0 = horizon, positive = pointing up, +/-90 = zenith/nadir.
     * getOrientation's pitch is negative with the device top tilted back (camera up) in this
     * remapped frame, hence the sign flip. Sign verified on first hardware run via the pano
     * HUD's tilt cue — if up reads as down there, flip here. */
    var pitchDeg by mutableFloatStateOf(0f)
        private set

    /** Overlap heuristic vs. the last shot: 100 minus yaw turned since then, scaled by a ~66° FOV. */
    var overlapPct by mutableIntStateOf(100)
        private set

    /** abs(degrees/sec) turn rate — drives the "slow down" toast above ~40°/s. */
    var yawRateDegPerSec by mutableFloatStateOf(0f)
        private set

    /** Absolute heading, 0..360, zeroed to the first yaw reading of the scan. Drives the plan-view minimap. */
    var headingDeg by mutableFloatStateOf(0f)
        private set

    private var yawAtLastShot: Float? = null
    private var lastYaw: Float? = null
    private var lastTimestampNs: Long = 0L
    private var yawZero: Float? = null

    // Raw per-event math, updated every sensor tick. The published Compose states above only
    // move when a raw value has drifted far enough to matter (see thresholds below) — this is
    // the recomposition-discipline fix: whole-screen recompositions cut roughly 10x with zero
    // visual difference, since the meters animate over 150-200 ms anyway.
    private var rawRollDeg: Float = 0f
    private var rawPitchDeg: Float = 0f
    private var rawHeadingDeg: Float = 0f
    private var rawOverlapPct: Int = 100
    private var rawYawRateDegPerSec: Float = 0f

    fun start() {
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        stepSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /** Resets the overlap baseline to "now" right after a shot is committed. */
    fun markShotTaken() {
        yawAtLastShot = lastYaw
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
            stepCount += 1
            return
        }
        // Accept whichever vector sensor we actually registered — getRotationMatrixFromVector
        // handles both TYPE_ROTATION_VECTOR and TYPE_GAME_ROTATION_VECTOR payloads identically.
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR
        ) {
            return
        }
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.remapCoordinateSystem(
            rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remappedMatrix,
        )
        SensorManager.getOrientation(remappedMatrix, orientation)
        val yaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
        rawRollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat()
        rawPitchDeg = -Math.toDegrees(orientation[1].toDouble()).toFloat()

        if (yawAtLastShot == null) yawAtLastShot = yaw
        val delta = angleDelta(yawAtLastShot!!, yaw)
        rawOverlapPct = (100 - (delta / 66.0 * 100)).roundToInt().coerceIn(0, 100)

        if (yawZero == null) yawZero = yaw
        rawHeadingDeg = normalize0to360(yaw - yawZero!!)

        val now = event.timestamp
        val prevYaw = lastYaw
        if (prevYaw != null && lastTimestampNs != 0L) {
            val dtSec = (now - lastTimestampNs) / 1_000_000_000.0
            if (dtSec > 0) {
                rawYawRateDegPerSec = (angleDelta(prevYaw, yaw) / dtSec).toFloat()
            }
        }
        lastYaw = yaw
        lastTimestampNs = now

        // Quantized publish: only touch Compose state when the change is big enough to be seen.
        if (abs(rawRollDeg - rollDeg) >= 0.5f) rollDeg = rawRollDeg
        if (abs(rawPitchDeg - pitchDeg) >= 0.5f) pitchDeg = rawPitchDeg
        if (angleDelta(headingDeg, rawHeadingDeg) >= 0.5f) headingDeg = rawHeadingDeg
        if (abs(rawOverlapPct - overlapPct) >= 1) overlapPct = rawOverlapPct
        if (abs(rawYawRateDegPerSec - yawRateDegPerSec) >= 2.0f) yawRateDegPerSec = rawYawRateDegPerSec
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Signed shortest angular distance from headingDeg to targetDeg, -180..180. Positive = turn right. */
    // Reads the published (quantized) headingDeg, not rawHeadingDeg — guidance math and the UI
    // must agree on the same number, and a 0.5° lag behind raw is invisible at this granularity.
    fun signedDeltaTo(targetDeg: Float): Float = signedAngleDelta(headingDeg, targetDeg)

    private fun angleDelta(from: Float, to: Float): Float = abs(signedAngleDelta(from, to))

    // ponytail: same shortest-path wrap as angleDelta, just without the abs() — one helper,
    // two call sites, instead of duplicating the wrap loop.
    private fun signedAngleDelta(from: Float, to: Float): Float {
        var d = to - from
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    private fun normalize0to360(deg: Float): Float {
        var d = deg % 360f
        if (d < 0f) d += 360f
        return d
    }
}
