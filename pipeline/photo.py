"""One place that decides how a still photo is opened.

Upload validation and the frames stage both have to agree about what counts as a
usable panorama. When they drifted apart it cost real photos: upload asked Pillow
to decode strictly, Pillow refuses a truncated JPEG outright, and a 70-percent
complete file that the pipeline could happily have processed was turned away at
the door. The rules live here so there is only one of them.

Pure Pillow on purpose - no numpy - so server.py can import it lazily without
dragging the whole splat pipeline in.
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageFile, ImageOps

# A partly-copied photo still has most of its picture in it, and for an
# equirectangular frame the missing part is the bottom of the sphere - usually the
# nadir, the least useful region. Refusing it outright loses a whole capture over
# one bad file copy; loading what is there and letting COLMAP's registration
# percentage report the damage is the better trade.
ImageFile.LOAD_TRUNCATED_IMAGES = True

# Pillow's decompression-bomb guard warns past ~89 MP and raises past ~179 MP. A
# 16K equirect is 134 MP and entirely legitimate here, so the guard would reject
# real captures. This is a local tool opening the user's own files, so the limit is
# raised rather than removed - 500 MP is past any 360 camera and still catches a
# genuinely absurd file instead of trying to allocate it.
MAX_PIXELS = 500_000_000
Image.MAX_IMAGE_PIXELS = MAX_PIXELS


def open_photo(path: Path) -> Image.Image:
    """Open a still the way the whole pipeline should see it.

    EXIF orientation is applied here rather than ignored: a phone panorama carrying
    an orientation flag is otherwise stored sideways, and because a rotation swaps
    width and height it would also fail the 2:1 equirectangular check for a reason
    that has nothing to do with the photograph.
    """
    # Re-asserted per call, not just at import. Both are process-wide Pillow globals
    # that any other importer can flip back underneath us, and when that happened the
    # only symptom was good photos quietly being refused again - caught exactly that
    # way by a test harness whose own cleanup reset the flag.
    ImageFile.LOAD_TRUNCATED_IMAGES = True
    Image.MAX_IMAGE_PIXELS = MAX_PIXELS
    with Image.open(path) as image:
        image.load()
        return ImageOps.exif_transpose(image) or image


def inspect(path: Path) -> tuple[int, int, str | None]:
    """Return (width, height, fault). Fault is None when the photo is usable.

    Pillow is the arbiter, because Pillow is what the frames stage runs. ffprobe is
    a useful fast pre-check but disagrees in both directions: it reports dimensions
    for a JPEG whose data is cut off, and it happily reads dimensions from a file
    with junk before the SOI marker that Pillow cannot open at all.
    """
    if not path.exists():
        return 0, 0, "the file is missing"
    if path.stat().st_size == 0:
        return 0, 0, "0 bytes, the copy never finished"
    try:
        image = open_photo(path)
    except Image.DecompressionBombError:
        return 0, 0, "far too large to be a photograph"
    except Exception as exc:
        name = type(exc).__name__
        if name == "UnidentifiedImageError":
            return 0, 0, "no picture inside despite the name, usually a HEIC renamed rather than converted"
        return 0, 0, f"the picture data could not be decoded ({name})"
    width, height = image.size
    image.close()
    if not (width and height):
        return 0, 0, "the picture has no size"
    return width, height, None
