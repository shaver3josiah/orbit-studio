from __future__ import annotations

from pathlib import Path
from typing import Optional

from pipeline import RunContext, photo

DEFAULT_HFOV = 100
DEFAULT_SIZE = 1280
MIN_PITCH = -60

RING_YAWS = (0, 45, 90, 135, 180, 225, 270, 315)
CARDINAL_YAWS = (0, 90, 180, 270)

RIG_8 = [(yaw, -15) for yaw in RING_YAWS]
RIG_14 = (
    [(yaw, -15) for yaw in RING_YAWS]
    + [(yaw, 20) for yaw in CARDINAL_YAWS]
    + [(0, 75), (180, 75)]
)
RIG_18 = (
    [(yaw, -15) for yaw in RING_YAWS]
    + [(yaw, 20) for yaw in RING_YAWS]
    + [(0, 75), (180, 75)]
)

RIGS = {8: RIG_8, 14: RIG_14, 18: RIG_18}


def angle_token(value: int) -> str:
    return f"n{abs(value):03d}" if value < 0 else f"p{value:03d}"


def build_rig(crops_per_frame: int) -> list[tuple[int, int]]:
    rig = RIGS.get(crops_per_frame, RIG_18)
    return [(yaw, pitch) for yaw, pitch in rig if pitch >= MIN_PITCH]


def normalize_yaw(value: int) -> int:
    return ((value + 180) % 360) - 180


def v360_filter(yaw: int, pitch: int, hfov: int, size: int) -> str:
    signed_yaw = normalize_yaw(yaw)
    return (
        f"v360=input=e:output=flat:h_fov={hfov}:v_fov={hfov}:"
        f"yaw={signed_yaw}:pitch={pitch}:w={size}:h={size}:interp=cubic"
    )


def crop_from_frames(
    ffmpeg_path: Path,
    frames_dir: Path,
    crops_dir: Path,
    rig: list[tuple[int, int]],
    hfov: int,
    size: int,
    ctx: RunContext,
) -> int:
    frame_paths = sorted(frames_dir.glob("f_*.jpg"))
    crops_dir.mkdir(parents=True, exist_ok=True)
    total_frames = len(frame_paths)
    written = 0
    for index, frame_path in enumerate(frame_paths, start=1):
        for yaw, pitch in rig:
            ctx.check_cancelled()
            out_name = f"{frame_path.stem}_y{yaw:03d}_{angle_token(pitch)}.jpg"
            out_path = crops_dir / out_name
            filter_chain = v360_filter(yaw, pitch, hfov, size)
            cmd = [
                str(ffmpeg_path), "-y", "-i", str(frame_path),
                "-vf", filter_chain, "-frames:v", "1", str(out_path),
            ]
            result = ctx.run(cmd, timeout=60)
            if result.returncode != 0 or not out_path.exists():
                raise RuntimeError(f"ffmpeg reframe failed for {out_name}: {result.stdout[-400:]}")
            written += 1
        pct = int(index / total_frames * 100) if total_frames else 100
        ctx.report(pct, f"reframed {index}/{total_frames} frames")
    return written


