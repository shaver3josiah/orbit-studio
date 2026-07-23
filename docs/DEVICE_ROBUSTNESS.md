# Orbit Capture — device robustness

Research + documentation only. Nothing in this file changes code; it records what the
app already does (and does not do) for the two device families the field team carries,
and what is still unverified without hardware in hand.

## Target devices

- **Samsung One UI, flagship down to Galaxy A (MediaTek + Exynos).** The Galaxy A tier is
  the stress case: a budget MediaTek ISP with a thinner camera2 feature set than the
  flagship Exynos/Snapdragon models, plus One UI's own font/display-scaling and
  storage-cleanup behavior layered on top.
- **Nothing CMF (CMF Phone 1 / 2 Pro, MediaTek Dimensity).** Same MediaTek ISP concerns as
  the Galaxy A, plus a punch-hole cutout and a stock-AOSP-adjacent shell that runs
  edge-to-edge with fewer OEM insets to lean on than One UI provides.

## Camera2 capability gaps

All of the following live in `capture/CaptureEngine.kt`. The rule throughout: query the
characteristic once at bind time, never assume a feature exists, and let the UI show an
honest "unsupported"/"unverified" state instead of a lock icon that lies.

- **AE lock availability (`CONTROL_AE_LOCK_AVAILABLE`).** If assumed present, `lockExposure()`
  would request `CONTROL_AE_LOCK` on hardware that silently ignores it, and the UI would show
  a lock request with no real effect. `computeCaps()` reads the characteristic into
  `CameraCaps.aeLockSupported`; `lockExposure()` and `setExposureCompensation()` only set the
  key when `caps.aeLockSupported` is true.
- **`CONTROL_AE_STATE` reporting.** Some MediaTek HALs never populate this in the capture
  result even when AE lock itself works. If assumed present, the AE chip would either stay
  falsely "unlocked" forever or require a timeout hack. Instead the session capture callback
  in `start()` reads `CaptureResult.CONTROL_AE_STATE` per frame and maps it through the
  `AeLockState` enum: `LOCKED` / `UNLOCKED` when the camera reports a value, `UNVERIFIED` when
  it's `null` (device never reports it) or the lock was just requested, and `UNSUPPORTED` when
  `CameraCaps.aeLockSupported` is false. The UI (`AeChip` in `CaptureScreen.kt`) only ever
  shows what `CaptureEngine` verified, never the raw request.
- **AWB lock availability (`CONTROL_AWB_LOCK_AVAILABLE`).** Same shape as AE: `CameraCaps.awbLockSupported`
  gates whether `CONTROL_AWB_LOCK` is included in the capture request options, in both
  `lockExposure()` and the unlock/relock dance inside `setExposureCompensation()`.
- **Exposure compensation range.** Budget ISPs can report a zero-width range or mark it
  unsupported entirely. If assumed present, dragging the EV slider would throw or silently no-op.
  `exposureInfo()` checks `cameraInfo.exposureState.isExposureCompensationSupported` first and
  returns `ExposureInfo(supported = false)` when it isn't; `CaptureScreen` only renders the
  `ExposureChip`/`ExposureSlider` when `evInfo.supported` is true.
