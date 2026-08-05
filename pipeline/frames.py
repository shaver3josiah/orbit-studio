from __future__ import annotations

import re
from pathlib import Path

from PIL import Image

from pipeline import RunContext, photo

# numpy is NOT imported here. It is used only by the sharpness filter, which only
# the VIDEO lane runs - a 360 photo set is copied through frame for frame with no
# scoring. Importing it at module level meant a machine with Pillow but no numpy
# could not process a photo set at all, for maths that path never reaches. Measured
# on a corporate laptop where setup had installed Pillow and not numpy.
# (from __future__ import annotations above keeps the np.ndarray hints as strings.)

DEFAULT_TARGET_FRAMES = 300
MAX_TARGET_FRAMES = 1200
SHARPNESS_DROP_RATIO = 0.2
FRAME_RE = re.compile(r"frame=\s*(\d+)")


def clamp_target_frames(value: int) -> int:
    return max(1, min(MAX_TARGET_FRAMES, int(value)))


def build_fps(duration: float, target_frames: int) -> float:
    safe_duration = max(float(duration), 0.1)
    return max(0.1, target_frames / safe_duration)


def extract_with_ffmpeg(
    ffmpeg_path: Path,
    source: Path,
    out_dir: Path,
    fps: float,
    target_frames: int,
    ctx: RunContext,
) -> list[Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    pattern = out_dir / "f_%05d.jpg"
    cmd = [str(ffmpeg_path), "-y", "-i", str(source), "-vf", f"fps={fps}", "-q:v", "2", str(pattern)]

    def on_line(line: str) -> None:
        match = FRAME_RE.search(line)
        if match:
            count = int(match.group(1))
            pct = min(60, int(count / max(1, target_frames) * 60))
            ctx.report(pct, line.strip())

    result = ctx.run(cmd, on_line=on_line)
    if result.returncode != 0:
        raise RuntimeError(f"ffmpeg frame extraction failed: {result.stdout[-400:]}")
    return sorted(out_dir.glob("f_*.jpg"))


def laplacian_variance(gray: "np.ndarray") -> float:
    center = gray[1:-1, 1:-1]
    up = gray[:-2, 1:-1]
    down = gray[2:, 1:-1]
    left = gray[1:-1, :-2]
    right = gray[1:-1, 2:]
    laplacian = up + down + left + right - 4.0 * center
    return float(laplacian.var())


def score_frame(path: Path) -> float:
    import numpy as np  # video lane only; see the note at the top of this module

    with Image.open(path) as image:
        gray = np.asarray(image.convert("L"), dtype=np.float64)
    if gray.shape[0] < 3 or gray.shape[1] < 3:
        return 0.0
    return laplacian_variance(gray)


def apply_sharpness_filter(out_dir: Path, ctx: RunContext) -> dict:
    frame_paths = sorted(out_dir.glob("f_*.jpg"))
    total = len(frame_paths)
    if total == 0:
        return {"extracted": 0, "kept": 0}
    scored: list[tuple[Path, float]] = []
    report_every = max(1, total // 8)
    for index, path in enumerate(frame_paths, start=1):
        ctx.check_cancelled()
        scored.append((path, score_frame(path)))
        if index % report_every == 0 or index == total:
            ctx.report(60 + int(index / total * 30), f"scored {index}/{total}")
    scored.sort(key=lambda item: item[1])
    drop_count = int(total * SHARPNESS_DROP_RATIO)
    for path, _ in scored[:drop_count]:
        path.unlink(missing_ok=True)
    kept = total - drop_count
    return {"extracted": total, "kept": kept}


def run(
    project_dir: Path,
    source: Path,
    duration: float,
    ffmpeg_path: Path,
    settings: dict,
    ctx: RunContext,
) -> dict:
    target_frames = clamp_target_frames(settings.get("target_frames", DEFAULT_TARGET_FRAMES))
    fps = build_fps(duration, target_frames)
    out_dir = project_dir / "frames"
    ctx.report(1, f"extracting frames at fps={fps:.3f}")
    extract_with_ffmpeg(ffmpeg_path, source, out_dir, fps, target_frames, ctx)
    ctx.report(60, "scoring sharpness")
    result = apply_sharpness_filter(out_dir, ctx)
    ctx.report(100, f"frames complete kept={result['kept']} of {result['extracted']}")
    return result


def run_single_image(project_dir: Path, source: Path, ctx: RunContext) -> dict:
    return run_multi_image(project_dir, [source], ctx)


def run_multi_image(project_dir: Path, sources: list[Path], ctx: RunContext) -> dict:
    out_dir = project_dir / "frames"
    out_dir.mkdir(parents=True, exist_ok=True)
    total = len(sources)
    for index, source in enumerate(sources, start=1):
        ctx.check_cancelled()
        # photo.open_photo, not a bare Image.open: it carries the truncation
        # tolerance, the raised decompression-bomb ceiling for big equirects, and the
        # EXIF-orientation fix, and it is the same call upload validation makes. When
        # these two disagreed, upload accepted photos this stage then died on, and
        # refused photos this stage could have handled.
        image = photo.open_photo(source)
        try:
            image.convert("RGB").save(out_dir / f"f_{index:05d}.jpg", quality=95)
        finally:
            image.close()
        ctx.report(int(index / total * 100) if total else 100, f"copied panorama {index}/{total}")
    ctx.report(100, f"frames complete kept={total} of {total}")
    return {"extracted": total, "kept": total}
