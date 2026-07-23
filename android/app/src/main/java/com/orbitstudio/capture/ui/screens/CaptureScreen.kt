package com.orbitstudio.capture.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.orbitstudio.capture.capture.AeLockState
import com.orbitstudio.capture.capture.BlurScore
import com.orbitstudio.capture.capture.CaptureEngine
import com.orbitstudio.capture.capture.CoachSensors
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import com.orbitstudio.capture.capture.PanoSweep
import com.orbitstudio.capture.data.PlanCoach
import com.orbitstudio.capture.data.PlanMath
import com.orbitstudio.capture.data.ScanStage
import com.orbitstudio.capture.data.Scans
import com.orbitstudio.capture.data.ShotMeta
import com.orbitstudio.capture.ui.components.SweepLevelOverlay
import com.orbitstudio.capture.ui.components.TreasureMap
import kotlinx.coroutines.withContext
import com.orbitstudio.capture.capture.ExposureInfo
import com.orbitstudio.capture.ui.components.CoachToast
import com.orbitstudio.capture.ui.components.ExposureChip
import com.orbitstudio.capture.ui.components.ExposureSlider
import com.orbitstudio.capture.ui.components.OverlapMeter
import com.orbitstudio.capture.ui.components.PlanView
import com.orbitstudio.capture.ui.components.PrimaryButton
import com.orbitstudio.capture.ui.components.StageBadges
import com.orbitstudio.capture.ui.components.ToastState
import com.orbitstudio.capture.ui.components.ToastTone
import com.orbitstudio.capture.ui.components.TurnArrows
import androidx.compose.runtime.mutableStateListOf
import com.orbitstudio.capture.ui.theme.OrbitColors
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun CaptureScreen(nav: NavController, scanId: String) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Box(modifier = Modifier.fillMaxSize().background(OrbitColors.canvas)) {
        when {
            granted -> Viewfinder(nav, scanId)
            asked -> PermissionDeniedState(nav)
            else -> Box(Modifier.fillMaxSize()) // waiting on the system dialog
        }
    }
}

@Composable
private fun PermissionDeniedState(nav: NavController) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Orbit Capture needs the camera",
            color = OrbitColors.textPrimary,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "A scan is a walk around the room, one frame at a time. " +
                "Without camera access there is nothing to walk with. " +
                "Grant it in system settings and come back.",
            color = OrbitColors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(
            text = "Open settings",
            onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { nav.popBackStack() }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Back", color = OrbitColors.accent)
        }
    }
}

