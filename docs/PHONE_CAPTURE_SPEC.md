# Phone Capture Spec

*A second way in: still photos from an Android phone instead of a 360 video walk.*

This describes a phone-photo lane that rides the same rails as the Insta360 lane: same `bundle.zip` contract, same Colab notebook, same viewer. Nothing here breaks an existing bundle. Anything marked **(proposed)** is a design for code that does not exist yet; everything else is a fact about the code as it stands today, with a file reference.

## 1. How a set of phone JPEGs becomes a valid bundle.zip

The bundle contract is smaller than it looks, and that is good news for this lane. `pipeline/bundle.py:8-22` builds `bundle.zip` from whatever is sitting in `<project>/crops/*.jpg` plus a `manifest.json`:

```json
{"app": "orbit-studio", "project": "<name>", "crops": <count>, "rig": <settings dict>}
```

`build_bundle()` does not care how those JPEGs got there or what naming pattern they follow (`pipeline/bundle.py:10`, a plain glob). The video lane's `reframe.py` fills `crops/` with fisheye-cropped stills named like `f_00001_y000_n015.jpg`. The phone lane can fill the same folder with `IMG_20260710_0001.jpg` unchanged, and `build_bundle()` will zip it exactly the same way, `ZIP_STORED`, no changes to that file needed.

`"rig"` is just `settings`, the dict passed into the bundle stage verbatim (`pipeline/bundle.py:15`, `server.py:284-287`). Nothing validates its shape. Today the studio UI sends `{target_frames, crops_per_frame, size}` for the video lane (`studio/index.html:3361`) and that dict has no `"lane"` key at all. That absence is what makes a phone rig safe to add: old bundles have no `"lane"` field, so a notebook that checks `rig.get("lane")` and defaults missing/unrecognized values to today's video behavior never breaks on an old bundle.

Proposed phone rig shape:

```json
{
  "lane": "phone",
  "device": "Pixel 8",
  "focal_length_35mm_equiv_mm": 26,
  "exposure_locked": true,
  "capture_pattern": "grid",
  "photo_count": 48
}
```

