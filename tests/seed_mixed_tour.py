"""Generate photos of every shape Orbit Tour now accepts, so the editor can be
exercised without owning a phone, a 360 camera or a GPS fix.

Writes four JPEGs into a folder and, unless --files-only is passed, creates a
tour on a running server and uploads them as raw files so a browser can fetch
them back and drop them through the real upload path.

    python tests/seed_mixed_tour.py            # needs server.py running
    python tests/seed_mixed_tour.py --files-only

Needs Pillow. End users never run this.
"""
from __future__ import annotations

import argparse
import json
import math
import struct
import sys
import urllib.request
import uuid
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from PIL.TiffImagePlugin import IFDRational

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = REPO_ROOT / "tours" / "_mixed-fixtures"


def font(size: int):
    try:
        return ImageFont.load_default(size)
    except TypeError:  # older Pillow without the size argument
        return ImageFont.load_default()


def draw_ruler(img: Image.Image, title: str, subtitle: str, h_fov: float) -> None:
    """Paint a degree ruler across the image. If the viewer maps the photo onto
    the right slice of sphere these marks land where they claim to."""
    w, h = img.size
    d = ImageDraw.Draw(img)
    d.line([(0, h // 2), (w, h // 2)], fill=(255, 255, 255), width=max(2, h // 300))
    step = 30
    marks = int(h_fov // step)
    for k in range(marks + 1):
        x = int(w * k * step / h_fov)
        if x >= w:
            break
        d.line([(x, h * 0.42), (x, h * 0.58)], fill=(255, 255, 255), width=max(1, h // 500))
        d.text((x + 6, h * 0.52), f"{k * step - int(h_fov // 2)}", fill=(255, 255, 255), font=font(max(14, h // 26)))
    big = font(max(24, h // 8))
    box = d.textbbox((0, 0), title, font=big)
    d.text(((w - box[2]) / 2, h * 0.16), title, fill=(255, 255, 255), font=big)
    small = font(max(13, h // 24))
    box = d.textbbox((0, 0), subtitle, font=small)
    d.text(((w - box[2]) / 2, h * 0.72), subtitle, fill=(230, 230, 240), font=small)


def gradient(size: tuple[int, int], top: tuple, bottom: tuple) -> Image.Image:
    w, h = size
    img = Image.new("RGB", size)
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img


def gps_exif(lat: float, lon: float, heading: float | None):
    """Pillow writes a nested GPS IFD when it is handed IFDRationals and refs.
    Plain tuples look like extra entries to it, not like one fraction."""
    exif = Image.Exif()
    gps = exif.get_ifd(0x8825)

    def dms(value: float):
        value = abs(value)
        deg = int(value)
        minutes = int((value - deg) * 60)
        seconds = round((value - deg - minutes / 60) * 3600 * 100)
        return (IFDRational(deg, 1), IFDRational(minutes, 1), IFDRational(seconds, 100))

    gps[1] = "N" if lat >= 0 else "S"
    gps[2] = dms(lat)
    gps[3] = "E" if lon >= 0 else "W"
    gps[4] = dms(lon)
    if heading is not None:
        gps[16] = "T"
        gps[17] = IFDRational(round(heading * 100), 100)
    return exif


def xmp_packet(full_w: int, full_h: int, crop_w: int, crop_h: int, left: int, top: int) -> bytes:
    """The GPano block Google Camera writes, in the attribute form."""
    body = (
        '<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>'
        '<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF '
        'xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
        '<rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/" '
        'GPano:ProjectionType="equirectangular" '
        f'GPano:FullPanoWidthPixels="{full_w}" GPano:FullPanoHeightPixels="{full_h}" '
        f'GPano:CroppedAreaImageWidthPixels="{crop_w}" GPano:CroppedAreaImageHeightPixels="{crop_h}" '
        f'GPano:CroppedAreaLeftPixels="{left}" GPano:CroppedAreaTopPixels="{top}" '
        # the tag that makes the viewer spin the whole sphere unless it is neutralised
        'GPano:PoseHeadingDegrees="217.5"/>'
        "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>"
    )
    return body.encode("utf-8")


def splice_xmp(path: Path, packet: bytes) -> None:
    """Insert an APP1 XMP segment straight after the JPEG's SOI. Pillow's own
    xmp= keyword is version-dependent; sixteen bytes of struct never are."""
    raw = path.read_bytes()
    if raw[:2] != b"\xff\xd8":
        raise SystemExit(f"{path} is not a JPEG")
    payload = b"http://ns.adobe.com/xap/1.0/\x00" + packet
    segment = b"\xff\xe1" + struct.pack(">H", len(payload) + 2) + payload
    path.write_bytes(raw[:2] + segment + raw[2:])


FIXTURES = [
    dict(
        key="full-equirect", name="Full 360 equirect",
        size=(3072, 1536), top=(38, 64, 110), bottom=(16, 20, 30), h_fov=360,
        subtitle="2:1, what an Insta360 export looks like. Expect no coverage override.",
        gps=(40.44160, -79.98330, 0.0),
    ),
    dict(
        key="phone-sweep", name="Phone sweep panorama",
        size=(3600, 720), top=(120, 70, 30), bottom=(28, 18, 12), h_fov=300,
        subtitle="5:1 strip, no metadata at all. The editor has to guess, then you correct it.",
        gps=(40.44175, -79.98330, 90.0),
    ),
    dict(
        key="photo-sphere", name="Partial photo sphere",
        size=(2000, 500), top=(30, 96, 60), bottom=(12, 24, 18), h_fov=90,
        subtitle="Carries GPano XMP saying 90 degrees. No guessing needed.",
        gpano=(8000, 4000, 2000, 500, 3000, 1750),
        gps=(40.44175, -79.98305, 180.0),
    ),
    dict(
        key="plain-photo", name="Ordinary photo",
        size=(1200, 900), top=(70, 40, 96), bottom=(20, 12, 28), h_fov=80,
        subtitle="4:3 snapshot. Becomes a window in the sphere rather than being refused.",
        gps=None,
    ),
]


def build_files() -> list[Path]:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    written = []
    for spec in FIXTURES:
        img = gradient(spec["size"], spec["top"], spec["bottom"])
        draw_ruler(img, spec["name"], spec["subtitle"], spec["h_fov"])
        path = OUT_DIR / f"{spec['key']}.jpg"
        if spec.get("gps"):
            lat, lon, heading = spec["gps"]
            img.save(path, "JPEG", quality=84, exif=gps_exif(lat, lon, heading))
        else:
            img.save(path, "JPEG", quality=84)
        if spec.get("gpano"):
            splice_xmp(path, xmp_packet(*spec["gpano"]))
        written.append(path)
        print(f"  {path.name:20} {spec['size'][0]}x{spec['size'][1]}"
              f"{'  +gps' if spec.get('gps') else ''}{'  +gpano' if spec.get('gpano') else ''}")
    return written


def post(url: str, payload: dict) -> dict:
    req = urllib.request.Request(url, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read())


def upload(url: str, path: Path) -> dict:
    boundary = uuid.uuid4().hex
    body = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{path.name}\"\r\n"
        "Content-Type: image/jpeg\r\n\r\n".encode()
        + path.read_bytes()
        + f"\r\n--{boundary}--\r\n".encode()
    )
    req = urllib.request.Request(url, data=body, method="POST",
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--files-only", action="store_true", help="write the JPEGs and stop")
    ap.add_argument("--port", type=int, default=7360)
    args = ap.parse_args()

    print(f"writing fixtures to {OUT_DIR}")
    files = build_files()
    if args.files_only:
        print("\nDrop these into any tour's editor to see each shape handled.")
        return 0

    base = f"http://127.0.0.1:{args.port}"
    try:
        tour = post(f"{base}/api/tours", {"name": "Mixed photo shapes"})
    except OSError as err:
        print(f"\ncould not reach {base}: {err}\nstart server.py first, or pass --files-only")
        return 1
    for path in files:
        upload(f"{base}/api/tours/{tour['id']}/files", path)
    print(f"\ntour {tour['id']} holds the raw files")
    print(f"open {base}/tour?edit={tour['id']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
