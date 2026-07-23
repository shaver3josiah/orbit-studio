from __future__ import annotations

import json
import os
import sys
import tempfile
import threading
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT))

TEMP_HOME = Path(tempfile.mkdtemp(prefix="orbit_tour_smoke_"))
os.environ["ORBIT_HOME"] = str(TEMP_HOME)

import server as orbit_server

PORT = 7362
BASE = f"http://127.0.0.1:{PORT}"

PASS_COUNT = 0
FAIL_COUNT = 0


def check(condition: bool, message: str) -> None:
    global PASS_COUNT, FAIL_COUNT
    if condition:
        PASS_COUNT += 1
        print(f"PASS {message}")
    else:
        FAIL_COUNT += 1
        print(f"FAIL {message}")


def http_json(method: str, path: str, payload=None):
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))


def http_json_allow_error(method: str, path: str, payload=None):
    try:
        return http_json(method, path, payload)
    except urllib.error.HTTPError as exc:
        body = exc.read()
        try:
            return exc.code, json.loads(body.decode("utf-8"))
        except Exception:
            return exc.code, {"error": "unknown", "detail": body.decode("utf-8", "replace")}


def http_upload(path: str, filename: str, content: bytes):
    boundary = "OrbitTourSmokeBoundary"
    body = b"".join(
        [
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode(),
            b"Content-Type: application/octet-stream\r\n\r\n",
            content,
            b"\r\n",
            f"--{boundary}--\r\n".encode(),
        ]
    )
    req = urllib.request.Request(
        BASE + path,
        data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read().decode("utf-8"))


def http_status(path: str) -> int:
    try:
        with urllib.request.urlopen(BASE + path, timeout=30) as resp:
            return resp.status
    except urllib.error.HTTPError as exc:
        return exc.code


def main() -> int:
    server = orbit_server.build_server(PORT)
    threading.Thread(target=server.serve_forever, daemon=True).start()

    status, created = http_json("POST", "/api/tours", {"name": "Smoke Tour"})
    check(status == 200 and created["id"].startswith("smoke-tour-"), "create tour")
    tid = created["id"]

    status, listed = http_json("GET", "/api/tours")
    check(any(t["id"] == tid for t in listed["tours"]), "tour appears in list")

    status, up1 = http_upload(f"/api/tours/{tid}/files", "pano.jpg", b"\xff\xd8fakejpg")
    check(status == 200 and up1["file"].endswith(".jpg"), "upload pano file")
    status, up2 = http_upload(f"/api/tours/{tid}/files", "orphan.jpg", b"\xff\xd8orphan")
    check(status == 200, "upload orphan file")

    status, _ = http_upload(f"/api/tours/{tid}/files", "evil.exe", b"MZ")
    check(status == 400, "reject non-image upload")

    doc = dict(created)
    doc["scenes"] = [{"id": "s1", "name": "Scene 1", "file": up1["file"], "hotspots": []}]
    status, saved = http_json("POST", f"/api/tours/{tid}", doc)
    check(status == 200 and saved["updated"] > saved["created"], "save tour doc")

    check(http_status(f"/api/tours/{tid}/files/{up1['file']}") == 200, "referenced file survives save")
    check(http_status(f"/api/tours/{tid}/files/{up2['file']}") == 404, "orphan file pruned on save")

    status, _ = http_json_allow_error("POST", f"/api/tours/{tid}", {"scenes": "nope"})
    check(status == 400, "reject doc without scenes list")

    status, _ = http_json_allow_error("GET", "/api/tours/..")
    check(status == 404, "path traversal id rejected")

    status, _ = http_json("DELETE", f"/api/tours/{tid}")
    check(status == 200, "delete tour")
    status, _ = http_json_allow_error("GET", f"/api/tours/{tid}")
    check(status == 404, "tour gone after delete")

    app_path = REPO_ROOT / "tour" / "index.html"
    if app_path.exists():
        req = urllib.request.Request(BASE + "/tour")
        with urllib.request.urlopen(req, timeout=30) as resp:
            check(resp.status == 200 and b"<" in resp.read(), "tour app served at /tour")
    else:
        print("SKIP tour app not built yet")

    server.shutdown()
    print(f"\n{PASS_COUNT} passed, {FAIL_COUNT} failed")
    return 1 if FAIL_COUNT else 0


if __name__ == "__main__":
    raise SystemExit(main())
