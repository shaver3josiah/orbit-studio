"""Regression guard: server.py (and the Orbit Tour app) must import and serve
on a machine that has NO numpy and NO pillow — i.e. a fresh clone where the
user never ran setup.ps1. The splat pipeline may only load its heavy deps
lazily, when a splat feature is actually used.

Runs the check in a subprocess with stub numpy/PIL modules that raise on
import, so this test works even though the dev machine has the real ones.
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import textwrap
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    stubs = Path(tempfile.mkdtemp(prefix="orbit_nodeps_"))
    (stubs / "numpy.py").write_text('raise ImportError("numpy absent (test)")')
    (stubs / "PIL").mkdir()
    (stubs / "PIL" / "__init__.py").write_text('raise ImportError("pillow absent (test)")')

    child = textwrap.dedent(
        f"""
        import os, sys, tempfile
        sys.path.insert(0, r"{stubs}")            # shadow numpy/pillow with raising stubs
        sys.path.insert(1, r"{REPO_ROOT}")
        os.environ["ORBIT_HOME"] = tempfile.mkdtemp(prefix="orbit_boot_")
        for mod in ("numpy", "PIL"):
            try:
                __import__(mod); print("FAIL stub not shadowing " + mod); sys.exit(1)
            except ImportError:
                pass
        import server                              # must not raise
        assert server.build_server                 # server is usable
        try:
            server.frames.build_fps                # touching a splat stage must fail clearly
            print("FAIL splat stage did not raise"); sys.exit(1)
        except RuntimeError as exc:
            assert "numpy" in str(exc).lower() or "pillow" in str(exc).lower(), exc
        print("OK")
        """
    )
    result = subprocess.run([sys.executable, "-c", child], capture_output=True, text=True)
    out = (result.stdout + result.stderr).strip()
    ok = result.returncode == 0 and out.endswith("OK")
    print("PASS server boots without numpy/pillow" if ok else f"FAIL:\n{out}")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
