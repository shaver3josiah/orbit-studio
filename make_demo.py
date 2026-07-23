from __future__ import annotations

from pathlib import Path

import numpy as np

from pipeline import splatio


def identity_quat_uint8(count: int) -> np.ndarray:
    quat = np.zeros((count, 4), dtype=np.float32)
    quat[:, 3] = 1.0
    return splatio.quat_to_uint8(quat).astype(np.uint8)


def quat_align_x_to(direction: np.ndarray) -> np.ndarray:
    direction = direction / np.linalg.norm(direction, axis=1, keepdims=True)
    x_axis = np.zeros_like(direction)
    x_axis[:, 0] = 1.0
    dot = np.sum(x_axis * direction, axis=1)
    cross = np.cross(x_axis, direction)
    w = 1.0 + dot
    quat = np.concatenate([cross, w[:, None]], axis=1)
    norm = np.linalg.norm(quat, axis=1)
    degenerate = norm < 1e-6
    quat[degenerate] = np.array([0.0, 0.0, 1.0, 0.0])
    norm = np.linalg.norm(quat, axis=1, keepdims=True)
    return (quat / norm).astype(np.float32)


def lerp_color(a, b, t):
    a = np.asarray(a, dtype=np.float64)
    b = np.asarray(b, dtype=np.float64)
    return a[None, :] * (1 - t) + b[None, :] * t


def part(positions, scales, rgb, alpha, rotations=None):
    count = positions.shape[0]
    colors = np.concatenate([np.clip(rgb, 0, 255), np.clip(alpha, 0, 255).reshape(count, 1)], axis=1).astype(np.uint8)
    if rotations is None:
        rotations = identity_quat_uint8(count)
    return positions.astype(np.float32), scales.astype(np.float32), colors, rotations


def make_ground(rng):
    count = 9000
    radius = 8.5
    r = radius * np.sqrt(rng.random(count))
    theta = rng.uniform(0, 2 * np.pi, count)
    x = r * np.cos(theta)
    z = r * np.sin(theta)
    y = rng.normal(0.0, 0.02, count) - 0.05
    positions = np.stack([x, y, z], axis=1)
    scale_xz = rng.uniform(0.35, 0.7, count)
    scale_y = rng.uniform(0.02, 0.05, count)
    scales = np.stack([scale_xz, scale_y, scale_xz], axis=1)
    t = np.clip(r / radius, 0, 1)[:, None]
    rgb = lerp_color([30, 82, 76], [16, 18, 46], t)
    rgb = rgb + rng.normal(0, 4, size=(count, 3))
    alpha = np.full(count, 225.0)
    return part(positions, scales, rgb, alpha)


def make_tree(rng, base_x, base_z, height, canopy_r):
    trunk_n = 140
    t = rng.random(trunk_n) ** 0.8
    sway = 0.18 * np.sin(t * 2.2)
    tx = base_x + sway + rng.normal(0, 0.03, trunk_n)
    tz = base_z + rng.normal(0, 0.03, trunk_n)
    ty = t * height
    trunk_pos = np.stack([tx, ty, tz], axis=1)
    trunk_s = (0.16 * (1.0 - 0.55 * t))[:, None] * np.array([[1.0, 1.6, 1.0]])
    trunk_rgb = lerp_color([64, 46, 92], [126, 94, 176], t[:, None])
    trunk_a = np.full(trunk_n, 235.0)

    canopy_n = 900
    u = rng.normal(size=(canopy_n, 3))
    u = u / np.linalg.norm(u, axis=1, keepdims=True)
    shell = u * (canopy_r * (0.55 + 0.45 * rng.random((canopy_n, 1)) ** 0.5))
    shell[:, 1] *= 0.85
    cx = base_x + shell[:, 0] + sway.mean()
    cy = height + 0.35 * canopy_r + shell[:, 1] * 0.9
    cz = base_z + shell[:, 2]
    canopy_pos = np.stack([cx, cy, cz], axis=1)
    cs = rng.uniform(0.22, 0.46, canopy_n)[:, None] * np.array([[1.0, 0.85, 1.0]])
    ht = np.clip((canopy_pos[:, 1:2] - height) / (canopy_r * 1.4), 0, 1)
    canopy_rgb = lerp_color([28, 130, 120], [150, 240, 200], ht)
    canopy_rgb = canopy_rgb + rng.normal(0, 7, size=(canopy_n, 3))
    canopy_a = rng.uniform(70, 140, canopy_n)

    positions = np.concatenate([trunk_pos, canopy_pos])
    scales = np.concatenate([trunk_s, cs])
    rgb = np.concatenate([trunk_rgb, canopy_rgb])
    alpha = np.concatenate([trunk_a, canopy_a])
    return part(positions, scales, rgb, alpha)