@Composable
private fun Viewfinder(nav: NavController, scanId: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val engine = remember { CaptureEngine(context) }
    val sensors = remember { CoachSensors(context) }
    val previewView = remember { PreviewView(context) }

    var cameraReady by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var aeState by remember { mutableStateOf(AeLockState.UNVERIFIED) }
    var busy by remember { mutableStateOf(false) }
    // Some budget MediaTek phones have no rotation sensor at all — the compass, level,
    // overlap meter, and turn arrows have no signal, so guidance drops to plain words.
    val sensorsAvailable = remember { sensors.available }
    var toast by remember { mutableStateOf<ToastState?>(null) }
    var focusRingAt by remember { mutableStateOf<IntOffset?>(null) }

    var shotCount by remember { mutableIntStateOf(Scans.repo.getScan(scanId)?.shots?.size ?: 0) }
    var stage by remember {
        mutableStateOf(Scans.repo.getScan(scanId)?.shots?.lastOrNull()?.stage ?: ScanStage.PERIMETER)
    }
    val enteredAtSeconds = remember { Scans.repo.getScan(scanId)?.captureSeconds ?: 0L }
    var elapsed by remember { mutableLongStateOf(enteredAtSeconds) }
    var nudged3min by remember { mutableStateOf(false) }
    var slowDownAt by remember { mutableLongStateOf(0L) }

    // Guided next-shot state. Headings are session-local (a resumed scan restarts guidance
    // from its first new shot; persisted per-shot headings are the upgrade path).
    val shotHeadings = remember { mutableStateListOf<Float>() }
    var targetHeading by remember { mutableStateOf<Float?>(null) }
    var turnDir by remember { mutableIntStateOf(1) } // +1 = clockwise/right
    // A soft frame pins guidance to the spot it was taken at, in words, until a sharp
    // frame lands. Weak shots never count as coverage on the plan view.
    var retakeMode by remember { mutableStateOf(false) }

    // Exposure compensation.
    var evInfo by remember { mutableStateOf(ExposureInfo(0, 0..0, 0f, false)) }
    var evIndex by remember { mutableIntStateOf(0) }
    var evPanelOpen by remember { mutableStateOf(false) }

    // Pano-station capture. A station = one treasure-map dot: sweep rings at each pitch
    // band (auto-fired when aligned), then zenith/nadir stills. Targets regenerate per
    // station starting at the operator's current heading, so the first frame never asks
    // for a turn. Resume: a station whose full target count is already persisted is done.
    val canonicalTargets = remember { PanoSweep.stationTargets() }
    val stationTargetCount = canonicalTargets.size
    var panoMode by remember { mutableStateOf(false) }
    var panoTargets by remember { mutableStateOf<List<PanoSweep.SweepTarget>>(emptyList()) }
    var panoCaptured by remember { mutableIntStateOf(0) }
    var currentStation by remember {
        val panoShots = Scans.repo.getScan(scanId)?.shots?.filter { it.kind != "still" } ?: emptyList()
        val maxStation = panoShots.maxOfOrNull { it.station } ?: -1
        val atMax = panoShots.count { it.station == maxStation }
        mutableIntStateOf(
            when {
                maxStation < 0 -> 0
                atMax >= stationTargetCount -> maxStation + 1
                else -> maxStation // mid-station: redo this station's sweep from scratch
            },
        )
    }
    var roomGeom by remember { mutableStateOf<RoomGeom?>(null) }
    // Route intro: a big look at the walk right as the scan opens, then back to the HUD.
    var routeIntro by remember { mutableStateOf(false) }
    // Per-shot undo/redo. Undo unlists the shot (file stays on disk so redo can relist
    // it); taking a new shot clears redo history, like any editor.
    val redoShots = remember { mutableStateListOf<ShotMeta>() }
    // A soft frame now waits for a verdict: retake it, or keep it anyway. Auto-fire
    // holds while the choice is on screen.
    var softPending by remember { mutableStateOf(false) }
    var softHeading by remember { mutableFloatStateOf(0f) }
    var softKind by remember { mutableStateOf("still") }
    // Steps counted at the moment walking began (station completed / mode entered);
    // the difference to now, at ~0.7 m a step, moves the minimap marker up the route.
    var stepAnchor by remember { mutableIntStateOf(0) }
    val stepPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Room brief from the floor plan, when this scan is linked to a sketched room:
    // per-room shot target in the ribbon plus window/obstacle coaching, sequenced
    // after the standoff hint so the single toast pill never gets trampled.
    var brief by remember { mutableStateOf<PlanCoach.RoomBrief?>(null) }
    var coveredNudged by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val b = PlanCoach.briefForScan(context, scanId)
                // Treasure map: the sketched room's walk path, subsampled into pano
                // stations (room-relative cells). Loaded once alongside the brief.
                val geom = PlanCoach.linkedRoom(context, scanId)?.let { (plan, room) ->
                    val startNear = room.startCol?.let { c -> room.startRow?.let { r -> c to r } }
                    val path = PlanMath.walkPath(room, plan.features, plan.scaleMPerCell, startNear)
                        .map { (c, r) -> (c - room.col) to (r - room.row) }
                    RoomGeom(
                        cols = room.cols,
                        rows = room.rows,
                        scaleMPerCell = plan.scaleMPerCell,
                        path = path,
                        stationIndices = PanoSweep.stationIndices(path, plan.scaleMPerCell),
                        startDirDeg = room.startDirDeg ?: 0f,
                    )
                }
                b to geom
            }.getOrNull()
        }
        if (loaded != null) {
            roomGeom = loaded.second
            if (loaded.second != null) routeIntro = true
            val b = loaded.first
            if (b != null) {
                brief = b
                b.notes.forEachIndexed { i, note ->
                    delay(6000L + i * 8000L)
                    toast = ToastState(note, ToastTone.INFO)
                }
            }
        }
    }
    // The route flyover dismisses itself after a good look; a tap dismisses it sooner.
    LaunchedEffect(routeIntro) {
        if (routeIntro) { delay(3200); routeIntro = false }
    }
    // Walk-the-path completion: once the sketch's shot target is reached, tell them once.
    LaunchedEffect(shotCount, brief) {
        val target = brief?.targetShots ?: return@LaunchedEffect
        if (!coveredNudged && shotCount >= target) {
            coveredNudged = true
            toast = ToastState("You have covered this room. Review it, or keep going.", ToastTone.SUCCESS)
        }
    }

    fun persistElapsed() {
        val scan = Scans.repo.getScan(scanId) ?: return
        Scans.repo.updateScan(scan.copy(captureSeconds = elapsed))
    }

    DisposableEffect(Unit) {
        sensors.start()
        // The chip shows the camera's own reported AE state, not our request. Only a
        // verified LOCKED reading counts as exposure-locked for the bundle rig flag.
        engine.setAeStateListener { state ->
            aeState = state
            val locked = state == AeLockState.LOCKED
            val scan = Scans.repo.getScan(scanId)
            if (scan != null && scan.exposureLocked != locked) {
                Scans.repo.updateScan(scan.copy(exposureLocked = locked))
            }
        }
        engine.start(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            onReady = {
                cameraReady = true
                // Request the lock; the chip only turns green when the camera
                // reports CONTROL_AE_STATE_LOCKED back through the listener above.
                engine.lockExposure { }
                evInfo = engine.exposureInfo()
                evIndex = evInfo.index
            },
            onError = { cameraError = it.message ?: "The camera could not start." },
        )
        onDispose {
            persistElapsed()
            engine.setAeStateListener(null)
            sensors.stop()
            engine.shutdown()
        }
    }

    // One-time standoff hint, then a real per-second timer with the 3-minute nudge.
    LaunchedEffect(Unit) {
        toast = ToastState(
            "Stay about a meter from what matters. Never closer than a third of one.",
            ToastTone.INFO,
        )
        while (true) {
            delay(1000)
            elapsed += 1
            if (!nudged3min && elapsed - enteredAtSeconds >= 180) {
                nudged3min = true
                toast = ToastState(
                    "Three minutes in this room. Coverage beats duration - consider reviewing.",
                    ToastTone.INFO,
                )
            }
        }
    }

    // Turn-rate coaching, throttled to one reminder per few seconds.
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            if (sensors.yawRateDegPerSec > 40f && elapsed - slowDownAt > 5) {
                slowDownAt = elapsed
                toast = ToastState("Slow down. Let each frame land.", ToastTone.WARNING)
            }
        }
    }

    // One capture routine shared by the shutter button (still mode) and the pano
    // auto-fire loop. Attitude rides into ShotMeta for every shot; the guidance that
    // follows a save differs by mode: stills run the retake/heading coach, pano frames
    // advance the sweep only when sharp (a soft frame re-shoots the same target).
    fun takeShot(kind: String, stationIdx: Int) {
        if (busy || !cameraReady) return
        busy = true
        val index = shotCount
        val overlapAtPress = sensors.overlapPct
        val stageAtPress = stage
        val headingAtPress = sensors.headingDeg
        val pitchAtPress = sensors.pitchDeg
        val rollAtPress = sensors.rollDeg
        val dir = Scans.repo.photosDir(scanId)
        val file = File(dir, String.format(Locale.US, "shot_%04d.jpg", index))
        engine.takePhoto(
            outputFile = file,
            onSaved = {
                scope.launch(Dispatchers.IO) {
                    val blur = BlurScore.compute(file)
                    val scan = Scans.repo.getScan(scanId)
                    if (scan != null) {
                        val meta = ShotMeta(
                            fileName = file.name,
                            takenAtMs = System.currentTimeMillis(),
                            blurScore = blur,
                            overlapPct = overlapAtPress,
                            stage = stageAtPress,
                            yawDeg = headingAtPress,
                            pitchDeg = pitchAtPress,
                            rollDeg = rollAtPress,
                            kind = kind,
                            station = stationIdx,
                        )
                        Scans.repo.updateScan(
                            scan.copy(
                                shots = scan.shots + meta,
                                captureSeconds = elapsed,
                            ),
                        )
                    }
                    sensors.markShotTaken()
                    redoShots.clear() // a new shot invalidates redo history
                    if (kind == "zenith" || kind == "nadir") {
                        // Ceilings and floors are often texture-poor, so the blur score
                        // reads low even on a good frame — never gate the poles on it.
                        panoCaptured += 1
                    } else if (kind != "still") {
                        if (blur < 80.0) {
                            softKind = kind
                            softPending = true
                            toast = ToastState("That frame came out soft.", ToastTone.WARNING)
                        } else {
                            panoCaptured += 1
                        }
                    } else if (blur < 80.0) {
                        softKind = "still"
                        softHeading = headingAtPress
                        softPending = true
                        // Soft frame: guide her back in words, pin the target
                        // to this spot, and leave the plan-view wedge unfilled.
                        retakeMode = true
                        targetHeading = normalize360(headingAtPress)
                        toast = ToastState(
                            "That one came out soft. Go back to your last spot and retake it.",
                            ToastTone.WARNING,
                        )
                    } else {
                        // Guidance: learn the walking direction from the last
                        // two sharp shots, then suggest the next heading.
                        // 18 degrees at a ~66 degree FOV lands near 75% overlap.
                        retakeMode = false
                        val prev = shotHeadings.lastOrNull()
                        shotHeadings.add(headingAtPress)
                        if (prev != null) {
                            val moved = signedAngle(prev, headingAtPress)
                            if (abs(moved) > 4f) turnDir = if (moved > 0) 1 else -1
                        }
                        targetHeading = normalize360(headingAtPress + 18f * turnDir)
                    }
                    shotCount = index + 1
                    busy = false
                }
            },
            onError = {
                busy = false
                toast = ToastState(
                    "That frame did not save. Try again.",
                    ToastTone.DANGER,
                )
            },
        )
    }

    // Undo unlists the newest shot but leaves its file on disk, so redo is a pure
    // relist. The bundle only ships listed shots, so an undone frame never exports.
    fun undoShot() {
        if (busy) return
        val scan = Scans.repo.getScan(scanId) ?: return
        val last = scan.shots.lastOrNull() ?: return
        Scans.repo.updateScan(scan.copy(shots = scan.shots.dropLast(1)))
        redoShots.add(last)
        shotCount = scan.shots.size - 1
        softPending = false
        if (last.kind != "still") {
            if (panoCaptured > 0) panoCaptured -= 1
        } else {
            if (shotHeadings.isNotEmpty()) shotHeadings.removeAt(shotHeadings.lastIndex)
            retakeMode = false
        }
        toast = ToastState("Removed the last shot.", ToastTone.INFO)
    }

    fun redoShot() {
        if (busy) return
        val meta = redoShots.lastOrNull() ?: return
        redoShots.removeAt(redoShots.lastIndex)
        val scan = Scans.repo.getScan(scanId) ?: return
        if (!File(Scans.repo.photosDir(scanId), meta.fileName).isFile) return
        Scans.repo.updateScan(scan.copy(shots = scan.shots + meta))
        shotCount = scan.shots.size + 1
        if (meta.kind != "still") panoCaptured += 1 else shotHeadings.add(meta.yawDeg)
        toast = ToastState("Restored the shot.", ToastTone.INFO)
    }

    // Pano auto-fire: photosphere-style. Once a station is armed (targets exist), any
    // moment the phone points at the current target and holds still, the frame takes
    // itself; the operator never reaches for the shutter mid-sweep. Arming is a
    // deliberate tap per station so nothing fires while walking between dots.
    LaunchedEffect(panoMode, cameraReady) {
        if (!panoMode || !cameraReady) return@LaunchedEffect
        while (true) {
            delay(150)
            // Paused while: a shot is in flight, the EV panel is open (adjusting exposure
            // must never race the shutter), a soft frame awaits its keep-or-retake
            // verdict, or the route intro is still up.
            if (busy || evPanelOpen || softPending || routeIntro || panoTargets.isEmpty()) continue
            val progress = PanoSweep.progress(panoTargets, panoCaptured)
            if (progress.stationComplete) {
                val total = roomGeom?.stationIndices?.size ?: 1
                currentStation += 1
                panoTargets = emptyList()
                panoCaptured = 0
                stepAnchor = sensors.stepCount // walking to the next dot starts now
                toast = if (currentStation >= total) {
                    ToastState("All stations done. Review the scan, or add extra panos.", ToastTone.SUCCESS)
                } else {
                    ToastState("Station done. Walk to dot ${currentStation + 1}, then start it.", ToastTone.SUCCESS)
                }
                continue
            }
            val target = panoTargets[panoCaptured]
            if (PanoSweep.aligned(target, sensors.headingDeg, sensors.pitchDeg, sensors.yawRateDegPerSec)) {
                val kind = when (target.kind) {
                    PanoSweep.TargetKind.PANO_FRAME -> "pano"
                    PanoSweep.TargetKind.ZENITH -> "zenith"
                    PanoSweep.TargetKind.NADIR -> "nadir"
                }
                takeShot(kind = kind, stationIdx = currentStation)
            }
        }
    }

    if (cameraError != null) {
        CameraErrorState(nav, cameraError.orEmpty())
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(cameraReady) {
                    detectTapGestures(onTap = { offset ->
                        if (cameraReady) {
                            focusRingAt = IntOffset(offset.x.toInt(), offset.y.toInt())
                            engine.tapToFocus(previewView.meteringPointFactory, offset.x, offset.y) {
                                engine.lockExposure { } // chip updates from the verified listener
                                scope.launch {
                                    delay(600)
                                    focusRingAt = null
                                }
                            }
                        }
                    })
                },
        )
        // Pano alignment instrument: artificial-horizon line + reticle over the preview.
        // Fly the line into the brackets and hold; green ring = auto-fire window open.
        if (panoMode && panoTargets.isNotEmpty() && sensorsAvailable) {
            val target = panoTargets.getOrNull(panoCaptured)
            if (target != null) {
                SweepLevelOverlay(
                    rollDeg = sensors.rollDeg,
                    pitchErrorDeg = target.pitchDeg - sensors.pitchDeg,
                    aligned = PanoSweep.aligned(target, sensors.headingDeg, sensors.pitchDeg, sensors.yawRateDegPerSec),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        focusRingAt?.let { at ->
            Box(
                modifier = Modifier
                    .offset { IntOffset(at.x - 32.dp.roundToPx(), at.y - 32.dp.roundToPx()) }
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .padding(2.dp),
            ) {
                Surface(
                    color = Color.Transparent,
                    shape = CircleShape,
                    border = androidx.compose.foundation.BorderStroke(2.dp, OrbitColors.accent),
                    modifier = Modifier.fillMaxSize(),
                ) {}
            }
        }

        // Top status ribbon: back, AE state (real), count + timer (monospace). The scrim
        // reaches the screen top; content is inset below the status bar and camera cutout.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(OrbitColors.canvas.copy(alpha = 0.72f))
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                persistElapsed()
                nav.popBackStack()
            }) { Text("Back", color = OrbitColors.accent) }
            Spacer(Modifier.weight(1f))
            AeChip(aeState)
            if (evInfo.supported) {
                Spacer(Modifier.width(8.dp))
                ExposureChip(
                    evLabel = evLabel(evIndex, evInfo.step),
                    active = evPanelOpen,
                    onClick = { evPanelOpen = !evPanelOpen },
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = brief?.let {
                    String.format(Locale.US, "%d / ~%d  %s", shotCount, it.targetShots, timeString(elapsed))
                } ?: String.format(Locale.US, "%d photos  %s", shotCount, timeString(elapsed)),
                color = OrbitColors.textPrimary,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(end = 12.dp),
            )
        }

        // Plan view: person-with-a-flashlight minimap, top-right under the ribbon.
        // Hidden when there is no rotation sensor — a frozen compass helps no one.
        if (sensorsAvailable) {
            val geom = roomGeom
            val mapModifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Right),
                )
                .padding(top = 56.dp, end = 12.dp)
            if (panoMode && geom != null) {
                // Minimap marker, kart-style but honest: pinned to the station dot while
                // sweeping (a measured anchor), dead-reckoned up the route by hardware
                // step counts while walking to the next dot, clamped at that dot.
                val stations = geom.stationIndices
                val marker: Pair<Float, Float>? = when {
                    stations.isEmpty() -> null
                    panoTargets.isNotEmpty() || currentStation == 0 || currentStation >= stations.size -> {
                        val idx = stations[currentStation.coerceIn(0, stations.size - 1)]
                        geom.path[idx].first.toFloat() to geom.path[idx].second.toFloat()
                    }
                    else -> PanoSweep.positionAlongPath(
                        path = geom.path,
                        scaleMPerCell = geom.scaleMPerCell,
                        fromIndex = stations[currentStation - 1],
                        toIndex = stations[currentStation],
                        metersWalked = (sensors.stepCount - stepAnchor).coerceAtLeast(0) * 0.7f,
                    )
                }
                TreasureMap(
                    roomCols = geom.cols,
                    roomRows = geom.rows,
                    pathCells = geom.path,
                    stationCells = geom.stationCells,
                    currentStation = currentStation,
                    markerCell = marker,
                    headingDeg = sensors.headingDeg + geom.startDirDeg,
                    modifier = mapModifier.size(150.dp),
                )
            } else if (geom != null) {
                // Still mode with a sketched room: the route stays on screen while
                // shooting — the line to follow, not just a heading radar.
                TreasureMap(
                    roomCols = geom.cols,
                    roomRows = geom.rows,
                    pathCells = geom.path,
                    stationCells = geom.stationCells,
                    currentStation = -1,
                    markerCell = null,
                    headingDeg = sensors.headingDeg + geom.startDirDeg,
                    modifier = mapModifier.size(150.dp),
                )
            } else {
                PlanView(
                    headingDeg = sensors.headingDeg,
                    shotHeadingsDeg = shotHeadings,
                    targetHeadingDeg = targetHeading,
                    modifier = mapModifier.size(112.dp),
                )
            }
        }

        // Bottom coaching stack: guidance, meters, stages, shutter row. Scrim reaches the
        // screen bottom; content is inset above the gesture navigation bar.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(OrbitColors.canvas.copy(alpha = 0.72f))
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (evPanelOpen && evInfo.supported) {
                ExposureSlider(
                    index = evIndex,
                    range = evInfo.range,
                    step = evInfo.step,
                    onIndexChange = { idx ->
                        engine.setExposureCompensation(idx) { applied -> evIndex = applied }
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                )
            }

            // Guided-path progress: how far through the sketch's computed shot target you are.
            // The sketch's whole purpose is to drive this walk. (Still mode only: pano mode
            // has its own station counters below.)
            if (!panoMode) brief?.let { b ->
                val target = b.targetShots.coerceAtLeast(1)
                val frac = (shotCount.toFloat() / target).coerceIn(0f, 1f)
                val done = shotCount >= target
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("WALK PROGRESS", color = OrbitColors.textTertiary, style = MaterialTheme.typography.labelSmall)
                    Text(
                        if (done) "Room covered" else "$shotCount of ~$target",
                        color = if (done) OrbitColors.success else OrbitColors.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth().height(5.dp)
                        .clip(RoundedCornerShape(50)).background(OrbitColors.hairline12),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(frac).height(5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (done) OrbitColors.success else OrbitColors.accent),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            if (panoMode) {
                // Pano HUD: station/frame/still counters, then the sweep cue. A station is
                // armed with one deliberate tap so nothing auto-fires while walking a dot.
                val stationsTotal = roomGeom?.stationIndices?.size ?: 1
                val displayTargets = if (panoTargets.isEmpty()) canonicalTargets else panoTargets
                val prog = PanoSweep.progress(displayTargets, panoCaptured)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        String.format(Locale.US, "PANO %d/%d", (currentStation + 1).coerceAtMost(stationsTotal.coerceAtLeast(1)), stationsTotal.coerceAtLeast(1)),
                        color = OrbitColors.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        String.format(Locale.US, "FRAMES %d/%d", prog.frameDone, prog.frameTotal),
                        color = OrbitColors.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        String.format(Locale.US, "UP+DOWN %d/%d", prog.stillsDone, prog.stillsTotal),
                        color = OrbitColors.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (panoTargets.isEmpty()) {
                    val allDone = currentStation >= stationsTotal
                    PrimaryButton(
                        text = if (allDone) "Add another pano here" else "Start pano ${currentStation + 1} here",
                        onClick = {
                            panoTargets = PanoSweep.stationTargets(startYawDeg = sensors.headingDeg)
                            panoCaptured = 0
                            toast = ToastState(
                                "Sweep the ring slowly. Frames take themselves when you hold on a dot.",
                                ToastTone.INFO,
                            )
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                } else {
                    val target = panoTargets.getOrNull(panoCaptured)
                    val pitchDelta = target?.let { it.pitchDeg - sensors.pitchDeg } ?: 0f
                    val yawDelta = target?.let { sensors.signedDeltaTo(it.yawDeg) } ?: 0f
                    val holding = target != null &&
                        abs(pitchDelta) <= PanoSweep.PITCH_TOL_DEG &&
                        (target.kind != PanoSweep.TargetKind.PANO_FRAME || abs(yawDelta) <= PanoSweep.YAW_TOL_DEG)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        val yawCueNeeded = target?.kind == PanoSweep.TargetKind.PANO_FRAME &&
                            abs(pitchDelta) <= PanoSweep.PITCH_TOL_DEG
                        if (yawCueNeeded && yawDelta < -PanoSweep.YAW_TOL_DEG) TurnArrows(signedDeltaDeg = yawDelta)
                        Text(
                            text = when {
                                target == null -> "Station done."
                                target.kind == PanoSweep.TargetKind.ZENITH -> "Point straight up at the ceiling and hold."
                                target.kind == PanoSweep.TargetKind.NADIR -> "Point straight down at the floor and hold."
                                abs(pitchDelta) > PanoSweep.PITCH_TOL_DEG ->
                                    if (pitchDelta > 0) "Tilt up to the ring." else "Tilt down to the ring."
                                abs(yawDelta) > PanoSweep.YAW_TOL_DEG ->
                                    if (yawDelta > 0) "Turn right to the next dot." else "Turn left to the next dot."
                                else -> "Hold still. Capturing."
                            },
                            color = if (holding) OrbitColors.success else OrbitColors.textPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp),
                        )
                        if (yawCueNeeded && yawDelta > PanoSweep.YAW_TOL_DEG) TurnArrows(signedDeltaDeg = yawDelta)
                    }
                }
                if (sensorsAvailable) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        LevelBubble(rollDeg = sensors.rollDeg)
                    }
                    Spacer(Modifier.height(10.dp))
                }
            } else {
            // Next-shot guidance. With a rotation sensor: words plus arrows toward the
            // marker (retake flow uses words only). Without one: non-directional coaching,
            // since there is no reliable heading to point toward.
            val delta = if (sensorsAvailable) targetHeading?.let { sensors.signedDeltaTo(it) } else null
            val aligned = delta != null && abs(delta) <= 8f
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                if (!retakeMode && delta != null && delta < -8f) TurnArrows(signedDeltaDeg = delta)
                Text(
                    text = when {
                        !sensorsAvailable && retakeMode ->
                            "That one was soft. Step back and retake it."
                        !sensorsAvailable && shotCount == 0 ->
                            "Take overlapping photos as you move around the room."
                        !sensorsAvailable ->
                            "Move a little, keep most of the last shot in frame, shoot again."
                        shotCount == 0 -> "Take the first shot, then follow the arrows."
                        retakeMode && aligned -> "You are back. Retake the shot."
                        retakeMode -> "Go back to your last spot and retake it."
                        aligned -> "Good. Take the shot."
                        delta != null && delta > 0 -> "Turn right to the marker."
                        delta != null -> "Turn left to the marker."
                        else -> "Follow the arrows to the next shot."
                    },
                    color = when {
                        aligned -> OrbitColors.success
                        retakeMode -> OrbitColors.warning
                        else -> OrbitColors.textPrimary
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
                if (!retakeMode && delta != null && delta > 8f) TurnArrows(signedDeltaDeg = delta)
            }
            // Live meters need the sensor; hide them rather than show frozen zeros.
            if (sensorsAvailable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            "OVERLAP",
                            color = OrbitColors.textTertiary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        OverlapMeter(pct = sensors.overlapPct)
                    }
                    LevelBubble(rollDeg = sensors.rollDeg)
                }
                Spacer(Modifier.height(10.dp))
            }
            StageBadges(
                current = stage,
                onAdvance = {
                    val next = when (stage) {
                        ScanStage.PERIMETER -> ScanStage.INTERIOR
                        ScanStage.INTERIOR -> ScanStage.LOOP_CLOSED
                        ScanStage.LOOP_CLOSED -> ScanStage.LOOP_CLOSED
                    }
                    if (next != stage) {
                        stage = next
                        toast = if (next == ScanStage.LOOP_CLOSED) {
                            ToastState(
                                "End near where you started so the reconstruction can check itself.",
                                ToastTone.INFO,
                            )
                        } else {
                            ToastState("Interior pass. Weave overlapping lines through the room.", ToastTone.INFO)
                        }
                    }
                },
            )
            }

            // A soft frame stays the operator's call: retake it, or keep it and move on.
            if (softPending) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(OrbitColors.warningSoft)
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Soft frame.",
                        color = OrbitColors.textPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { softPending = false }) {
                        Text("Retake", color = OrbitColors.warning, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = {
                        softPending = false
                        if (softKind == "still") {
                            retakeMode = false
                            val prev = shotHeadings.lastOrNull()
                            shotHeadings.add(softHeading)
                            if (prev != null) {
                                val moved = signedAngle(prev, softHeading)
                                if (abs(moved) > 4f) turnDir = if (moved > 0) 1 else -1
                            }
                            targetHeading = normalize360(softHeading + 18f * turnDir)
                        } else {
                            panoCaptured += 1
                        }
                    }) {
                        Text("Keep anyway", color = OrbitColors.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            // Per-shot undo/redo, always at hand.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { undoShot() }, enabled = shotCount > 0 && !busy) {
                    Text(
                        "Undo shot",
                        color = if (shotCount > 0) OrbitColors.accent else OrbitColors.textTertiary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TextButton(onClick = { redoShot() }, enabled = redoShots.isNotEmpty() && !busy) {
                    Text(
                        "Redo",
                        color = if (redoShots.isNotEmpty()) OrbitColors.accent else OrbitColors.textTertiary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                ShutterButton(
                    busy = busy,
                    enabled = cameraReady && !busy,
                    modifier = Modifier.align(Alignment.Center),
                    onClick = { takeShot(kind = "still", stationIdx = -1) },
                )
                TextButton(
                    onClick = {
                        panoMode = !panoMode
                        panoTargets = emptyList()
                        panoCaptured = 0
                        stepAnchor = sensors.stepCount
                        if (panoMode && !sensorsAvailable) {
                            panoMode = false
                            toast = ToastState(
                                "Pano needs the rotation sensor this phone does not have.",
                                ToastTone.WARNING,
                            )
                        } else if (panoMode && sensors.stepsAvailable &&
                            android.os.Build.VERSION.SDK_INT >= 29 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            // Step counting moves the minimap marker between dots; declining
                            // just leaves the marker anchored on the dots themselves.
                            stepPermLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                        }
                    },
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Text(
                        if (panoMode) "Stills" else "Pano",
                        color = if (panoMode) OrbitColors.warning else OrbitColors.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TextButton(
                    onClick = {
                        persistElapsed()
                        nav.navigate("review/$scanId")
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                    enabled = shotCount > 0,
                ) {
                    Text(
                        "Review",
                        color = if (shotCount > 0) OrbitColors.accent else OrbitColors.textTertiary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Route flyover: right as the scan opens, the walk fills the screen — dots,
        // road, and where you start — then hands back to the viewfinder.
        val introGeom = roomGeom
        if (routeIntro && introGeom != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(OrbitColors.canvas.copy(alpha = 0.88f))
                    .pointerInput(Unit) { detectTapGestures(onTap = { routeIntro = false }) },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "YOUR ROUTE",
                    color = OrbitColors.textSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                TreasureMap(
                    roomCols = introGeom.cols,
                    roomRows = introGeom.rows,
                    pathCells = introGeom.path,
                    stationCells = introGeom.stationCells,
                    currentStation = currentStation,
                    markerCell = introGeom.stationIndices.getOrNull(currentStation.coerceAtLeast(0))
                        ?.let { introGeom.path.getOrNull(it) }
                        ?.let { it.first.toFloat() to it.second.toFloat() },
                    headingDeg = sensors.headingDeg + introGeom.startDirDeg,
                    modifier = Modifier.fillMaxWidth(0.88f).height(340.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Walk the dots in order. Tap to start.",
                    color = OrbitColors.textPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        CoachToast(
            toast = toast,
            onDismiss = { toast = null },
            modifier = Modifier.fillMaxSize().padding(bottom = 168.dp),
        )
    }
}

@Composable
private fun AeChip(state: AeLockState) {
    // Honest across hardware: green only when the camera itself reports a locked state.
    // "AE lock on" = requested but the device never reports state (common on MediaTek).
    // "Auto exposure" = the device can't lock AE at all — don't claim otherwise.
    val (label, color, bg) = when (state) {
        AeLockState.LOCKED -> Triple("AE locked", OrbitColors.success, OrbitColors.successSoft)
        AeLockState.UNVERIFIED -> Triple("AE lock on", OrbitColors.textSecondary, OrbitColors.hairline12)
        AeLockState.UNLOCKED -> Triple("AE unlocked", OrbitColors.warning, OrbitColors.warningSoft)
        AeLockState.UNSUPPORTED -> Triple("Auto exposure", OrbitColors.textSecondary, OrbitColors.hairline12)
    }
    Text(
        text = label,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

// Horizontal level bubble: centered dot means the phone is upright; amber past 3 degrees.
@Composable
private fun LevelBubble(rollDeg: Float, modifier: Modifier = Modifier) {
    val level = abs(rollDeg) < 3f
    val trackWidth = 96.dp
    val fractionOff = (rollDeg / 15f).coerceIn(-1f, 1f)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "LEVEL",
            color = OrbitColors.textTertiary,
            style = MaterialTheme.typography.labelSmall,
        )
        Box(
            modifier = Modifier
                .width(trackWidth)
                .height(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(OrbitColors.hairline20),
            )
            Box(
                modifier = Modifier
                    .offset(x = trackWidth / 2 * fractionOff)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (level) OrbitColors.success else OrbitColors.warning),
            )
        }
    }
}

@Composable
private fun ShutterButton(
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = androidx.compose.animation.core.tween(150),
        label = "shutterScale",
    )
    Box(
        modifier = modifier
            .size(76.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = OrbitColors.accent,
                strokeWidth = 3.dp,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .padding(2.dp),
            ) {
                Surface(
                    color = Color.Transparent,
                    shape = CircleShape,
                    border = androidx.compose.foundation.BorderStroke(2.dp, OrbitColors.canvas),
                    modifier = Modifier.fillMaxSize(),
                ) {}
            }
        }
    }
}

@Composable
private fun CameraErrorState(nav: NavController, message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "The camera could not start",
            color = OrbitColors.textPrimary,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            color = OrbitColors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(
            text = "Back to scans",
            onClick = { nav.popBackStack() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The sketched room's geometry for the treasure map, room-relative grid cells.
 *  Stations are indices into [path] so the minimap marker can dead-reckon along the
 *  route between dots. */
private data class RoomGeom(
    val cols: Int,
    val rows: Int,
    val scaleMPerCell: Float,
    val path: List<Pair<Int, Int>>,
    val stationIndices: List<Int>,
    // Map calibration: the operator declared they start the scan facing this sketch
    // direction, so map arrow rotation = phone heading + this offset. 0 when unset.
    val startDirDeg: Float = 0f,
) {
    val stationCells: List<Pair<Int, Int>> = stationIndices.map { path[it] }
}

private fun timeString(totalSeconds: Long): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", m, s)
}

private fun normalize360(deg: Float): Float = ((deg % 360f) + 360f) % 360f

/** Signed shortest angular distance from -> to, in -180..180; positive = clockwise/right. */
private fun signedAngle(from: Float, to: Float): Float {
    var d = normalize360(to) - normalize360(from)
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

private fun evLabel(index: Int, step: Float): String {
    val ev = index * step
    return if (ev == 0f) "EV 0.0" else String.format(Locale.US, "EV %+.1f", ev)
}
