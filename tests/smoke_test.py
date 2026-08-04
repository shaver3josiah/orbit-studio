from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

import numpy as np

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT))

TEMP_HOME = Path(tempfile.mkdtemp(prefix="orbit_smoke_"))
os.environ["ORBIT_HOME"] = str(TEMP_HOME)

from pipeline import doctor, splatio
import server as orbit_server

PORT = 7361
BASE = f"http://127.0.0.1:{PORT}"

# Resolve ffmpeg exactly the way the server does, so the test exercises the same
# binary the pipeline will use. Looking for a bare "ffmpeg" on PATH silently
# skipped the entire video->frames->crops->bundle path on the target laptop,
# where ffmpeg only exists under tools/.
FFMPEG = doctor.find_ffmpeg(REPO_ROOT)

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


def skip(message: str) -> None:
    print(f"SKIP {message}")


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


def build_multipart(field_name: str, filename: str, content: bytes, content_type: str):
    boundary = "OrbitSmokeBoundary123456"
    parts = [
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="{field_name}"; filename="{filename}"\r\n'.encode(),
        f"Content-Type: {content_type}\r\n\r\n".encode(),
        content,
        b"\r\n",
        f"--{boundary}--\r\n".encode(),
    ]
    body = b"".join(parts)
    headers = {
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        "Content-Length": str(len(body)),
    }
    return body, headers


