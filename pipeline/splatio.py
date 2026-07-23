from __future__ import annotations

from pathlib import Path
from typing import Optional

import numpy as np

SH_C0 = 0.28209479177387814
RECORD_BYTES = 32

SPLAT_DTYPE = np.dtype(
    [
        ("position", "<f4", 3),
        ("scale", "<f4", 3),
        ("color", "u1", 4),
        ("rotation", "u1", 4),
    ]
)

PLY_TYPE_MAP = {
    "char": "i1",
    "int8": "i1",
    "uchar": "u1",
    "uint8": "u1",
    "short": "i2",
    "int16": "i2",
    "ushort": "u2",
    "uint16": "u2",
    "int": "i4",
    "int32": "i4",
    "uint": "u4",
    "uint32": "u4",
    "float": "f4",
    "float32": "f4",
    "double": "f8",
    "float64": "f8",
}

assert SPLAT_DTYPE.itemsize == RECORD_BYTES


def sigmoid(x: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-x))


def color_from_sh_dc(f_dc: np.ndarray) -> np.ndarray:
    return np.clip((0.5 + SH_C0 * f_dc) * 255.0, 0, 255)


def alpha_from_opacity(opacity: np.ndarray) -> np.ndarray:
    return np.clip(sigmoid(opacity) * 255.0, 0, 255)


def quat_to_uint8(rotation: np.ndarray) -> np.ndarray:
    norm = np.linalg.norm(rotation, axis=1, keepdims=True)
    norm = np.where(norm < 1e-8, 1.0, norm)
    unit = rotation / norm
    return np.clip(unit * 128.0 + 128.0, 0, 255)


def read_ply(path: Path) -> tuple[dict[str, np.ndarray], int]:
    with open(path, "rb") as handle:
        magic = handle.readline().decode("ascii", errors="strict").strip()
        if magic != "ply":
            raise ValueError(f"not a ply file: {path}")
        fmt: Optional[str] = None
        count = 0
        properties: list[tuple[str, str]] = []
        seen_vertex_element = False
        while True:
            raw_line = handle.readline()
            if not raw_line:
                raise ValueError("ply header ended without end_header")
            line = raw_line.decode("ascii", errors="strict").strip()
            if line.startswith("format"):
                fmt = line.split()[1]
            elif line.startswith("element vertex"):
                count = int(line.split()[-1])
                seen_vertex_element = True
            elif line.startswith("element"):
                if seen_vertex_element:
                    raise ValueError("ply files with multiple elements are not supported")
            elif line.startswith("property") and seen_vertex_element:
                parts = line.split()
                properties.append((parts[1], parts[-1]))
            elif line == "end_header":
                break
        if fmt != "binary_little_endian":
            raise ValueError(f"unsupported ply format: {fmt}")
        dtype_fields = [(name, PLY_TYPE_MAP[ptype]) for ptype, name in properties]
        dtype = np.dtype(dtype_fields)
        data = np.fromfile(handle, dtype=dtype, count=count)
    if data.shape[0] != count:
        raise ValueError(f"ply vertex data truncated: expected {count}, got {data.shape[0]}")
    arrays = {name: data[name] for name, _ in dtype_fields}
    return arrays, count


def ply_to_splat_arrays(props: dict[str, np.ndarray]) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    positions = np.stack([props["x"], props["y"], props["z"]], axis=1).astype(np.float32)
    scale_log = np.stack([props["scale_0"], props["scale_1"], props["scale_2"]], axis=1).astype(np.float32)
    scales = np.exp(scale_log).astype(np.float32)
    f_dc = np.stack([props["f_dc_0"], props["f_dc_1"], props["f_dc_2"]], axis=1).astype(np.float32)
    rgb = color_from_sh_dc(f_dc)
    alpha = alpha_from_opacity(props["opacity"].astype(np.float32))
    colors = np.concatenate([rgb, alpha[:, None]], axis=1).astype(np.uint8)
    quat = np.stack([props["rot_0"], props["rot_1"], props["rot_2"], props["rot_3"]], axis=1).astype(np.float32)
    rotations = quat_to_uint8(quat).astype(np.uint8)
    return positions, scales, colors, rotations


def write_splat(
    path: Path,
    positions: np.ndarray,
    scales: np.ndarray,
    colors: np.ndarray,
    rotations: np.ndarray,
) -> int:
    positions = np.asarray(positions, dtype=np.float32)
    scales = np.asarray(scales, dtype=np.float32)
    colors = np.clip(np.asarray(colors), 0, 255).astype(np.uint8)
    rotations = np.clip(np.asarray(rotations), 0, 255).astype(np.uint8)
    count = positions.shape[0]
    if count == 0:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"")
        return 0
    volume = np.prod(np.abs(scales.astype(np.float64)), axis=1)
    alpha = colors[:, 3].astype(np.float64)
    order = np.argsort(-(volume * alpha))
    records = np.zeros(count, dtype=SPLAT_DTYPE)
    records["position"] = positions[order]
    records["scale"] = scales[order]
    records["color"] = colors[order]
    records["rotation"] = rotations[order]
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(records.tobytes())
    return count


def convert_ply_to_splat(ply_path: Path, splat_path: Path) -> int:
    props, _ = read_ply(ply_path)
    positions, scales, colors, rotations = ply_to_splat_arrays(props)
    return write_splat(splat_path, positions, scales, colors, rotations)


def read_splat(path: Path) -> dict:
    size = path.stat().st_size
    if size % RECORD_BYTES != 0:
        raise ValueError(f"malformed splat file size {size} at {path}")
    data = np.fromfile(path, dtype=SPLAT_DTYPE)
    count = int(data.shape[0])
    if count == 0:
        return {"count": 0, "min": [0.0, 0.0, 0.0], "max": [0.0, 0.0, 0.0]}
    positions = data["position"].astype(np.float64)
    return {
        "count": count,
        "min": positions.min(axis=0).tolist(),
        "max": positions.max(axis=0).tolist(),
    }


def validate_splat(path: Path) -> None:
    if not path.exists():
        raise ValueError(f"splat file not found: {path}")
    size = path.stat().st_size
    if size == 0 or size % RECORD_BYTES != 0:
        raise ValueError(f"malformed splat file size {size} at {path}")
    data = np.fromfile(path, dtype=SPLAT_DTYPE)
    if not np.all(np.isfinite(data["position"])):
        raise ValueError(f"non finite position values in {path}")
    if not np.all(np.isfinite(data["scale"])):
        raise ValueError(f"non finite scale values in {path}")
