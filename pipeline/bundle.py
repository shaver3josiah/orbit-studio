from __future__ import annotations

import json
import zipfile
from pathlib import Path


def build_bundle(project_dir: Path, project_name: str, settings: dict) -> Path:
    crops_dir = project_dir / "crops"
    crop_files = sorted(crops_dir.glob("*.jpg")) if crops_dir.exists() else []
    manifest = {
        "app": "orbit-studio",
        "project": project_name,
        "crops": len(crop_files),
        "rig": settings,
    }
    bundle_path = project_dir / "bundle.zip"
    with zipfile.ZipFile(bundle_path, "w") as archive:
        archive.writestr("manifest.json", json.dumps(manifest, indent=2), zipfile.ZIP_STORED)
        for crop_file in crop_files:
            archive.write(crop_file, arcname=f"crops/{crop_file.name}", compress_type=zipfile.ZIP_STORED)
    return bundle_path