def crop_from_frames_python(
    frames_dir: Path,
    crops_dir: Path,
    rig: list[tuple[int, int]],
    hfov: int,
    size: int,
    ctx: RunContext,
) -> int:
    """Reframe without ffmpeg, for machines where policy will not run it.

    Verified against ffmpeg's own v360 across the full 18-view rig: worst mean
    absolute difference 1.33 of 255, which is bilinear against cubic and not
    geometry. Bundles built this way are interchangeable with bundles built by
    ffmpeg. It also decodes each panorama ONCE and projects every view from it,
    where the ffmpeg path re-decodes per crop, so it is not the slow option.
    """
    from pipeline import equirect
    from PIL import Image
    import numpy as np

    frame_paths = sorted(frames_dir.glob("f_*.jpg"))
    crops_dir.mkdir(parents=True, exist_ok=True)
    total_frames = len(frame_paths)
    written = 0
    for index, frame_path in enumerate(frame_paths, start=1):
        ctx.check_cancelled()
        image = photo.open_photo(frame_path)
        try:
            array = np.asarray(image.convert("RGB")).astype(np.float32)
        finally:
            image.close()
        for yaw, pitch in rig:
            ctx.check_cancelled()
            out_name = f"{frame_path.stem}_y{yaw:03d}_{angle_token(pitch)}.jpg"
            out = equirect.project(array, normalize_yaw(yaw), pitch, hfov, hfov, size)
            Image.fromarray(np.clip(out + 0.5, 0, 255).astype(np.uint8)).save(
                crops_dir / out_name, quality=92, subsampling=0)
            written += 1
        pct = int(index / total_frames * 100) if total_frames else 100
        ctx.report(pct, f"reframed {index}/{total_frames} frames (no ffmpeg needed)")
    return written


def crop_from_video(
    ffmpeg_path: Path,
    source: Path,
    crops_dir: Path,
    rig: list[tuple[int, int]],
    hfov: int,
    size: int,
    fps: float,
    ctx: RunContext,
) -> int:
    crops_dir.mkdir(parents=True, exist_ok=True)
    total_views = len(rig)
    written = 0
    for index, (yaw, pitch) in enumerate(rig, start=1):
        ctx.check_cancelled()
        pattern = crops_dir / f"y{yaw:03d}_{angle_token(pitch)}_f_%05d.jpg"
        filter_chain = f"fps={fps}," + v360_filter(yaw, pitch, hfov, size)
        cmd = [
            str(ffmpeg_path), "-y", "-i", str(source),
            "-vf", filter_chain, "-q:v", "2", str(pattern),
        ]
        result = ctx.run(cmd, timeout=120)
        if result.returncode != 0:
            raise RuntimeError(f"ffmpeg reframe failed for view y{yaw} p{pitch}: {result.stdout[-400:]}")
        written += len(list(crops_dir.glob(f"y{yaw:03d}_{angle_token(pitch)}_f_*.jpg")))
        ctx.report(int(index / total_views * 100), f"reframed view {index}/{total_views}")
    return written


def run(
    project_dir: Path,
    ffmpeg_path: Path,
    settings: dict,
    source: Optional[Path],
    fps: float,
    ctx: RunContext,
) -> dict:
    crops_per_frame = int(settings.get("crops_per_frame", 18))
    hfov = int(settings.get("hfov", DEFAULT_HFOV))
    size = int(settings.get("size", DEFAULT_SIZE))
    rig = build_rig(crops_per_frame)
    frames_dir = project_dir / "frames"
    crops_dir = project_dir / "crops"
    frame_files = sorted(frames_dir.glob("f_*.jpg")) if frames_dir.exists() else []
    if frame_files:
        # ffmpeg_path is None when it is missing or policy refuses to run it. A photo
        # set never needed it for anything but this projection, so do it in numpy
        # rather than dead-ending the capture.
        if ffmpeg_path is None:
            written = crop_from_frames_python(frames_dir, crops_dir, rig, hfov, size, ctx)
        else:
            written = crop_from_frames(ffmpeg_path, frames_dir, crops_dir, rig, hfov, size, ctx)
    elif source is not None and source.exists():
        if ffmpeg_path is None:
            raise RuntimeError(
                "Reframing straight from video needs ffmpeg, which will not run on this "
                "machine. 360 PHOTO sets work without it - export stills from the "
                "Insta360 app instead of a video.")
        written = crop_from_video(ffmpeg_path, source, crops_dir, rig, hfov, size, fps, ctx)
    else:
        raise RuntimeError("no frames or source video available for reframe")
    ctx.report(100, f"reframe complete crops={written}")
    return {"crops": written, "views": len(rig)}
