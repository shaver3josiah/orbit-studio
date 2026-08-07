#!/usr/bin/env python3
"""Every check Orbit Studio has, in one list, runnable anywhere.

    python tests/run_all.py

There were four checks and two places that knew about them: test.bat, for a
person double-clicking on Windows, and nothing at all for a machine. So the
checks ran when somebody remembered to run them, which — measured against this
repo's own history — is not always. Two commits went out in one afternoon
without the suite that covered them ever being run: the Android app shipped
uncompiled because the toolchain looked absent, and a rewrite of the server's
save path shipped without the server suite, which had a check for the exact
handler that changed.

So the list lives here, once, and both callers read it. test.bat keeps the
things a batch file is for — finding Python, and holding the window open — and
delegates the list. CI runs this file directly. A check added here is a check
both of them get, which is the property the pure-helper test argues for at
length about its own exports and is worth having one level up too.

Exit code is 0 only if nothing failed, so a machine can tell.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def has_module(name: str) -> bool:
    """Whether the current interpreter can import something, without importing
    it here — asking in a subprocess keeps this runner itself on stdlib."""
    return subprocess.run(
        [sys.executable, "-c", f"import {name}"],
        capture_output=True,
    ).returncode == 0


# name, argv, and what must exist for the check to be meaningful.
#
# A missing tool is a SKIP, not a failure, and the reasoning is test.bat's own,
# applied to one more case than it had: counting an absent Node as a failure
# meant a locked-down laptop could never see a clean run, so a real failure had
# nothing to stand out against. numpy and Pillow are the same situation. The
# tour half of this project deliberately runs on stdlib alone — test_stdlib_boot
# exists to keep it that way — so a machine that never ran setup.ps1 is an
# ordinary machine here, not a broken one, and the splat suite is simply not a
# question that can be asked on it.
def checks() -> list[tuple[str, list[str], str | None]]:
    node = shutil.which("node")
    return [
        (
            "pure helpers",
            [node or "node", "tests/tour_pano_test.mjs"],
            None if node else "Node.js is not installed; the panorama and plan-view maths need it",
        ),
        ("server API", [sys.executable, "tests/tour_smoke_test.py"], None),
        ("stdlib boot", [sys.executable, "tests/test_stdlib_boot.py"], None),
        (
            "splat pipeline",
            [sys.executable, "tests/smoke_test.py"],
            None if has_module("numpy") and has_module("PIL")
            else "numpy and Pillow are not installed; run setup.ps1 for the splat lane",
        ),
    ]


def main() -> int:
    failed: list[str] = []
    skipped: list[str] = []

    for name, argv, skip_why in checks():
        print(f"\n==> {name} ({' '.join(Path(a).name if '/' in a else a for a in argv)})\n", flush=True)
        if skip_why:
            print(f"  SKIPPED: {skip_why}", flush=True)
            skipped.append(name)
            continue
        # Output is inherited rather than captured: when a check fails the
        # reason has to be on screen from the first run, not after a second
        # diagnostic round trip. Same rule test.bat states.
        if subprocess.run(argv, cwd=ROOT).returncode != 0:
            failed.append(name)

    print()
    if skipped:
        print(f"  SKIPPED: {', '.join(skipped)}")
    if failed:
        print(f"  FAILED: {', '.join(failed)}")
        return 1
    print("  Everything that could run here passed." if skipped else "  All checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
