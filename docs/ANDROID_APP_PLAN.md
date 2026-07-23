# Orbit Capture — Android build plan

Composed 2026-07-10 (prompt-architect COMPOSE). Governs the `android/` project.

## Decision: native Kotlin, not a WebView wrapper
Reconstruction quality depends on camera control the web layer cannot give: true AE/AWB lock (Camera2 interop `CONTROL_AE_LOCK`), full-resolution JPEGs with EXIF intact (focal length feeds COLMAP intrinsics), and real sensors for coaching (rotation vector → level + turn-rate). A TWA/PWA strips all three. UI is Jetpack Compose, porting the validated AR-coach design 1:1.

## Pinned stack (do not drift)
| Piece | Version | Why |
|---|---|---|
| JDK | 17 (installed) | AGP 8.x requirement |
| Gradle | 8.11.1 (standalone, no wrapper bootstrap) | ≥8.9 needed by AGP 8.7 |
| AGP | 8.7.3 | Known-good with Gradle 8.11/JDK 17 |
| Kotlin | 2.0.21 + `org.jetbrains.kotlin.plugin.compose` | Compose compiler is a Kotlin plugin from 2.0 |
| Compose BOM | 2024.12.01 | Material3 + Navigation compatible |
| CameraX | 1.4.1 | Exposure lock via Camera2 interop, stable |
| SDK | compileSdk 35, targetSdk 35, minSdk 26 | CameraX floor + modern MediaStore |
| Third-party deps | none beyond the above | thumbnails via BitmapFactory, zip via java.util.zip |

Toolchain lives at `C:\Users\shave\android-sdk` (cmdline-tools → platform-tools, platforms;android-35, build-tools;35.0.0) and `C:\Users\shave\gradle\gradle-8.11.1`. Project points at the SDK via `local.properties`, not env vars.

## Module map (one Gradle module, `android/app`)
```
com.orbitstudio.capture
├── MainActivity.kt + OrbitNavHost        (single activity, 5 destinations)
├── ui/theme/                             (tokens from DESIGN.md)
├── data/ScanRepository.kt                (filesDir/scans/<id>/photos/*.jpg + scan.json)
├── capture/CaptureEngine.kt              (CameraX: preview, ImageCapture max-quality,
│                                          AE+AWB lock, tap-to-focus, EXIF passthrough)
├── capture/CoachSensors.kt               (rotation vector → level bubble + turn-per-shot
│                                          overlap heuristic; honest v1, see spec)
├── capture/BlurScore.kt                  (Laplacian variance per shot → weak spots)
├── bundle/BundleBuilder.kt               (manifest.json + crops/*.jpg, ZIP_STORED,
│                                          rig.lane="phone" per PHONE_CAPTURE_SPEC.md)
└── ui/screens/ Home | Capture | Review | Bundle | Done
```

## Delegation (efficient-fable, Sonnet fleet)
- T toolchain-bootstrap ─┐ (runs in parallel with S)
- S scaffold+contracts  ─┤ scaffold defines interfaces; modules code against them
- M1 capture, M2 review, M3 bundle+done, M4 home+repo (parallel, disjoint files)
- B build-fix loop (gradle assembleDebug, ≤6 iterations)
- P design polish pass (DESIGN.md conformance, then rebuild)
- V verify (aapt badging, APK contents, install steps)

## Known risks, pre-answered
- **Version matrix breakage** → all versions pinned above; scaffold writes them verbatim.
- **Parallel agents colliding** → file ownership is disjoint; contracts frozen in scaffold.
- **No device/adb here** → deliverable is `app-debug.apk` + sideload instructions; on-device test is the user's step.
- **Overlap meter honesty** → sensor heuristic, ceiling documented in code (`ponytail:` comment) with upgrade path = on-device feature matching.
- **Windows quirks** → short project path (`android/`), `local.properties` escaped path, Gradle heap capped at 3g.

## Acceptance
1. `gradle assembleDebug` exits 0 on this laptop; APK < 25 MB.
2. `aapt dump badging` shows package `com.orbitstudio.capture`, CAMERA permission, minSdk 26.
3. Bundle zip produced by the app validates against `pipeline/bundle.py` expectations (manifest keys, STORED entries, crops/ names).
4. Every screen has its empty/permission/error state; DESIGN.md bans hold.