def make_lanterns(rng):
    count = 9
    parts = []
    angles = np.linspace(0, 2 * np.pi, count, endpoint=False) + rng.uniform(-0.2, 0.2, count)
    radii = rng.uniform(2.0, 6.2, count)
    heights = rng.uniform(1.1, 2.8, count)
    for i in range(count):
        cx = radii[i] * np.cos(angles[i])
        cz = radii[i] * np.sin(angles[i])
        cy = heights[i]
        core_pos = np.array([[cx, cy, cz], [cx, cy + 0.02, cz], [cx, cy - 0.02, cz]])
        core_s = np.full((3, 3), 0.085)
        core_rgb = np.tile(np.array([[255.0, 196.0, 110.0]]), (3, 1))
        core_a = np.full(3, 255.0)
        halo_n = 36
        offs = rng.normal(size=(halo_n, 3))
        offs = offs / np.linalg.norm(offs, axis=1, keepdims=True) * rng.uniform(0.08, 0.24, (halo_n, 1))
        halo_pos = np.array([cx, cy, cz])[None, :] + offs
        halo_s = rng.uniform(0.14, 0.26, halo_n)[:, None] * np.ones((1, 3))
        halo_rgb = np.tile(np.array([[255.0, 172.0, 84.0]]), (halo_n, 1))
        halo_a = rng.uniform(28, 70, halo_n)
        parts.append(part(np.concatenate([core_pos, halo_pos]), np.concatenate([core_s, halo_s]),
                          np.concatenate([core_rgb, halo_rgb]), np.concatenate([core_a, halo_a])))
    return parts


def make_aurora(rng):
    parts = []
    for ribbon in range(2):
        n = 900
        t = np.linspace(0, 1, n)
        base_y = 7.0 + ribbon * 1.3
        x = (t - 0.5) * 15.0
        z = -2.5 + ribbon * 5.0 + 1.8 * np.sin(t * 5.0 + ribbon * 2.0)
        y = base_y + 0.7 * np.sin(t * 8.0 + ribbon) + rng.normal(0, 0.1, n)
        positions = np.stack([x, y, z], axis=1)
        d = np.gradient(positions, axis=0)
        rot = splatio.quat_to_uint8(quat_align_x_to(d)).astype(np.uint8)
        sx = rng.uniform(0.5, 0.9, n)
        sy = rng.uniform(0.16, 0.3, n)
        sz = rng.uniform(0.05, 0.1, n)
        scales = np.stack([sx, sy, sz], axis=1)
        rgb = lerp_color([70, 220, 190], [150, 110, 245], t[:, None])
        alpha = 26 + 44 * np.sin(t * np.pi) + rng.uniform(-6, 6, n)
        parts.append(part(positions, scales, rgb, alpha, rot))
    return parts


def make_stars(rng):
    count = 500
    u = rng.normal(size=(count, 3))
    u = u / np.linalg.norm(u, axis=1, keepdims=True)
    u[:, 1] = np.abs(u[:, 1])
    r = rng.uniform(9.0, 13.0, (count, 1))
    positions = u * r
    positions[:, 1] += 1.5
    scales = rng.uniform(0.02, 0.045, count)[:, None] * np.ones((1, 3))
    rgb = np.tile(np.array([[220.0, 228.0, 255.0]]), (count, 1)) + rng.normal(0, 10, (count, 3))
    alpha = rng.uniform(120, 220, count)
    return part(positions, scales, rgb, alpha)


def make_moon(rng):
    halo_n = 30
    center = np.array([5.5, 8.6, -5.0])
    core_pos = center[None, :]
    core_s = np.full((1, 3), 0.5)
    core_rgb = np.array([[236.0, 238.0, 224.0]])
    core_a = np.array([255.0])
    offs = rng.normal(size=(halo_n, 3))
    offs = offs / np.linalg.norm(offs, axis=1, keepdims=True) * rng.uniform(0.35, 0.9, (halo_n, 1))
    halo_pos = center[None, :] + offs
    halo_s = rng.uniform(0.25, 0.5, halo_n)[:, None] * np.ones((1, 3))
    halo_rgb = np.tile(np.array([[210.0, 216.0, 210.0]]), (halo_n, 1))
    halo_a = rng.uniform(16, 40, halo_n)
    return part(np.concatenate([core_pos, halo_pos]), np.concatenate([core_s, halo_s]),
                np.concatenate([core_rgb, halo_rgb]), np.concatenate([core_a, halo_a]))


def build_scene():
    rng = np.random.default_rng(360)
    pieces = [make_ground(rng)]
    tree_angles = np.linspace(0, 2 * np.pi, 5, endpoint=False) + 0.4
    tree_radii = [4.6, 5.4, 4.9, 5.8, 5.1]
    for angle, radius in zip(tree_angles, tree_radii):
        height = rng.uniform(2.2, 3.2)
        canopy = rng.uniform(1.0, 1.5)
        pieces.append(make_tree(rng, radius * np.cos(angle), radius * np.sin(angle), height, canopy))
    pieces.extend(make_lanterns(rng))
    pieces.extend(make_aurora(rng))
    pieces.append(make_stars(rng))
    pieces.append(make_moon(rng))
    positions = np.concatenate([p[0] for p in pieces])
    scales = np.concatenate([p[1] for p in pieces])
    colors = np.concatenate([p[2] for p in pieces])
    rotations = np.concatenate([p[3] for p in pieces])
    return positions, scales, colors, rotations


def main() -> None:
    out_path = Path(__file__).parent / "demo" / "demo.splat"
    out_path.parent.mkdir(exist_ok=True)
    positions, scales, colors, rotations = build_scene()
    count = splatio.write_splat(out_path, positions, scales, colors, rotations)
    splatio.validate_splat(out_path)
    print(f"wrote {out_path} with {count} gaussians")


if __name__ == "__main__":
    main()
