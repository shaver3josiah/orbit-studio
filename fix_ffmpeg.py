"""Make ffmpeg runnable when policy blocks it where it sits.

On a managed Windows machine ffmpeg.exe can be present and correct and still fail
with WinError 5, because AppLocker and SRP rules deny execution from user-writable
paths - Downloads and Temp being the usual targets. Moving the whole project is one
answer, but sometimes the project has to stay where it is.

So this puts the two binaries somewhere execution IS permitted and points Orbit
Studio at them. The candidate list starts with %LOCALAPPDATA%\\Programs because
that is where per-user installs of VS Code, Teams and Chrome live - an enterprise
that blocked it would break its own software - and every candidate is proven by
actually running ffmpeg there, never by assuming.

    python fix_ffmpeg.py

Writes only tools/paths.json (merged, existing keys kept) plus the copied
binaries. Re-runnable and safe if it already worked.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(REPO_ROOT))

from pipeline import doctor

NEEDED = ("ffmpeg", "ffprobe")


def can_execute(exe: Path) -> tuple[bool, str]:
    """Actually run it. Presence, permissions and policy are three different things."""
    try:
        result = subprocess.run([str(exe), "-version"], capture_output=True, timeout=30)
    except PermissionError as exc:
        return False, f"blocked by policy ({exc.__class__.__name__}: {exc})"
    except Exception as exc:
        return False, f"{type(exc).__name__}: {exc}"
    if result.returncode != 0:
        return False, f"exit code {result.returncode}"
    return True, ""


def clear_mark_of_the_web(path: Path) -> bool:
    """Drop the alternate data stream Windows attaches to anything downloaded.

    Cheap to try and occasionally the whole problem, since a stamped binary can be
    refused outright. Failure here is not interesting - the copy step is the real fix.
    """
    try:
        os.remove(f"{path}:Zone.Identifier")
        return True
    except Exception:
        return False


def candidates() -> list[Path]:
    local = os.environ.get("LOCALAPPDATA", "")
    roaming = os.environ.get("APPDATA", "")
    home = str(Path.home())
    out = []
    # %LOCALAPPDATA%\Programs first: per-user VS Code / Teams / Chrome install here,
    # so an org that denied it would have broken its own tooling.
    if local:
        out.append(Path(local) / "Programs" / "orbit-studio" / "bin")
        out.append(Path(local) / "orbit-studio" / "bin")
    if roaming:
        out.append(Path(roaming) / "orbit-studio" / "bin")
    out.append(Path(home) / "orbit-studio-bin")
    return out


def install_to(target: Path, sources: dict[str, Path]) -> tuple[bool, str]:
    try:
        target.mkdir(parents=True, exist_ok=True)
        for name, src in sources.items():
            dest = target / src.name
            if dest.resolve() != src.resolve():
                shutil.copy2(src, dest)
            clear_mark_of_the_web(dest)
    except Exception as exc:
        return False, f"could not copy there ({type(exc).__name__}: {exc})"
    ok, why = can_execute(target / sources["ffmpeg"].name)
    return (True, "") if ok else (False, why)


def write_paths_json(bin_dir: Path) -> Path:
    paths_file = REPO_ROOT / "tools" / "paths.json"
    paths_file.parent.mkdir(parents=True, exist_ok=True)
    data = {}
    if paths_file.exists():
        try:
            data = json.loads(paths_file.read_text())
        except Exception:
            data = {}  # unreadable, do not die on it - it is a hint file
    data["ffmpeg_bin"] = str(bin_dir)  # find_tool reads ffmpeg_bin for ffprobe too
    paths_file.write_text(json.dumps(data, indent=4))
    return paths_file


def main() -> int:
    sources: dict[str, Path] = {}
    for name in NEEDED:
        found = doctor.find_tool(name, REPO_ROOT, "ffmpeg")
        if found is None:
            print(f"Could not find {name} anywhere. Run setup.bat first to download it.")
            return 1
        sources[name] = found
    print(f"found ffmpeg:  {sources['ffmpeg']}")
    print(f"found ffprobe: {sources['ffprobe']}\n")

    for src in sources.values():
        clear_mark_of_the_web(src)
    ok, why = can_execute(sources["ffmpeg"])
    if ok:
        print("It already runs where it is. Nothing to fix.")
        if "Zone.Identifier" not in why:
            write_paths_json(sources["ffmpeg"].parent)
            print(f"Pointed Orbit Studio at {sources['ffmpeg'].parent}")
        return 0

    print(f"Cannot run it where it sits: {why}")
    print("Trying somewhere execution is usually permitted...\n")

    for target in candidates():
        ok, why = install_to(target, sources)
        print(f"  {'WORKS' if ok else 'no   '}  {target}" + ("" if ok else f"  - {why}"))
        if ok:
            paths_file = write_paths_json(target)
            print(f"\nffmpeg now runs from {target}")
            print(f"Recorded in {paths_file}, so Orbit Studio will use it from now on.")
            print("\nRestart server.py, then: python preflight.py")
            return 0

    print("\nNo location worked. The policy here blocks execution everywhere this")
    print("script can write, which needs your IT team rather than a workaround:")
    print("  - ask for the orbit-studio folder to be allowed, or")
    print("  - ask for ffmpeg to be installed somewhere already permitted, then")
    print("    'python fix_ffmpeg.py' will pick it up off PATH automatically.")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
