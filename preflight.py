"""Answer "will this machine run Orbit Studio?" before you spend a shoot finding out.

Written for locked-down enterprise Windows, where the things that break are never
CPU or RAM - they are policy. A managed laptop can have 32 GB and still fail every
one of these: an AppLocker rule that lets ffmpeg.exe EXIST but not RUN, a TLS-
inspecting proxy that blocks the two CDN hosts the viewer imports from, endpoint
security that refuses a listening socket on localhost, or a blocked Colab.

    python preflight.py

Read-only. Talks to the network but downloads nothing and writes nothing.
"""

from __future__ import annotations

import shutil
import socket
import subprocess
import sys
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(REPO_ROOT))

from pipeline import doctor

PORT = 7360
# Exactly the hosts studio/index.html names in its importmap, plus the notebook.
# sparkjs.dev is the one to watch: it is not a household CDN, so category-based
# proxy filters block it far more often than they block jsdelivr.
RUNTIME_URLS = [
    ("three.js core", "https://cdn.jsdelivr.net/npm/three@0.180.0/build/three.module.js"),
    ("three.js split chunk", "https://cdn.jsdelivr.net/npm/three@0.180.0/build/three.core.js"),
    ("Spark splat renderer", "https://sparkjs.dev/releases/spark/2.1.0/spark.module.js"),
    ("Colab (training)", "https://colab.research.google.com/"),
]

rows: list[tuple[bool, str, str]] = []


def record(ok: bool, name: str, detail: str = "") -> None:
    rows.append((ok, name, detail))


def check_python() -> None:
    info = doctor.check_python()
    record(info["ok"], f"Python {info['version']}", "" if info["ok"] else "need 3.11+")


def check_libraries() -> None:
    for mod in ("numpy", "PIL"):
        try:
            __import__(mod)
            record(True, f"{mod} importable")
        except Exception as exc:
            record(False, f"{mod} importable", str(exc)[:60])


def check_ffmpeg() -> None:
    path = doctor.find_ffmpeg(REPO_ROOT)
    if path is None:
        record(False, "ffmpeg present", "not on PATH and not under tools/ - run setup.bat")
        return
    # Presence is not permission. AppLocker blocks EXECUTION of an unsigned exe in a
    # user profile, which looks exactly like a working install until you run it.
    try:
        result = subprocess.run([str(path), "-hide_banner", "-filters"],
                                capture_output=True, text=True, timeout=30)
    except Exception as exc:
        record(False, "ffmpeg runs", f"found at {path} but will not execute: {str(exc)[:50]}")
        return
    if result.returncode != 0:
        record(False, "ffmpeg runs", f"exit {result.returncode} - likely blocked by policy")
        return
    record(True, "ffmpeg runs", str(path))
    record("v360" in result.stdout, "ffmpeg has v360 filter",
           "" if "v360" in result.stdout else "this build cannot reframe 360 video")


def check_port() -> None:
    # Deliberately NO SO_REUSEADDR: on Windows it lets you bind a port that is
    # actively LISTENing, so the probe reported "ok" with a server demonstrably
    # running on 7360. Without it, an occupied port fails with errno 10048 the way
    # the check needs it to.
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.bind(("127.0.0.1", PORT))
        record(True, f"can serve on localhost:{PORT}")
    except OSError as exc:
        record(False, f"can serve on localhost:{PORT}",
               "already in use (a server is running) " if exc.errno in (48, 98, 10048)
               else f"blocked: {str(exc)[:50]}")
    finally:
        sock.close()


def check_network() -> None:
    for name, url in RUNTIME_URLS:
        try:
            request = urllib.request.Request(url, method="GET")
            with urllib.request.urlopen(request, timeout=20) as response:
                record(200 <= response.status < 400, name, f"HTTP {response.status}")
        except Exception as exc:
            record(False, name, f"unreachable: {type(exc).__name__} {str(exc)[:45]}")


def check_disk() -> None:
    free_gb = round(shutil.disk_usage(str(REPO_ROOT)).free / (1024 ** 3), 1)
    # A 20-photo job at 18 views writes ~360 crops plus the source panoramas and a
    # bundle; video at the defaults writes far more. 10 GB is a comfortable floor.
    record(free_gb >= 10, f"{free_gb} GB free disk", "" if free_gb >= 10 else "want 10 GB+")


def main() -> None:
    print("Orbit Studio preflight\n")
    check_python()
    check_libraries()
    check_ffmpeg()
    check_port()
    check_disk()
    print("Checking the two CDN hosts the viewer imports from, and Colab ...\n")
    check_network()

    for ok, name, detail in rows:
        print(f"  {'ok  ' if ok else 'FAIL'}  {name}{'  - ' + detail if detail else ''}")

    failed = [name for ok, name, _ in rows if not ok]
    print()
    if not failed:
        print("All clear. Capture prep, the viewer and the Colab handoff should all work here.")
        return
    print(f"{len(failed)} problem(s): {', '.join(failed)}\n")
    if any("Spark" in f or "three.js" in f for f in failed):
        print("  The viewer imports three.js and Spark from a CDN at runtime, so a blocked")
        print("  host means the flythrough will not render - the app still opens and still")
        print("  builds bundles. The fix is to vendor those files into studio/ the way")
        print("  tour/vendor/ already does for the tour app. Ask and it is a small change.")
    if any("Colab" in f for f in failed):
        print("  Colab is where training runs. Blocked means bundles build but nothing")
        print("  trains; you would need a machine that can reach it for that one step.")
    if any("ffmpeg" in f for f in failed):
        print("  No usable ffmpeg means no frame extraction and no 360 reframing, which is")
        print("  the whole capture-prep stage. Run setup.bat, or put ffmpeg on PATH.")
    sys.exit(1)


if __name__ == "__main__":
    main()
