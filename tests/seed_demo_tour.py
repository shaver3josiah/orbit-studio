"""Build the shipped sample tour from generated equirectangular panoramas.

Writes into tour/sample/ (committed to the repo). The server copies this into
a fresh user's tours/ folder on first run, so a new user can walk a real tour
before they own any 360 photos. Needs Pillow; end users never run this — they
get the already-generated result. Usage: python tests/seed_demo_tour.py
"""
from __future__ import annotations

import json
import math
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

REPO_ROOT = Path(__file__).resolve().parent.parent
TOUR_ID = "sample-tour"
TOUR_DIR = REPO_ROOT / "tour" / "sample"
W, H = 4096, 2048

SCENES = [
    ("s1", "Living Room", (38, 64, 110), (16, 20, 30)),
    ("s2", "Kitchen", (120, 70, 30), (28, 18, 12)),
    ("s3", "Terrace", (30, 96, 60), (12, 24, 18)),
]


def font(size: int):
    try:
        return ImageFont.load_default(size)
    except TypeError:  # older Pillow without size arg
        return ImageFont.load_default()


def make_pano(name: str, sky: tuple, floor: tuple, path: Path) -> None:
    img = Image.new("RGB", (W, H))
    draw = ImageDraw.Draw(img)
    for y in range(H):
        t = y / H
        color = tuple(int(sky[i] + (floor[i] - sky[i]) * t) for i in range(3))
        draw.line([(0, y), (W, y)], fill=color)
    # yaw graticule every 30 deg, horizon line, compass labels
    for k in range(12):
        x = int(W * k / 12)
        draw.line([(x, 0), (x, H)], fill=(255, 255, 255, 40), width=2)
    draw.line([(0, H // 2), (W, H // 2)], fill=(255, 255, 255), width=4)
    labels = {0: "0 (front)", 3: "90", 6: "180 (back)", 9: "270"}
    for k, text in labels.items():
        x = (int(W * (0.5 + k / 12)) % W)
        draw.text((x + 12, H // 2 + 16), text, fill=(255, 255, 255), font=font(48))
    big = font(160)
    bbox = draw.textbbox((0, 0), name, font=big)
    draw.text(((W - bbox[2]) / 2, H * 0.28), name, fill=(255, 255, 255), font=big)
    img.save(path, "JPEG", quality=82)


def thumb_for(src: Path, dest: Path) -> None:
    img = Image.open(src)
    img.thumbnail((512, 256))
    img.save(dest, "JPEG", quality=75)


def main() -> None:
    if TOUR_DIR.exists():
        shutil.rmtree(TOUR_DIR)
    files_dir = TOUR_DIR / "files"
    files_dir.mkdir(parents=True)

    scenes = []
    for sid, name, sky, floor in SCENES:
        pano = files_dir / f"{sid}.jpg"
        make_pano(name, sky, floor, pano)
        thumb_for(pano, files_dir / f"{sid}.thumb.jpg")
        scenes.append({
            "id": sid,
            "name": name,
            "file": f"{sid}.jpg",
            "thumb": f"{sid}.thumb.jpg",
            "view": {"yaw": 0, "pitch": 0},
            "hotspots": [],
        })

    # ring of link hotspots: s1 -> s2 -> s3 -> s1, plus a back-link and info spot
    scenes[0]["hotspots"] = [
        {"id": "h1", "type": "link", "yaw": 40, "pitch": -8, "target": "s2", "label": "To the kitchen"},
        {"id": "h2", "type": "info", "yaw": -50, "pitch": 5, "title": "Fireplace",
         "text": "Original 1920s fireplace.\nRestored in 2024."},
    ]
    scenes[1]["hotspots"] = [
        {"id": "h3", "type": "link", "yaw": 120, "pitch": -8, "target": "s3", "label": "Out to the terrace"},
        {"id": "h4", "type": "link", "yaw": -140, "pitch": -8, "target": "s1", "label": "Back to living room"},
    ]
    scenes[2]["hotspots"] = [
        {"id": "h5", "type": "link", "yaw": 0, "pitch": -12, "target": "s1", "label": "Back inside"},
    ]

    logo = Image.new("RGBA", (280, 96), (0, 0, 0, 0))
    ld = ImageDraw.Draw(logo)
    ld.rounded_rectangle([0, 0, 279, 95], radius=18, fill=(11, 11, 14, 210), outline=(242, 242, 247, 255), width=2)
    ld.text((22, 26), "ORBIT DEMO", fill=(242, 242, 247, 255), font=font(40))
    logo.save(files_dir / "logo.png")

    now = datetime.now(timezone.utc).isoformat()
    tour = {
        "id": TOUR_ID,
        "name": "Sample Apartment (delete me)",
        "created": now,
        "updated": now,
        "settings": {
            "startScene": "s1",
            "littlePlanetIntro": True,
            "autorotate": True,
            "autorotateSpeed": 0.7,
            "logo": "logo.png",
            "logoLink": "https://example.com",
        },
        "scenes": scenes,
    }
    (TOUR_DIR / "tour.json").write_text(json.dumps(tour, indent=2), encoding="utf-8")
    print(f"seeded {TOUR_ID}: {len(scenes)} scenes at {TOUR_DIR}")


if __name__ == "__main__":
    main()
