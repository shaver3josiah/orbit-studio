package com.orbitstudio.capture.capture

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Snapshot of the camera's exposure-compensation capability + current setting. */
data class ExposureInfo(val index: Int, val range: IntRange, val step: Float, val supported: Boolean)

/**
 * What the AE lock is actually doing, not what we asked for. Budget MediaTek ISPs
 * (CMF Phone 1/2 Pro, some Galaxy A) frequently do not report CONTROL_AE_STATE or do
 * not expose CONTROL_AE_LOCK_AVAILABLE at all, so the UI must be able to say "requested
 * but unverified" or "not lockable" honestly instead of a green light that lies.
 */
enum class AeLockState { LOCKED, UNLOCKED, UNVERIFIED, UNSUPPORTED }

/** Static camera2 capabilities queried once the camera binds. */
data class CameraCaps(
    val aeLockSupported: Boolean,
    val awbLockSupported: Boolean,
    val hardwareLevel: String,
)

// ponytail: one small engine class wrapping CameraX use-case binding + Camera2 AE/AWB lock.
// No DI, no interfaces — this app has exactly one camera screen.
class CaptureEngine(private val context: Context) {
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var cameraCaps: CameraCaps? = null

    // Verified AE lock: the truth is the camera's own reported CONTROL_AE_STATE, read off the
    // session capture callback (camera thread) — lockExposure() below is only the request.
    // On hardware that never reports AE_STATE the state stays UNVERIFIED rather than lying.
    private var aeStateListener: ((AeLockState) -> Unit)? = null
    private var lastAeState: AeLockState? = null

    fun setAeStateListener(listener: ((AeLockState) -> Unit)?) {
        aeStateListener = listener
    }

    /** Camera2 capabilities; null until the camera has bound. */
    fun caps(): CameraCaps? = cameraCaps

    private fun emitAe(state: AeLockState) {
        if (state != lastAeState) {
            lastAeState = state
            ContextCompat.getMainExecutor(context).execute { aeStateListener?.invoke(state) }
        }
    }

    private fun computeCaps(cam: Camera): CameraCaps = try {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val aeLock = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: false
        val awbLock = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: false
        val level = info.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        CameraCaps(aeLock, awbLock, hardwareLevelName(level))
    } catch (e: Exception) {
        // LEGACY devices can throw on interop capability reads — assume nothing works.
        CameraCaps(aeLockSupported = false, awbLockSupported = false, hardwareLevel = "unknown")
    }

