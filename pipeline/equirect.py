"""Equirectangular to flat perspective, in numpy, when ffmpeg cannot run.

A managed Windows machine can enforce AppLocker by PUBLISHER rather than by path:
signed executables run, unsigned ones do not, anywhere the user can write. The
ffmpeg essentials build is unsigned, so no folder fixes it and no relocation helps.
That leaves the whole capture-prep stage dead on exactly the machines this tool is
meant for.

But a 360 PHOTO set only ever asks ffmpeg for one thing - the v360 reframe - and
that is a projection, not a codec. numpy and Pillow are already dependencies, so it
can be done here with no binary at all. Video still needs ffmpeg for decoding;
nothing here replaces that.

Output matches `v360=input=e:output=flat:h_fov=H:v_fov=V:yaw=Y:pitch=P:w=W:h=H`
closely enough to be interchangeable - verified against real ffmpeg output rather
than derived and hoped for.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np

from pipeline import photo


def _rays(size: int, hfov: float, vfov: float) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Unit direction per output pixel, camera looking down +Z, +X right, +Y up."""
    half_x = np.tan(np.radians(hfov) / 2.0)
    half_y = np.tan(np.radians(vfov) / 2.0)
    # pixel CENTRES, hence the +0.5: sampling the corner shifts the whole image by
    # half a pixel and shows up as a seam when the crops are stitched.
    px = (2.0 * (np.arange(size) + 0.5) / size - 1.0) * half_x
    py = (2.0 * (np.arange(size) + 0.5) / size - 1.0) * half_y
    x = px[None, :].repeat(size, axis=0)
    y = -py[:, None].repeat(size, axis=1)  # image y grows downward, world Y grows up
    z = np.ones_like(x)
    norm = np.sqrt(x * x + y * y + z * z)
    return x / norm, y / norm, z / norm


def _sample(src: np.ndarray, u: np.ndarray, v: np.ndarray) -> np.ndarray:
    """Bilinear sample, wrapping in longitude and clamping in latitude.

    ponytail: bilinear where ffmpeg's call site asks for cubic. The difference is
    invisible at these crop sizes and irrelevant to the SIFT features COLMAP pulls
    out next; swap in a cubic kernel only if a reconstruction ever blames it.
    """
    height, width = src.shape[:2]
    u0 = np.floor(u).astype(np.int64)
    v0 = np.floor(v).astype(np.int64)
    fu = (u - u0)[..., None]
    fv = (v - v0)[..., None]
    u0m, u1m = u0 % width, (u0 + 1) % width           # longitude wraps at the seam
    v0m = np.clip(v0, 0, height - 1)                   # latitude does not
    v1m = np.clip(v0 + 1, 0, height - 1)
    top = src[v0m, u0m] * (1 - fu) + src[v0m, u1m] * fu
    bottom = src[v1m, u0m] * (1 - fu) + src[v1m, u1m] * fu
    return top * (1 - fv) + bottom * fv


def project(source: np.ndarray, yaw: float, pitch: float, hfov: float, vfov: float,
            size: int) -> np.ndarray:
    """One flat crop out of an equirectangular image, angles in degrees."""
    x, y, z = _rays(size, hfov, vfov)

    # Pitch about X (positive tilts the view UP), then yaw about Y (positive turns
    # RIGHT). Order matters and this one matches ffmpeg; the signs were settled by
    # comparing against real v360 output, not by reasoning about handedness.
    p = np.radians(pitch)
    cp, sp = np.cos(p), np.sin(p)
    y2 = y * cp + z * sp
    z2 = -y * sp + z * cp

    a = np.radians(yaw)
    ca, sa = np.cos(a), np.sin(a)
    x3 = x * ca + z2 * sa
    z3 = -x * sa + z2 * ca

    lon = np.arctan2(x3, z3)
    lat = np.arcsin(np.clip(y2, -1.0, 1.0))

    height, width = source.shape[:2]
    u = (lon / (2 * np.pi) + 0.5) * width - 0.5
    v = (0.5 - lat / np.pi) * height - 0.5
    return _sample(source.astype(np.float32), u, v)


def crop_file(src_path: Path, out_path: Path, yaw: float, pitch: float,
              hfov: float, vfov: float, size: int, quality: int = 92) -> None:
    from PIL import Image

    image = photo.open_photo(src_path)
    try:
        array = np.asarray(image.convert("RGB"))
    finally:
        image.close()
    out = project(array, yaw, pitch, hfov, vfov, size)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(np.clip(out + 0.5, 0, 255).astype(np.uint8)).save(
        out_path, quality=quality, subsampling=0)