- **EXIF `FocalLengthIn35mmFilm`.** Not a camera2 characteristic but the same family of gap:
  some ISPs don't populate it. `BundleBuilder.focalLength35mm()` reads
  `ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM`, treats `0` (Android's "absent" sentinel) as
  absent, and wraps the whole read in try/catch — the manifest's `focal_length_35mm_equiv_mm`
  key is simply omitted rather than written as a bogus `0`.
- **Interop reads throwing outright.** `LEGACY`-level hardware can throw on
  `Camera2CameraInfo.getCameraCharacteristic` calls. `computeCaps()` wraps the whole read in a
  try/catch and falls back to `CameraCaps(aeLockSupported = false, awbLockSupported = false,
  hardwareLevel = "unknown")` — nothing works, but nothing crashes either.

## Sensors

Budget MediaTek devices sometimes lack `TYPE_ROTATION_VECTOR` (fused accel+gyro+mag).
`CoachSensors.kt` now picks the best available sensor: `TYPE_ROTATION_VECTOR` first, else
`TYPE_GAME_ROTATION_VECTOR` (same vector math via `getRotationMatrixFromVector`, no
magnetometer so absolute north drifts — but relative heading over a 1–3 minute scan is fine and
far better than a dead compass). `onSensorChanged` accepts events from either type. A new public
`val available: Boolean` is true only when one of the two was found; `start()` remains a safe
no-op (`rotationSensor?.let { ... }`) when neither exists.

`CaptureScreen.kt` reads `available` and branches on it: when there is no usable sensor it hides
the plan-view minimap, the level bubble, and the overlap meter (a frozen compass or a stuck
100% meter is worse than nothing), suppresses the turn arrows, and switches guidance to plain
non-directional coaching ("Move a little, keep most of the last shot in frame, shoot again").
So a sensor-less CMF Phone or low-end Galaxy A still gets a working capture flow, just without
the directional overlay.

## Display cutouts and edge-to-edge

`targetSdk 35` makes edge-to-edge the default window behavior — the app draws under the status
bar and punch-hole cutout area unless it insets its content. `MainActivity.onCreate()` calls
`enableEdgeToEdge()` explicitly (intentional, and consistent on API levels below 35 too).

Every screen that shows chrome then insets it: `HomeScreen.kt`, `ReviewScreen.kt`,
`DoneScreen.kt`, `BundleScreen.kt`, and `FloorPlanScreen.kt` apply `Modifier.safeDrawingPadding()`
to their root container (order `fillMaxSize().background(...).safeDrawingPadding()`, so the
background still paints edge-to-edge with no black bars while only the children pull in from the
system bars). `CaptureScreen.kt` is deliberately different: the camera preview (`AndroidView`
wrapping `PreviewView`) stays `fillMaxSize()` full-bleed under the cutout — a viewfinder that
stops short of the edges wastes frame — while the top ribbon and bottom coaching stack use
`windowInsetsPadding(WindowInsets.safeDrawing.only(...))` so their translucent scrims reach the
screen edge but the text and controls sit clear of the status bar, cutout, and gesture bar.

## Storage

Two storage surfaces, with different exposure. Scans and photos live under
`context.filesDir/scans/<id>/...` — app-private internal storage that Android never scopes
regardless of OEM, so Samsung's shared-storage cleanup can't touch a scan in progress. That's
the safe path and it needs no MediaStore work.

The *finished* `bundle.zip`, however, is written to shared Downloads so the user can move it to
the laptop, and that's where OEM storage policy bites. `BundleScreen.kt` now hardens it: the
`MediaStore.insert(...)` into Downloads is wrapped for `SecurityException` and null-checked
(some Samsung/MediaTek ROMs refuse or return null), falling back to the app's own
`getExternalFilesDir(DIRECTORY_DOWNLOADS)` and sharing via the existing FileProvider
(`com.orbitstudio.capture.fileprovider`) with an inline "Saved to the app folder" note instead
of crashing. On API 29+ it uses the two-phase `IS_PENDING` write (insert pending, stream the
zip, clear pending) so the file actually appears in Downloads on One UI rather than staying
invisible. The legacy `WRITE_EXTERNAL_STORAGE` permission (`maxSdkVersion="28"`) covers the
pre-Q path.

## Font and display scaling

One UI's large-font and large-display-size accessibility settings scale both `sp` (text) and,
on newer One UI, general layout density. Current state: this codebase uses Compose
`MaterialTheme.typography` throughout (`sp`-based, so it honors system font scale correctly by
default) and layout in `dp`, which Compose does not auto-scale with the display-size setting —
that is standard Android behavior, not a bug introduced here. No screen in this pass pins a
fixed `sp` size or disables scaling. Residual risk: nothing in the reviewed files was tested
against extreme One UI scale multipliers (font scale beyond ~130% is common on Galaxy A
accessibility presets), so tight rows like the `CaptureScreen` top ribbon (back button + AE
chip + EV chip + counter, all in one `Row`) or `StageBadges` could wrap or clip at large scale.
That screen is not owned here, so this is a flag, not a fix.

## Not verifiable without hardware

This is defensive coding validated by reading the code and by the green `compileDebugKotlin`
build below — not by running on a device. Specifically unconfirmed without a physical Galaxy A
and a physical CMF Phone:

- Whether `CONTROL_AE_STATE` is actually `null` on these specific ISPs (the `UNVERIFIED` path
  exists for this case but has not been observed firing on real hardware).
- The real `CameraCaps` values (`aeLockSupported`, `awbLockSupported`, `hardwareLevel`) these
  specific SoCs report — camera2 characteristic support varies by firmware revision, not just
  by chip family.
- Whether `TYPE_ROTATION_VECTOR` is actually absent (the `GAME_ROTATION_VECTOR` fallback and
  `available`-driven UI handle absence) versus merely slow or noisy on these units — a noisy
  sensor still reports as `available` and would degrade guidance quality, which only field use
  will show.
- Real `MediaStore` insert/`IS_PENDING` behavior for the bundle write on One UI, and whether the
  app-folder fallback path is the one that actually fires on a given Samsung ROM.
- Real `filesDir` quota/cleanup behavior under Samsung's storage-saver mode during a long
  ~300-photo scan.
- Actual rendered punch-hole/cutout geometry under `enableEdgeToEdge()` on these specific
  panels, and whether `safeDrawingPadding()`'s inset values match the physical cutout on a
  CMF Phone versus a Galaxy A.
- Real One UI large-font/large-display behavior on the tighter rows called out above.

## Compile result

The full changeset (camera capability detection, sensor fallback, edge-to-edge insets, manifest
feature flags, storage hardening) compiles green via `gradle assembleDebug` and ships as
Orbit Capture v0.5.0.