def http_upload(path: str, field_name: str, filename: str, content: bytes, content_type: str):
    body, headers = build_multipart(field_name, filename, content, content_type)
    req = urllib.request.Request(BASE + path, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body_bytes = exc.read()
        try:
            return exc.code, json.loads(body_bytes.decode("utf-8"))
        except Exception:
            return exc.code, {"error": "unknown", "detail": body_bytes.decode("utf-8", "replace")}


def http_get_bytes(path: str):
    req = urllib.request.Request(BASE + path)
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.status, resp.read()


def wait_for_server() -> bool:
    for _ in range(50):
        try:
            status, _ = http_json("GET", "/api/health")
            if status == 200:
                return True
        except Exception:
            pass
        time.sleep(0.1)
    return False


def has_v360_filter() -> bool:
    if FFMPEG is None:
        return False
    try:
        result = subprocess.run([str(FFMPEG), "-filters"], capture_output=True, text=True, timeout=15)
        return "v360" in result.stdout
    except Exception:
        return False


def make_synthetic_video(path: Path) -> bool:
    if FFMPEG is None:
        return False
    cmd = [
        str(FFMPEG), "-y", "-f", "lavfi",
        "-i", "color=c=gray:s=512x256:d=3,drawbox=x='mod(50*t\\,462)':y=100:w=40:h=40:color=red@0.8:t=fill",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", str(path),
    ]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    except Exception:
        return False
    return result.returncode == 0 and path.exists()


def make_fake_ply(count: int) -> bytes:
    rng = np.random.default_rng(7)
    props = [
        "x", "y", "z", "nx", "ny", "nz",
        "f_dc_0", "f_dc_1", "f_dc_2",
        "opacity", "scale_0", "scale_1", "scale_2",
        "rot_0", "rot_1", "rot_2", "rot_3",
    ]
    header = f"ply\nformat binary_little_endian 1.0\nelement vertex {count}\n"
    for name in props:
        header += f"property float {name}\n"
    header += "end_header\n"
    data = np.zeros((count, len(props)), dtype="<f4")
    data[:, 0:3] = rng.normal(size=(count, 3)).astype("<f4")
    data[:, 6:9] = (rng.normal(size=(count, 3)) * 0.3).astype("<f4")
    data[:, 9] = (rng.normal(size=count) * 2).astype("<f4")
    data[:, 10:13] = np.log(np.abs(rng.normal(size=(count, 3))) + 0.05).astype("<f4")
    quat = rng.normal(size=(count, 4))
    quat /= np.linalg.norm(quat, axis=1, keepdims=True)
    data[:, 13:17] = quat.astype("<f4")
    return header.encode("ascii") + data.tobytes()


def poll_project_until_idle(project_id: str, timeout: float = 90.0):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        _, project = http_json("GET", f"/api/projects/{project_id}")
        last = project
        if project.get("status") != "processing":
            return project
        time.sleep(0.3)
    return last


def main() -> None:
    demo_path = REPO_ROOT / "demo" / "demo.splat"
    info = splatio.read_splat(demo_path)
    # make_demo.py builds ~16.9k gaussians (ground + trees + ribbons + sky motes).
    # The old ">50000" here never once passed; the point of the check is only that
    # the demo scene is whole rather than truncated or empty.
    check(info["count"] > 10000, f"demo.splat has a whole scene in it ({info['count']} gaussians)")
    splatio.validate_splat(demo_path)
    check(True, "demo.splat passes validate_splat (finite positions and scales)")
    demo_data = np.fromfile(demo_path, dtype=splatio.SPLAT_DTYPE)
    check(bool((demo_data["color"][:, 3] > 0).any()), "demo.splat has alpha > 0 somewhere")

    http_server = orbit_server.build_server(PORT)
    server_thread = threading.Thread(target=http_server.serve_forever, daemon=True)
    server_thread.start()

    try:
        check(wait_for_server(), "server started and responds on /api/health")

        status, health = http_json("GET", "/api/health")
        check(
            status == 200 and health.get("ok") is True and health.get("app") == "orbit-studio",
            "GET /api/health reports ok",
        )

        status, doc = http_json("GET", "/api/doctor")
        check(status == 200 and "ffmpeg" in doc and "python" in doc and "gpu" in doc, "GET /api/doctor reports tool status")

        status, project = http_json("POST", "/api/projects", {"name": "Smoke Test Scene"})
        check(status == 200 and bool(project.get("id")), "POST /api/projects creates a project")
        project_id = project["id"]

        status, listing = http_json("GET", "/api/projects")
        ids = [p["id"] for p in listing.get("projects", [])]
        check(status == 200 and project_id in ids, "GET /api/projects lists the new project")

        video_path = TEMP_HOME / "synthetic_equirect.mp4"
        video_ok = make_synthetic_video(video_path)
        check(video_ok, "synthetic equirect test video generated with ffmpeg")

        v360_ok = has_v360_filter()
        if not v360_ok:
            skip("ffmpeg build in this sandbox lacks the v360 filter")

        if video_ok:
            status, media_project = http_upload(
                f"/api/projects/{project_id}/media",
                "file", "synthetic_equirect.mp4", video_path.read_bytes(), "video/mp4",
            )
            media = media_project.get("media") or {}
            check(
                status == 200 and media.get("width") == 512 and media.get("height") == 256,
                "POST media upload accepts a 512x256 equirect video",
            )

            status, second_project = http_json("POST", "/api/projects", {"name": "Busy Probe"})
            second_id = second_project["id"]

            status, _ = http_json(
                "POST", f"/api/projects/{project_id}/run",
                {"stage": "frames", "settings": {"target_frames": 6}},
            )
            check(status == 200, "POST run frames stage accepted")

            status, busy_payload = http_json_allow_error(
                "POST", f"/api/projects/{second_id}/run", {"stage": "bundle", "settings": {}}
            )
            check(status == 409 and busy_payload.get("error") == "busy", "concurrent run on a second project returns 409 busy")
            http_json("DELETE", f"/api/projects/{second_id}")

            final = poll_project_until_idle(project_id)
            check(
                final is not None and final.get("status") != "error",
                f"frames stage finished without error (status={final and final.get('status')})",
            )
            frames_dir = TEMP_HOME / project_id / "frames"
            frame_files = list(frames_dir.glob("f_*.jpg")) if frames_dir.exists() else []
            check(len(frame_files) > 0, f"frames directory has extracted frames ({len(frame_files)})")

            if v360_ok:
                status, _ = http_json(
                    "POST", f"/api/projects/{project_id}/run",
                    {"stage": "reframe", "settings": {"crops_per_frame": 8, "size": 256}},
                )
                check(status == 200, "POST run reframe stage accepted")
                final = poll_project_until_idle(project_id)
                check(
                    final is not None and final.get("status") != "error",
                    f"reframe stage finished without error (status={final and final.get('status')})",
                )
                crops_dir = TEMP_HOME / project_id / "crops"
                crop_files = list(crops_dir.glob("*.jpg")) if crops_dir.exists() else []
                check(len(crop_files) > 0, f"crops directory has reframed images ({len(crop_files)})")

                status, crops_resp = http_json("GET", f"/api/projects/{project_id}/crops")
                check(status == 200 and len(crops_resp.get("images", [])) > 0, "GET crops endpoint lists image urls")

                status, crop_bytes = http_get_bytes(crops_resp["images"][0])
                check(status == 200 and len(crop_bytes) > 0, "GET single crop image returns bytes")

                status, _ = http_json("POST", f"/api/projects/{project_id}/run", {"stage": "bundle", "settings": {}})
                check(status == 200, "POST run bundle stage accepted")
                final = poll_project_until_idle(project_id)
                check(final is not None and final.get("status") != "error", "bundle stage finished without error")

                status, bundle_bytes = http_get_bytes(f"/api/projects/{project_id}/bundle.zip")
                check(status == 200 and len(bundle_bytes) > 0, f"GET bundle.zip returns bytes ({len(bundle_bytes)})")
            else:
                skip("reframe stage, crops listing, and bundle.zip assertions")

        status, kf_project = http_json(
            "POST", f"/api/projects/{project_id}/keyframes",
            {"keyframes": [{"pos": [0, 0, 0], "look": [0, 0, -1]}]},
        )
        check(status == 200 and len(kf_project.get("keyframes", [])) == 1, "POST keyframes saves keyframe list")
        status, kf_get = http_json("GET", f"/api/projects/{project_id}/keyframes")
        check(status == 200 and len(kf_get.get("keyframes", [])) == 1, "GET keyframes returns saved list")

        tiny_jpeg_b64 = (
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a"
            "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIy"
            "MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIA"
            "AhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAj/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEB"
            "AQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwCdABmX"
            "/9k="
        )
        status, poster_resp = http_json(
            "POST", f"/api/projects/{project_id}/poster", {"dataUrl": f"data:image/jpeg;base64,{tiny_jpeg_b64}"}
        )
        check(status == 200 and poster_resp.get("ok") is True, "POST poster saves a jpeg")
        status, poster_bytes = http_get_bytes(f"/api/projects/{project_id}/poster.jpg")
        check(status == 200 and len(poster_bytes) > 0, "GET poster.jpg returns bytes")

        fake_ply = make_fake_ply(200)
        status, result_project = http_upload(
            f"/api/projects/{project_id}/result", "file", "artifact.ply", fake_ply, "application/octet-stream",
        )
        check(
            status == 200 and result_project.get("artifact") == "artifact.splat",
            "POST result converts a .ply upload to artifact.splat",
        )
        check(result_project.get("status") == "ready", "project status becomes ready after result upload")

        artifact_path = TEMP_HOME / project_id / "artifact.splat"
        splatio.validate_splat(artifact_path)
        artifact_info = splatio.read_splat(artifact_path)
        check(artifact_info["count"] == 200, f"uploaded artifact.splat has 200 gaussians ({artifact_info['count']})")

        status, artifact_bytes = http_get_bytes(f"/api/projects/{project_id}/artifact.splat")
        check(
            status == 200 and len(artifact_bytes) == artifact_path.stat().st_size,
            "GET artifact.splat returns the full file",
        )

        status, deleted = http_json("DELETE", f"/api/projects/{project_id}")
        check(status == 200 and deleted.get("ok") is True, "DELETE project succeeds")
        status, missing = http_json_allow_error("GET", f"/api/projects/{project_id}")
        check(status == 404, "GET deleted project returns 404")

    finally:
        http_server.shutdown()
        http_server.server_close()

    print(f"\n{PASS_COUNT} passed, {FAIL_COUNT} failed")
    if FAIL_COUNT > 0:
        sys.exit(1)


if __name__ == "__main__":
    main()