- `lane`: the discriminator. `"phone"` vs. absent/`"video-360"` for the existing lane.
- `device`: EXIF `Model` tag if available, else user-entered. Lets a human (or the notebook's printed summary) sanity-check that one physical camera shot the whole set.
- `focal_length_35mm_equiv_mm`: from EXIF `FocalLengthIn35mmFilm` if present, else `null`. This is the value that matters for a shared-camera assumption (below), not raw focal length in mm, because it is comparable across sensors.
- `exposure_locked`: whether AE/AF/zoom were locked for the whole set. True is what unlocks the shared-intrinsics shortcut in COLMAP; false means treat every photo as its own camera, same as the video lane does today.
- `capture_pattern`: free text, e.g. `"grid"`, `"orbit"`, `"walkthrough"`. Informational for now, not consumed by the notebook.
- `photo_count`: redundant with `manifest.json`'s top-level `"crops"` count, kept in `rig` too so the printed capture summary in the notebook (`notebooks/OrbitStudio_Colab.ipynb`, cell-06) shows it without a second lookup.

None of this requires a change to `pipeline/bundle.py`. Whatever dict is passed as `settings` to the `bundle` stage becomes `rig`.

## 2. What changes in the Colab notebook (proposed)

The upload cell (cell-06) needs nothing. It already extracts any zip, moves a stray `manifest.json` to the work dir, and prints `project`, `crops`, and `rig` if present (`notebooks/OrbitStudio_Colab.ipynb` cell-06) — a phone rig dict prints the same way a video rig dict does.

The pose-recovery cell (cell-11) is the one that would change. Today it unconditionally uses:

```python
pycolmap.extract_features(..., camera_model="OPENCV", camera_mode=pycolmap.CameraMode.PER_IMAGE)
```

with an explicit comment that per-image is the safer default because "Orbit Studio's extracted frames are not all identical crops" (cell-10 markdown, cell-11 code). That reasoning does not hold for the phone lane: if `rig.exposure_locked` is true and zoom never moved, every photo really did come from one physical camera with one set of intrinsics, and `pycolmap.CameraMode.SINGLE` (one shared camera solved once, from all images pooled together) is both more robust with fewer photos and faster than solving intrinsics separately per image.

Proposed branch, keyed off the manifest rather than re-guessing from the image set:

```python
rig = manifest.get("rig", {}) if os.path.exists(manifest_path) else {}
shared_camera = rig.get("lane") == "phone" and rig.get("exposure_locked") is True
camera_mode = pycolmap.CameraMode.SINGLE if shared_camera else pycolmap.CameraMode.PER_IMAGE
```

No manifest, or `exposure_locked` false/absent, falls through to today's `PER_IMAGE` behavior — an old video bundle or a phone set with mixed zoom is untouched.

The other candidate change is feeding COLMAP an EXIF-derived focal length as a prior instead of letting it estimate one from scratch. `pycolmap.extract_features` accepts per-camera `camera_params`, and COLMAP's own image reader has historically read EXIF focal length automatically when a camera model is not fully specified. I have not verified that behavior against the specific `pycolmap` version this notebook's install cell (`cell-09`) pins, so treat "pass `rig.focal_length_35mm_equiv_mm` as a focal prior" as a design intent, not a confirmed API call — flagged again under Open Questions.

Everything downstream of pose recovery — training (cell-13) and export (cell-15) — is lane-agnostic already. gsplat trains against COLMAP's sparse model and images directory, and does not know or care whether those images came from a 360 crop or a phone.

## 3. Capture requirements for healthy COLMAP registration

The one hard number in the code: `notebooks/OrbitStudio_Colab.ipynb` cell-11 warns when fewer than 60% of images register, and calls that a real signal to re-shoot, not a bug (matches the project's documented registration rule). The notebook does not encode a separate "healthy" threshold in code — the ~95% figure for healthy registration is practice guidance layered on top, not something `pycolmap.incremental_mapping` enforces.

Guidance for phone shooters, calibrated against what generally starves a COLMAP matcher (general photogrammetry practice, not pipeline-code-verified numbers — see Open Questions):

- **Photo count by room size** (roughly one small/medium/large bedroom-to-living-room scale):
  - Small room (closet, bathroom, ~10 m²): 25-40 photos
  - Medium room (bedroom, office, ~15-25 m²): 40-70 photos
  - Large room (living room, open-plan kitchen, 25-40 m²): 70-120 photos
  - These assume the grid/orbit overlap below; a sparser walk needs more shots to compensate.
- **Overlap**: 70-80% between consecutive photos, matching the CAPTURE_GUIDE's video-lane closed-loop philosophy (`docs/CAPTURE_GUIDE.md:20`) but applied shot-to-shot instead of frame-to-frame. Below ~60% overlap COLMAP starts failing to find enough shared keypoints between neighbors.
- **Resolution**: shoot at the phone's native/highest still resolution, not a cropped or "portrait mode" shot. Matches the video lane's "highest your camera offers" rule (`docs/CAPTURE_GUIDE.md:11`).
- **EXIF fields worth preserving**: `Model` (camera identity), `FocalLengthIn35mmFilm` (shared-camera prior), `DateTimeOriginal` (helps debug capture order if filenames get scrambled), `Orientation` (so crops aren't accidentally re-rotated). If any laptop-side tool re-encodes the JPEGs (resize, strip, recompress), it must explicitly carry EXIF bytes forward — a naive re-save (e.g. `PIL.Image.save()` without `exif=`) silently drops all of it, which is exactly what `pipeline/frames.py:112-114`'s `run_single_image` does today (it converts and re-saves with PIL and does not pass `exif=`; fine for its current single-equirect use case, but a trap if that helper were ever reused for phone photos).
- **What kills registration**:
  - Motion blur (same failure mode as the video lane — `docs/CAPTURE_GUIDE.md:12-15`).
  - Zoom changes mid-set — breaks the shared-camera assumption and, if the notebook branches on `exposure_locked`, silently produces bad poses if the flag was set true when it should not have been. Zoom changing is a `exposure_locked: false` fact, not a `true` one.
  - Auto-HDR / multi-frame merge inconsistencies: some phone camera apps blend several exposures per shutter press with slightly different crop or slightly different apparent field of view frame to frame. That variance defeats both the shared-camera assumption and feature matching consistency. Shoot with HDR off, single-frame capture, matching the video lane's "Adaptive Tone: Off" rule (`docs/CAPTURE_GUIDE.md:14`).
  - Large blank stretches with nothing to key on (a plain wall, a ceiling) — same failure the video lane already documents (`README.md:86`).

## 4. Handoff path

**v1 (this spec, minimal):** the Android app is a dumb exporter. It writes the photo set to a location the laptop can read — a folder over a file share, a USB copy, a synced folder, whatever is least new code. A small laptop-side script drops those files into `<project>/crops/*.jpg` (renamed if needed, EXIF preserved), writes the phone `rig` dict described in §1, then calls the *existing* `bundle` stage — either `pipeline.bundle.build_bundle()` directly or a `POST /api/projects/{id}/run {"stage": "bundle"}` after the crops folder is populated by hand. No `frames` or `reframe` stage runs for this lane; there is nothing to extract from a still-photo set. Validation is whatever's already there: the notebook's own `total_images < 10` guard (cell-11) and the 60% registration warning are the only checks that exist today.

One thing v1 does **not** get to reuse: `server.py`'s `POST /api/projects/{id}/media` endpoint. That handler takes exactly one file and rejects anything that isn't `width == 2*height` equirectangular unless `force=1` is passed (`server.py:469-505`). A folder of 40-120 phone stills does not fit that endpoint at all — the phone lane has to bypass HTTP media upload entirely and populate `crops/` directly, calling the bundle stage the way §1 describes. That is a real gap, not a contradiction of anything the pipeline promises; the media-upload endpoint was built for one equirect video/image and was never meant to take a multi-file set.

**Later (not v1):** the Android app builds `bundle.zip` itself, on-device, and uploads straight to the laptop (or straight to Colab) — same manifest shape, same `crops/*.jpg` layout, just assembled at the source instead of round-tripped through a laptop script. That is a strict superset of v1's contract, so nothing in §1 needs to change to get there later.

## 5. Up-axis statement

Per the project's coordinate rule (`PROJECT_STATE.md:31-33`): pipeline artifacts (COLMAP output, gsplat-trained splats) are **Y-down**, and the viewer applies a 180-degree X flip to compensate (`studio/index.html:3663`, `mesh.quaternion.set(1, 0, 0, 0)`, gated by `applyPipelineOrientation`). The phone lane produces its splat through the same COLMAP-to-gsplat path as the video lane, so it inherits the same Y-down convention and the same flip — no new axis handling needed anywhere in this lane. Any phone-lane artifact handed off between stages should still say so explicitly per the project rule, even though the answer is "same as everything else."

## 6. Open questions

- Whether `pycolmap.extract_features` in the version pinned by the notebook's install cell (`cell-09`, unpinned `pip install -q pycolmap`) actually reads EXIF focal length automatically, or whether that needs to be passed explicitly via `camera_params`/`ImageReaderOptions`. Not verified against running code in this repo.
- The "~95% healthy registration" figure and the photo-count-per-room-size ranges in §3 are practice guidance, not derived from or checked against anything in this codebase — the codebase only encodes the `<60%` warning threshold. Worth validating against a handful of real phone captures before publishing as a hard rule.
- Whether Android EXIF `FocalLengthIn35mmFilm` is reliably populated across phone models/camera apps, or whether some strip it. If it's frequently missing, the `focal_length_35mm_equiv_mm: null` fallback path (plain per-image or shared-camera-without-a-prior) needs to be the well-tested default, not an edge case.
- Where exactly the v1 laptop-side "drop phone photos into crops/" script should live — a new `pipeline/` module, a one-off script in `tools/`, or a CLI flag on `server.py`. Left unspecified here since no such script exists yet to reference.
- Whether `exposure_locked` should be a boolean the phone app can actually assert (if it locks AE/AF itself before the burst) versus a user-toggled checkbox that can be wrong. Its accuracy determines whether the notebook's proposed `SINGLE` vs `PER_IMAGE` branch helps or actively hurts registration.
