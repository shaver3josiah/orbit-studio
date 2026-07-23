from __future__ import annotations

import json
import platform
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Optional

MIN_PYTHON = (3, 11)


def find_tool(name: str, repo_root: Path, subdir: str) -> Optional[Path]:
    found = shutil.which(name)
    if found:
        return Path(found)
    exe_names = [name, f"{name}.exe"]
    paths_file = repo_root / "tools" / "paths.json"
    if paths_file.exists():
        try:
            hints = json.loads(paths_file.read_text())
            hint = hints.get(f"{name}_bin") or hints.get(f"{subdir}_bin")
            if hint:
                for exe_name in exe_names:
                    candidate = Path(hint) / exe_name
                    if candidate.exists():
                        return candidate
        except Exception:
            pass
    search_dirs = [
        repo_root / "tools" / subdir,
        repo_root / "tools" / subdir / "bin",
    ]
    for directory in search_dirs:
        for exe_name in exe_names:
            candidate = directory / exe_name
            if candidate.exists():
                return candidate
    base = repo_root / "tools" / subdir
    if base.exists():
        for exe_name in exe_names:
            for candidate in sorted(base.rglob(exe_name)):
                if candidate.is_file():
                    return candidate
    return None


def run_version(path: Path, arg: str = "-version") -> str:
    try:
        result = subprocess.run([str(path), arg], capture_output=True, text=True, timeout=10)
        text = (result.stdout or result.stderr or "").strip()
        return text.splitlines()[0] if text else ""
    except Exception:
        return ""


def find_ffmpeg(repo_root: Path) -> Optional[Path]:
    return find_tool("ffmpeg", repo_root, "ffmpeg")


def find_ffprobe(repo_root: Path) -> Optional[Path]:
    return find_tool("ffprobe", repo_root, "ffmpeg")


def find_brush(repo_root: Path) -> Optional[Path]:
    found = find_tool("brush", repo_root, "brush")
    if found is None:
        found = find_tool("brush_app", repo_root, "brush")
    return found


def check_ffmpeg(repo_root: Path) -> dict:
    path = find_ffmpeg(repo_root)
    if path is None:
        return {"ok": False, "version": "", "path": ""}
    version = run_version(path)
    return {"ok": bool(version), "version": version, "path": str(path)}


def check_brush(repo_root: Path) -> dict:
    path = find_brush(repo_root)
    if path is None:
        return {"ok": False, "path": ""}
    return {"ok": True, "path": str(path)}


def detect_gpu_name() -> str:
    if platform.system() == "Windows":
        try:
            result = subprocess.run(
                ["wmic", "path", "win32_VideoController", "get", "name"],
                capture_output=True,
                text=True,
                timeout=10,
            )
            lines = [line.strip() for line in result.stdout.splitlines() if line.strip() and line.strip() != "Name"]
            if lines:
                return lines[0]
        except Exception:
            pass
        try:
            result = subprocess.run(
                [
                    "powershell",
                    "-NoProfile",
                    "-Command",
                    "Get-CimInstance Win32_VideoController | Select-Object -ExpandProperty Name",
                ],
                capture_output=True,
                text=True,
                timeout=15,
            )
            lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
            if lines:
                return lines[0]
        except Exception:
            pass
    else:
        try:
            result = subprocess.run(["lspci"], capture_output=True, text=True, timeout=10)
            for line in result.stdout.splitlines():
                if "VGA" in line or "3D controller" in line:
                    return line.split(":", 2)[-1].strip()
        except Exception:
            pass
    return "unknown"


def check_python() -> dict:
    ok = sys.version_info[:2] >= MIN_PYTHON
    return {"ok": ok, "version": platform.python_version()}


def run_doctor(repo_root: Path, projects_dir: Path) -> dict:
    python_info = check_python()
    ffmpeg_info = check_ffmpeg(repo_root)
    brush_info = check_brush(repo_root)
    gpu_name = detect_gpu_name()
    usage_target = projects_dir if projects_dir.exists() else repo_root
    usage = shutil.disk_usage(str(usage_target))
    disk_free_gb = round(usage.free / (1024 ** 3), 2)
    ok = python_info["ok"] and ffmpeg_info["ok"]
    return {
        "ok": ok,
        "python": python_info,
        "ffmpeg": ffmpeg_info,
        "brush": brush_info,
        "gpu": {"name": gpu_name},
        "disk_free_gb": disk_free_gb,
        "projects_dir": str(projects_dir),
    }