    private fun hardwareLevelName(level: Int?): String = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level_3"
        else -> "external_or_unknown"
    }

    fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onReady: () -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val previewBuilder = Preview.Builder()
                Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult,
                        ) {
                            // If the device can't lock AE at all, don't churn the chip off
                            // stray AE_STATE readings — it stays UNSUPPORTED.
                            if (cameraCaps?.aeLockSupported != true) return
                            when (result.get(CaptureResult.CONTROL_AE_STATE)) {
                                null -> return // device never reports it — hold UNVERIFIED
                                CaptureResult.CONTROL_AE_STATE_LOCKED -> emitAe(AeLockState.LOCKED)
                                else -> emitAe(AeLockState.UNLOCKED)
                            }
                        }
                    },
                )
                val preview = previewBuilder.build().also { it.surfaceProvider = previewView.surfaceProvider }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(95)
                    .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                    .build()
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                imageCapture = capture
                val caps = computeCaps(camera!!)
                cameraCaps = caps
                // Seed the chip before any capture callback: unsupported hardware says so now,
                // supported hardware starts UNVERIFIED until it reports a real locked state.
                emitAe(if (caps.aeLockSupported) AeLockState.UNVERIFIED else AeLockState.UNSUPPORTED)
                onReady()
            } catch (e: Exception) {
                onError(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // AE/AWB lock via Camera2 interop. Only requests the locks the device actually advertises,
    // and swallows the throw that LEGACY/limited HALs can raise — the AE chip reflects the
    // camera's reported state, not this request.
    fun lockExposure(onResult: (Boolean) -> Unit) {
        val cam = camera ?: return onResult(false)
        val caps = cameraCaps
        if (caps != null && !caps.aeLockSupported && !caps.awbLockSupported) return onResult(false)
        try {
            val control = Camera2CameraControl.from(cam.cameraControl)
            val builder = CaptureRequestOptions.Builder()
            if (caps == null || caps.aeLockSupported) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            }
            if (caps == null || caps.awbLockSupported) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
            }
            control.setCaptureRequestOptions(builder.build())
            onResult(true)
        } catch (e: Exception) {
            onResult(false)
        }
    }

    /** Current EV compensation state; supported=false when camera isn't ready or the device can't do it. */
    fun exposureInfo(): ExposureInfo {
        val cam = camera ?: return ExposureInfo(0, 0..0, 0f, false)
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return ExposureInfo(0, 0..0, 0f, false)
        return ExposureInfo(
            index = state.exposureCompensationIndex,
            range = state.exposureCompensationRange.lower..state.exposureCompensationRange.upper,
            step = state.exposureCompensationStep.toFloat(),
            supported = true,
        )
    }

    // ponytail: EV compensation is silently ignored by camera2 while CONTROL_AE_LOCK is on
    // (locked AE means the 3A algorithm won't re-run, so a new target EV never applies), so
    // the dance is unlock -> set index -> relock. Keeps AWB_LOCK true the whole time since only
    // AE needs to move; relock happens even if setExposureCompensationIndex fails, so the AE
    // lock this app depends on for consistent scan exposure is never left off by accident.
    // Main-thread confined (all listeners land on the main executor), so a plain Int suffices.
    private var evGeneration = 0

    fun setExposureCompensation(index: Int, onApplied: (Int) -> Unit) {
        val cam = camera ?: return onApplied(0)
        val info = exposureInfo()
        if (!info.supported) return onApplied(0)
        val coerced = index.coerceIn(info.range.first, info.range.last)
        val myGeneration = ++evGeneration
        val aeLockable = cameraCaps?.aeLockSupported ?: true
        val awbLockable = cameraCaps?.awbLockSupported ?: true
        // Best effort: if the interop control throws on this HAL, just push the EV index
        // through cameraControl directly and skip the lock dance.
        runCatching {
            if (aeLockable) {
                Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
                    CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, awbLockable)
                        .build(),
                )
            }
        }

        fun applyEv() {
            if (myGeneration != evGeneration) return // superseded by a newer slide
            val future = cam.cameraControl.setExposureCompensationIndex(coerced)
            future.addListener({
                val applied = runCatching { future.get() }.getOrDefault(coerced)
                // Only the newest in-flight call may relock; a stale relock would freeze AE
                // while a newer EV change is still converging.
                if (myGeneration == evGeneration && aeLockable) {
                    runCatching {
                        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
                            CaptureRequestOptions.Builder()
                                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, awbLockable)
                                .build(),
                        )
                    }
                }
                onApplied(applied)
            }, ContextCompat.getMainExecutor(context))
        }

        // The unlock is only a submitted request: camera2 ignores a new EV target while
        // CONTROL_AE_LOCK is still applied, and on slower HALs the unlock takes a few
        // frames to land. Give it ~5 frames before pushing the EV, or the change is
        // silently swallowed and the slider appears dead ("cannot adjust exposure").
        if (aeLockable) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ applyEv() }, 160L)
        } else {
            applyEv()
        }
    }

    fun tapToFocus(
        meteringPointFactory: MeteringPointFactory,
        x: Float,
        y: Float,
        onComplete: (Boolean) -> Unit,
    ) {
        val cam = camera ?: return
        val point = meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build()
        val future = cam.cameraControl.startFocusAndMetering(action)
        future.addListener({
            val result = runCatching { future.get() }.getOrNull()
            onComplete(result?.isFocusSuccessful ?: false)
        }, ContextCompat.getMainExecutor(context))
    }

    fun takePhoto(
        outputFile: File,
        onSaved: () -> Unit,
        onError: (ImageCaptureException) -> Unit,
    ) {
        val capture = imageCapture ?: return
        val options = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        capture.takePicture(
            options,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = onSaved()
                override fun onError(exception: ImageCaptureException) = onError(exception)
            },
        )
    }

    fun shutdown() {
        executor.shutdown()
    }
}
