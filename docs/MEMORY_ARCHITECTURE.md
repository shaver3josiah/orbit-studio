# Orbit Capture v0.3.0 — memory & robustness architecture

Target device: budget Android, ~2-3 GB RAM, ~192 MB app heap. The app must survive a full
5-room scan (~300 photos) without OOM, with all animations intact.

## Where the memory actually goes (audit of v0.2.0)

| Hotspot | v0.2.0 behavior | Risk on 192 MB heap |
|---|---|---|
| Review thumbnails | `BitmapFactory.decodeFile` inside composition, main thread, ARGB_8888, unbounded per-item `remember` | Jank + unbounded growth with photo count; prime OOM suspect |
| Bundle export | `File.readBytes()` per photo (5-12 MB each) into heap | Repeated multi-MB spikes; OOM on low headroom |
| Sensor state | rotation vector publishes ~16-60 Hz -> whole Viewfinder recomposes every event | Allocation churn + GC pressure while animating |
| Blur scoring | 256px gray + Laplacian arrays (~0.8 MB, short-lived) | Fine; keep |
| Camera JPEG buffers | native, not heap | Fine; keep MAXIMIZE_QUALITY |

## The fixes (world-class = boring, bounded, measured)

1. **ThumbCache** (`ui/components/Thumbs.kt`): one process-wide `android.util.LruCache<String, Bitmap>`
   sized `min(maxHeap/8, 24 MB)` by byte count. Decode: `inSampleSize` to the cell size,
   `inPreferredConfig = RGB_565` (halves bytes, thumbnails don't need alpha or depth), on
   `Dispatchers.IO` via `produceState` — composition never blocks, cells show a placeholder
   tile until the bitmap lands. Eviction relies on GC (no manual `recycle()`, which races
   in-flight draws). `onTrimMemory` clears the whole cache.
2. **Streaming zip** (`bundle/BundleBuilder.kt`): STORED entries need CRC+size up front, so
   each photo is streamed twice with an 8 KB buffer — pass 1 computes CRC32/length, pass 2
   copies into the zip. Constant memory regardless of photo size. Manifest unchanged.
3. **Recomposition discipline** (`capture/CoachSensors.kt`): publish to Compose state only on
   meaningful change — heading 0.5°, roll 0.5°, overlap 1%, yaw rate 2°/s. Cuts capture-screen
   recompositions roughly 10x with zero visual difference (the meters animate over 150-200 ms
   anyway).
4. **Verified AE lock** (`capture/CaptureEngine.kt`): a `Camera2Interop.Extender`
   session-capture callback reads `CaptureResult.CONTROL_AE_STATE` — the chip now shows the
   camera's own reported lock state (`AE_STATE_LOCKED`), not our request. Throttled to
   state-change only.
5. **Crash forensics** (`MainActivity.kt` + Home): a default uncaught-exception handler writes
   the stack to `filesDir/last-crash.txt` before dying; Home shows a quiet "Last session
   crashed — share the report" chip (share sheet -> text) so field crashes on her phone become
   fixable bugs here. Dismiss deletes the file.
6. **Retake guidance** (CaptureScreen): a soft frame no longer just flips arrows — guidance
   enters retake mode with words: "Go back to your last spot and retake it." Arrows are
   suppressed; the plan-view target pins to the weak shot's heading until a sharp frame lands.

## Floor plans (new feature, same memory rules)

- `data/FloorPlanModels.kt` + `FloorPlanRepository`: `FloorPlan(id, name, rooms<=5)`;
  `PlanRoom(id, name, gridRect, scanId?)`. One JSON file (`filesDir/plans.json`), org.json,
  synchronized, no new dependencies.
- `ui/screens/FloorPlanScreen.kt`: 8x10 grid canvas; drag to draw a room rectangle, name it,
  max 5 rooms; tap a room to start (or reopen) its scan — each room is its own Scan and its
  own bundle.zip, matching the pipeline's one-scene-per-bundle assumption. Room cells tint by
  scan status. Vector drawing only — no bitmaps.
- Home gains a "Plan a building" entry. Route: `plan`.

## Explicitly rejected

- `android:largeHeap="true"` — a crutch that papers over unbounded allocations and hurts the
  rest of the system on a budget phone.
- Coil/Glide — 2-3 MB of dependency for what a 40-line LruCache does here (ponytail rung 5).
- Downgrading capture quality — reconstruction quality is the product; memory is managed
  around it, not by degrading it.
