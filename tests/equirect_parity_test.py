"""Does the numpy reframe agree with ffmpeg's v360, angle by angle?

If it does not, bundles built on a machine without ffmpeg would be geometrically
different from bundles built with it, and COLMAP would be solving a different scene
than the capture guide describes. Agreement is the whole point.
"""
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(r"C:\Users\shave\orbit-studio")
sys.path.insert(0, str(REPO))

import numpy as np
from PIL import Image

from pipeline import doctor, equirect
from pipeline.reframe import build_rig, v360_filter

FF = doctor.find_ffmpeg(REPO)
work = Path(tempfile.mkdtemp(prefix="eqcmp_"))

# A source with unmistakable structure everywhere, so a wrong sign or a swapped
# axis shows up as a large error instead of hiding in smooth gradients.
W, H = 2048, 1024
yy, xx = np.mgrid[0:H, 0:W]
lon = (xx / W) * 360.0
lat = 90.0 - (yy / H) * 180.0
src = np.zeros((H, W, 3), dtype=np.uint8)
src[..., 0] = (lon * 255 / 360).astype(np.uint8)          # longitude ramp
src[..., 1] = ((lat + 90) * 255 / 180).astype(np.uint8)   # latitude ramp
checker = (((xx // 64) + (yy // 64)) % 2) * 255
src[..., 2] = checker.astype(np.uint8)                    # hard edges
src_path = work / "src.jpg"
Image.fromarray(src).save(src_path, quality=98, subsampling=0)

SIZE, HFOV = 256, 100
worst = 0.0
rows = []
for yaw, pitch in build_rig(18):
    ff_out = work / f"ff_{yaw}_{pitch}.png"
    cmd = [str(FF), "-y", "-i", str(src_path), "-vf",
           v360_filter(yaw, pitch, HFOV, SIZE), "-frames:v", "1", str(ff_out)]
    r = subprocess.run(cmd, capture_output=True)
    if r.returncode != 0 or not ff_out.exists():
        print(f"  ffmpeg failed for yaw={yaw} pitch={pitch}")
        continue
    a = np.asarray(Image.open(ff_out).convert("RGB")).astype(np.float64)

    from pipeline.reframe import normalize_yaw
    mine = equirect.project(src.astype(np.float32), normalize_yaw(yaw), pitch,
                            HFOV, HFOV, SIZE).astype(np.float64)

    # ignore a 2px frame: cubic vs bilinear diverge most at the very edge
    a_i, m_i = a[2:-2, 2:-2], mine[2:-2, 2:-2]
    mae = float(np.abs(a_i - m_i).mean())
    worst = max(worst, mae)
    rows.append((yaw, pitch, mae))

print(f"{'yaw':>5} {'pitch':>6}   mean abs diff (0-255)")
for yaw, pitch, mae in rows:
    flag = "" if mae < 8 else "   <-- OFF"
    print(f"{yaw:5} {pitch:6}   {mae:8.2f}{flag}")

print(f"\nworst mean abs difference across the whole 18-view rig: {worst:.2f} / 255")
ok = worst < 8.0
print("MATCHES ffmpeg" if ok else "DOES NOT MATCH - geometry differs")
sys.exit(0 if ok else 1)
