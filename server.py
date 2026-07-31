from __future__ import annotations

import argparse
import base64
import importlib
import json
import os
import queue
import re
import secrets
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.parse
import zipfile
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Optional

from pipeline import CancelledError, ProcessHolder, RunContext


class _LazyStage:
    """Defers importing a splat-pipeline submodule (and its numpy/pillow deps)
    until a splat feature actually uses it. Orbit Tour and static serving are
    pure stdlib, so this lets the whole server boot on a fresh machine that
    never ran setup.ps1 — only the splat routes need the extra libraries."""

    def __init__(self, name: str) -> None:
        self._name = name
        self._mod = None

    def __getattr__(self, attr: str):
        if self._mod is None:
            try:
                self._mod = importlib.import_module(f"pipeline.{self._name}")
            except Exception as exc:  # numpy / pillow not installed
                raise RuntimeError(
                    "This step needs the splat pipeline libraries. Install them with "
                    "'pip install numpy pillow' (or run setup.ps1). The 360 tour app "
                    "needs none of these."
                ) from exc
        return getattr(self._mod, attr)


bundle = _LazyStage("bundle")
doctor = _LazyStage("doctor")
frames = _LazyStage("frames")
reframe = _LazyStage("reframe")
splatio = _LazyStage("splatio")

VERSION = "1.0.0"
REPO_ROOT = Path(__file__).resolve().parent
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp", ".tif", ".tiff"}
STAGE_NAMES = ("frames", "reframe", "bundle", "local_train")


def get_projects_dir() -> Path:
    override = os.environ.get("ORBIT_HOME")
    if override:
        return Path(override)
    return REPO_ROOT / "projects"


def project_dir(project_id: str) -> Path:
    return get_projects_dir() / project_id


PROJECTS_IO_LOCK = threading.Lock()


def load_project(project_id: str) -> Optional[dict]:
    path = project_dir(project_id) / "project.json"
    with PROJECTS_IO_LOCK:
        if not path.exists():
            return None
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            return None


def save_project(project: dict) -> None:
    path = project_dir(project["id"]) / "project.json"
    with PROJECTS_IO_LOCK:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(project, indent=2), encoding="utf-8")


def list_projects() -> list[dict]:
    root = get_projects_dir()
    if not root.exists():
        return []
    items = []
    for entry in root.iterdir():
        candidate = entry / "project.json"
        if candidate.exists():
            try:
                items.append(json.loads(candidate.read_text(encoding="utf-8")))
            except Exception:
                continue
    items.sort(key=lambda item: item.get("created", ""), reverse=True)
    return items


def slugify(name: str) -> str:
    lowered = name.strip().lower()
    chars = [c if c.isalnum() else "-" for c in lowered]
    slug = "".join(chars)
    while "--" in slug:
        slug = slug.replace("--", "-")
    slug = slug.strip("-")
    return slug or "project"


def new_project(name: str) -> dict:
    slug = slugify(name)
    project_id = f"{slug}-{secrets.token_hex(3)}"
    project = {
        "id": project_id,
        "name": name,
        "created": datetime.now(timezone.utc).isoformat(),
        "status": "new",
        "stage": None,
        "pct": 0,
        "message": "",
        "media": None,
        "counts": {"frames": 0, "crops": 0},
        "artifact": None,
        "keyframes": [],
        "settings": {},
    }
    save_project(project)
    return project


# ---- tours (Orbit Tour: self-hosted 360 photo tours, the Kuula replacement) ----

TOUR_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
TOURS_IO_LOCK = threading.Lock()


def get_tours_dir() -> Path:
    override = os.environ.get("ORBIT_HOME")
    if override:
        return Path(override) / "tours"
    return REPO_ROOT / "tours"


def tour_dir(tour_id: str) -> Optional[Path]:
    if not TOUR_ID_RE.match(tour_id):
        return None
    return get_tours_dir() / tour_id


def load_tour(tour_id: str) -> Optional[dict]:
    tdir = tour_dir(tour_id)
    if tdir is None:
        return None
    path = tdir / "tour.json"
    with TOURS_IO_LOCK:
        if not path.exists():
            return None
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            return None


def save_tour(tour: dict) -> None:
    tdir = tour_dir(tour["id"])
    if tdir is None:
        raise ValueError(f"bad tour id {tour['id']!r}")
    path = tdir / "tour.json"
    with TOURS_IO_LOCK:
        path.parent.mkdir(parents=True, exist_ok=True)
        _write_tour(path, tour)


TOUR_SCHEMA_VERSION = 1


def _write_tour(path: Path, tour: dict) -> None:
    """Serialise a tour. Caller must already hold TOURS_IO_LOCK.

    Every write goes through here, so the version stamp goes here too rather
    than at each of the three call sites that would have to remember it. The
    field is a marker for a future reader, not a gate: nothing refuses a
    document for lacking it, because every tour written before today lacks it
    and they all still load.
    """
    tour["v"] = TOUR_SCHEMA_VERSION
    path.write_text(json.dumps(tour, indent=2), encoding="utf-8")


def save_tour_if_current(tour: dict, seen: Optional[str]) -> Optional[dict]:
    """Write a tour only if nobody else wrote it since the client last read.

    Compare-and-swap under ONE hold of the lock. The version this replaces did
    the reading in handle_tour_save and the writing in save_tour, each taking
    and releasing the lock separately, with the request's own body read sitting
    in between. Every request gets its own thread (ThreadingHTTPServer), so two
    editors posting the same `updated` both passed the comparison and the later
    write silently won — which is the exact loss the comparison was added to
    prevent. Reading the body is still done by the caller, outside the lock; it
    waits on a client and must not hold the tours directory while it does.

    Returns the written document, or None when the client is working from a
    version that is no longer on disk.
    """
    tdir = tour_dir(tour["id"])
    if tdir is None:
        raise ValueError(f"bad tour id {tour['id']!r}")
    path = tdir / "tour.json"
    with TOURS_IO_LOCK:
        try:
            current = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            current = {}
        # A payload with no "updated" at all is a first save or a non-browser
        # client and is let through unchanged, as it always was.
        if seen and current.get("updated") and seen != current["updated"]:
            return None
        tour["created"] = current.get("created", tour.get("created"))
        tour["updated"] = datetime.now(timezone.utc).isoformat()
        path.parent.mkdir(parents=True, exist_ok=True)
        _write_tour(path, tour)
    return tour


def list_tours() -> list[dict]:
    root = get_tours_dir()
    if not root.exists():
        return []
    items = []
    for entry in root.iterdir():
        candidate = entry / "tour.json"
        if candidate.exists():
            try:
                items.append(json.loads(candidate.read_text(encoding="utf-8")))
            except Exception:
                continue
    items.sort(key=lambda item: item.get("updated", ""), reverse=True)
    return items


def new_tour(name: str) -> dict:
    slug = slugify(name)
    tour_id = f"{slug}-{secrets.token_hex(3)}"
    now = datetime.now(timezone.utc).isoformat()
    tour = {
        "id": tour_id,
        "name": name,
        "created": now,
        "updated": now,
        "settings": {},
        "scenes": [],
    }
    save_tour(tour)
    return tour


# Two things must not be collected: an upload that has not been referenced by a
# save yet (confirmed data-loss race), and media belonging to a scene the editor
# can still bring back with undo. The undo stack lives in memory, so it never
# outlives the page; a day covers any single editing session with room to spare,
# and orphans still get collected on the first save after that.
PRUNE_GRACE_SECONDS = 24 * 60 * 60


def seed_sample_tour() -> None:
    """On FIRST run only, drop one ready-made demo tour into the tours folder so
    a new user has something to walk through before they own any 360 photos.
    Pure file copy — the sample panoramas ship in the repo, so this needs no
    camera and no image libraries.

    Deleting the sample is the user saying they are done with it, so a marker
    file records that and the sample never comes back. Without it, every
    restart re-created a tour the user had already thrown away."""
    src = REPO_ROOT / "tour" / "sample"
    if not (src / "tour.json").exists():
        return
    tours = get_tours_dir()
    seeded = tours / ".sample-seeded"
    dest = tours / "sample-tour"
    if seeded.exists() or dest.exists():
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(src, dest)
    seeded.write_text("the sample tour has been placed once; delete this to get it back\n", encoding="utf-8")


def prune_tour_files(tour: dict) -> None:
    """Delete uploaded files no longer referenced anywhere in the tour doc."""
    tdir = tour_dir(tour["id"])
    files_dir = tdir / "files" if tdir else None
    if files_dir is None or not files_dir.exists():
        return
    # ponytail: substring check against the JSON blob; safe because filenames
    # are server-generated hex tokens, revisit if filenames ever become user-chosen
    blob = json.dumps(tour)
    now = time.time()
    for f in files_dir.iterdir():
        if f.name in blob:
            continue
        try:
            if now - f.stat().st_mtime < PRUNE_GRACE_SECONDS:
                continue
        except OSError:
            continue
        f.unlink(missing_ok=True)


class BusyError(Exception):
    pass


class JobBus:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.subscribers: list[queue.Queue] = []

    def subscribe(self) -> queue.Queue:
        subscriber: queue.Queue = queue.Queue()
        with self.lock:
            self.subscribers.append(subscriber)
        return subscriber

    def unsubscribe(self, subscriber: queue.Queue) -> None:
        with self.lock:
            if subscriber in self.subscribers:
                self.subscribers.remove(subscriber)

    def publish(self, event: dict) -> None:
        with self.lock:
            targets = list(self.subscribers)
        for target in targets:
            target.put(event)


BUS = JobBus()


class JobRunner:
    def __init__(self, bus: JobBus) -> None:
        self.bus = bus
        self.jobs: "queue.Queue[dict]" = queue.Queue()
        self.lock = threading.Lock()
        self.busy_project_id: Optional[str] = None
        self.holder: Optional[ProcessHolder] = None
        thread = threading.Thread(target=self._worker, daemon=True)
        thread.start()

    def submit(self, project_id: str, stage: str, settings: dict) -> None:
        with self.lock:
            if self.busy_project_id is not None:
                raise BusyError(f"project {self.busy_project_id} is currently processing")
            self.busy_project_id = project_id
        project = load_project(project_id)
        if project is not None:
            project["status"] = "processing"
            project["stage"] = stage
            project["pct"] = 0
            project["message"] = f"queued {stage}"
            project["settings"] = settings
            save_project(project)
        self.jobs.put({"project_id": project_id, "stage": stage, "settings": settings})

    def cancel(self, project_id: str) -> bool:
        with self.lock:
            active = self.busy_project_id == project_id
            holder = self.holder
        if not active:
            return False
        if holder is not None:
            holder.request_cancel()
        return True

    def _worker(self) -> None:
        while True:
            job = self.jobs.get()
            self._run_job(job)

    def _run_job(self, job: dict) -> None:
        project_id = job["project_id"]
        stage = job["stage"]
        settings = job["settings"]
        holder = ProcessHolder()
        with self.lock:
            self.holder = holder

        def progress(pct: int, line: str) -> None:
            current = load_project(project_id)
            if current is None:
                return
            current["status"] = "processing"
            current["stage"] = stage
            current["pct"] = pct
            current["message"] = line
            save_project(current)
            self.bus.publish({"id": project_id, "stage": stage, "pct": pct, "line": line, "state": "running"})

        ctx = RunContext(progress=progress, holder=holder)
        try:
            project = load_project(project_id)
            if project is None:
                raise RuntimeError("project not found")
            result = execute_stage(project_dir(project_id), project, stage, settings, ctx)
            project = load_project(project_id) or project
            if isinstance(result, dict):
                if "frames" in result and isinstance(result["frames"], dict):
                    project["counts"]["frames"] = result["frames"].get("kept", project["counts"]["frames"])
                if "crops" in result:
                    project["counts"]["crops"] = result["crops"]
            project["status"] = "ready" if project.get("artifact") else "new"
            project["stage"] = None
            project["pct"] = 100
            project["message"] = f"{stage} complete"
            save_project(project)
            self.bus.publish({"id": project_id, "stage": stage, "pct": 100, "line": project["message"], "state": "done"})
        except CancelledError:
            project = load_project(project_id)
            if project is not None:
                project["status"] = "error"
                project["stage"] = None
                project["message"] = "cancelled"
                save_project(project)
            self.bus.publish({"id": project_id, "stage": stage, "pct": 0, "line": "cancelled", "state": "error"})
        except Exception as exc:
            project = load_project(project_id)
            if project is not None:
                project["status"] = "error"
                project["stage"] = None
                project["message"] = str(exc)
                save_project(project)
            self.bus.publish({"id": project_id, "stage": stage, "pct": 0, "line": str(exc), "state": "error"})
        finally:
            with self.lock:
                self.busy_project_id = None
                self.holder = None


RUNNER = JobRunner(BUS)


def run_local_train(pdir: Path, settings: dict, ctx: RunContext) -> dict:
    brush_path = doctor.find_brush(REPO_ROOT)
    if brush_path is None:
        raise RuntimeError("brush not found locally; the Colab notebook path is recommended for training")
    dataset_dir = pdir / "colmap"
    if not dataset_dir.exists():
        raise RuntimeError("no COLMAP-style dataset found in project; the Colab notebook path is recommended for training")
    ctx.report(1, "[experimental] starting local brush training")
    export_path = pdir / "artifact.ply"
    cmd = [str(brush_path), str(dataset_dir), "--export-path", str(export_path)]

    def on_line(line: str) -> None:
        ctx.report(50, f"[experimental] {line.strip()}")

    result = ctx.run(cmd, on_line=on_line)
    if result.returncode != 0:
        raise RuntimeError(f"[experimental] brush exited with code {result.returncode}")
    ctx.report(100, "[experimental] local training complete")
    return {}


def execute_stage(pdir: Path, project: dict, stage: str, settings: dict, ctx: RunContext) -> dict:
    if stage == "frames":
        media = project.get("media")
        if not media:
            raise RuntimeError("project has no media uploaded")
        source = pdir / "source" / media["filename"]
        if media.get("type") == "imageset":
            sources = [pdir / "source" / name for name in media.get("filenames", [])]
            result = frames.run_multi_image(pdir, sources, ctx)
        elif media.get("type") == "image":
            result = frames.run_single_image(pdir, source, ctx)
        else:
            ffmpeg_path = doctor.find_ffmpeg(REPO_ROOT)
            if ffmpeg_path is None:
                raise RuntimeError("ffmpeg not found")
            result = frames.run(pdir, source, float(media.get("duration") or 1.0), ffmpeg_path, settings, ctx)
        return {"frames": result}
    if stage == "reframe":
        ffmpeg_path = doctor.find_ffmpeg(REPO_ROOT)
        if ffmpeg_path is None:
            raise RuntimeError("ffmpeg not found")
        media = project.get("media")
        source = pdir / "source" / media["filename"] if media else None
        duration = float(media.get("duration") or 1.0) if media else 1.0
        target_frames = int(settings.get("target_frames", frames.DEFAULT_TARGET_FRAMES))
        fps = frames.build_fps(duration, target_frames)
        result = reframe.run(pdir, ffmpeg_path, settings, source, fps, ctx)
        return {"crops": result["crops"]}
    if stage == "bundle":
        bundle.build_bundle(pdir, project["name"], settings)
        ctx.report(100, "bundle written")
        return {}
    if stage == "local_train":
        return run_local_train(pdir, settings, ctx)
    raise RuntimeError(f"unknown stage {stage}")


def parse_content_type(header: str) -> tuple[str, dict[str, str]]:
    parts = header.split(";")
    main = parts[0].strip()
    params: dict[str, str] = {}
    for part in parts[1:]:
        if "=" in part:
            key, _, value = part.strip().partition("=")
            params[key.strip().lower()] = value.strip().strip('"')
    return main, params


def iter_multipart(body: bytes, boundary: str):
    delimiter = ("--" + boundary).encode("utf-8")
    for segment in body.split(delimiter)[1:-1]:
        if segment.startswith(b"\r\n"):
            segment = segment[2:]
        if segment.endswith(b"\r\n"):
            segment = segment[:-2]
        header_blob, sep, content = segment.partition(b"\r\n\r\n")
        if not sep:
            continue
        headers_text = header_blob.decode("utf-8", errors="replace")
        disposition = ""
        for line in headers_text.split("\r\n"):
            if line.lower().startswith("content-disposition"):
                disposition = line
        if not disposition:
            continue
        _, params = parse_content_type(disposition.split(":", 1)[1])
        yield params.get("name", ""), params.get("filename"), content


def parse_multipart(body: bytes, boundary: str) -> dict[str, dict]:
    fields: dict[str, dict] = {}
    for name, filename, content in iter_multipart(body, boundary):
        fields[name] = {"filename": filename, "content": content}
    return fields


def collect_file_parts(body: bytes, boundary: str) -> list[dict]:
    return [
        {"filename": Path(filename).name, "content": content}
        for _, filename, content in iter_multipart(body, boundary)
        if filename
    ]


def probe_media(ffprobe_path: Path, path: Path) -> Optional[dict]:
    cmd = [str(ffprobe_path), "-v", "error", "-print_format", "json", "-show_format", "-show_streams", str(path)]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    except Exception:
        return None
    if result.returncode != 0:
        return None
    try:
        data = json.loads(result.stdout or "{}")
    except Exception:
        return None
    streams = data.get("streams", [])
    video_stream = next((s for s in streams if s.get("codec_type") == "video"), None)
    width = int(video_stream["width"]) if video_stream and "width" in video_stream else 0
    height = int(video_stream["height"]) if video_stream and "height" in video_stream else 0
    duration_raw = data.get("format", {}).get("duration")
    if duration_raw is None and video_stream is not None:
        duration_raw = video_stream.get("duration")
    duration = float(duration_raw) if duration_raw not in (None, "N/A") else 0.0
    media_type = "image" if path.suffix.lower() in IMAGE_EXTENSIONS else "video"
    return {"filename": path.name, "type": media_type, "duration": duration, "width": width, "height": height}


def cors_headers(handler: "Handler") -> None:
    # Echo only local origins instead of "*": the API has destructive routes
    # (DELETE), so arbitrary websites must not pass a CORS preflight against it.
    # Non-browser clients (phone app, curl, Colab scripts) send no Origin header
    # and are unaffected — CORS is a browser-only gate.
    if not handler.path.startswith("/api"):
        return
    origin = handler.headers.get("Origin", "")
    host = urllib.parse.urlsplit(origin).hostname or ""
    if host in ("127.0.0.1", "localhost", "::1"):
        handler.send_header("Access-Control-Allow-Origin", origin)
        handler.send_header("Vary", "Origin")


def send_json(handler: "Handler", status: int, payload) -> None:
    body = json.dumps(payload).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Content-Length", str(len(body)))
    cors_headers(handler)
    handler.end_headers()
    handler.wfile.write(body)


def send_error(handler: "Handler", status: int, error: str, detail: str = "") -> None:
    send_json(handler, status, {"error": error, "detail": detail})


def send_bytes(handler: "Handler", status: int, data: bytes, content_type: str) -> None:
    handler.send_response(status)
    handler.send_header("Content-Type", content_type)
    handler.send_header("Content-Length", str(len(data)))
    cors_headers(handler)
    handler.end_headers()
    handler.wfile.write(data)


def send_file(handler: "Handler", path: Path, content_type: str) -> None:
    if not path.exists() or not path.is_file():
        send_error(handler, 404, "not_found", f"{path.name} not found")
        return
    # streamed, not read whole: panoramas and splat artifacts run to tens of MB
    handler.send_response(200)
    handler.send_header("Content-Type", content_type)
    handler.send_header("Content-Length", str(path.stat().st_size))
    cors_headers(handler)
    handler.end_headers()
    with path.open("rb") as source:
        shutil.copyfileobj(source, handler.wfile, 64 * 1024)


def read_body(handler: "Handler") -> bytes:
    length = int(handler.headers.get("Content-Length", 0) or 0)
    if length <= 0:
        return b""
    return handler.rfile.read(length)


def read_json_body(handler: "Handler") -> Optional[dict]:
    raw = read_body(handler)
    if not raw:
        return {}
    try:
        return json.loads(raw.decode("utf-8"))
    except Exception:
        return None


def handle_studio_index(handler: "Handler") -> None:
    path = REPO_ROOT / "studio" / "index.html"
    send_file(handler, path, "text/html")


def handle_demo_splat(handler: "Handler") -> None:
    send_file(handler, REPO_ROOT / "demo" / "demo.splat", "application/octet-stream")


REPO_FILE_TYPES = {
    ".ipynb": "application/x-ipynb+json",
    ".md": "text/markdown; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
}


def handle_repo_file(handler: "Handler", folder: str, name: str) -> None:
    safe_name = Path(name).name
    if safe_name != name or safe_name.startswith("."):
        send_error(handler, 404, "not_found", "file not found")
        return
    path = REPO_ROOT / folder / safe_name
    content_type = REPO_FILE_TYPES.get(path.suffix.lower(), "application/octet-stream")
    send_file(handler, path, content_type)


def handle_health(handler: "Handler") -> None:
    send_json(handler, 200, {"ok": True, "app": "orbit-studio", "version": VERSION})


def handle_doctor(handler: "Handler") -> None:
    send_json(handler, 200, doctor.run_doctor(REPO_ROOT, get_projects_dir()))


def handle_projects_list(handler: "Handler") -> None:
    send_json(handler, 200, {"projects": list_projects()})


def handle_projects_create(handler: "Handler") -> None:
    payload = read_json_body(handler)
    if payload is None:
        send_error(handler, 400, "bad_request", "invalid json body")
        return
    name = str(payload.get("name") or "untitled").strip() or "untitled"
    send_json(handler, 200, new_project(name))


def handle_project_get(handler: "Handler", project_id: str) -> None:
    project = load_project(project_id)
    if project is None:
        send_error(handler, 404, "not_found", "project not found")
        return
    send_json(handler, 200, project)


def handle_project_delete(handler: "Handler", project_id: str) -> None:
    project = load_project(project_id)
    if project is None:
        send_error(handler, 404, "not_found", "project not found")
        return
    shutil.rmtree(project_dir(project_id), ignore_errors=True)
    send_json(handler, 200, {"ok": True})


def handle_media_upload(handler: "Handler", project_id: str) -> None:
    project = load_project(project_id)
    if project is None:
        send_error(handler, 404, "not_found", "project not found")
        return
    content_type = handler.headers.get("Content-Type", "")
    if "multipart/form-data" not in content_type:
        send_error(handler, 400, "bad_request", "expected multipart/form-data")
        return
    _, params = parse_content_type(content_type)
    boundary = params.get("boundary", "")
    body = read_body(handler)
    fields = parse_multipart(body, boundary)
    file_field = fields.get("file")
    if file_field is None or not file_field.get("filename"):
        send_error(handler, 400, "bad_request", "missing file field")
        return
    filename = Path(file_field["filename"]).name
    source_dir = project_dir(project_id) / "source"
    source_dir.mkdir(parents=True, exist_ok=True)
    dest = source_dir / filename
    dest.write_bytes(file_field["content"])
    query = urllib.parse.urlsplit(handler.path).query
    force = urllib.parse.parse_qs(query).get("force", ["0"])[0] == "1"
    ffprobe_path = doctor.find_ffprobe(REPO_ROOT)
    info = probe_media(ffprobe_path, dest) if ffprobe_path else None
    if info is None:
        send_error(handler, 500, "ffprobe_failed", "could not read media metadata")
        return
    width = info["width"]
    height = info["height"]
    if width and height and width != 2 * height and not force:
        send_error(handler, 400, "not_equirectangular", f"expected width == 2*height, got {width}x{height}")
        return
    project["media"] = info
    save_project(project)
    send_json(handler, 200, project)


def handle_photoset_upload(handler: "Handler", project_id: str) -> None:
    """Receive N equirectangular still photos (the 360-photo capture lane).
    Each is validated as a 2:1 image, saved in order, and recorded as one
    'imageset' media entry the frames stage explodes into per-photo frames."""
    project = load_project(project_id)
    if project is None:
        send_error(handler, 404, "not_found", "project not found")
        return
    content_type = handler.headers.get("Content-Type", "")
    if "multipart/form-data" not in content_type:
        send_error(handler, 400, "bad_request", "expected multipart/form-data")
        return
    _, params = parse_content_type(content_type)
    boundary = params.get("boundary", "")
    files = collect_file_parts(read_body(handler), boundary)
    if not files:
        send_error(handler, 400, "bad_request", "no photos in upload")
        return
    query = urllib.parse.urlsplit(handler.path).query
    force = urllib.parse.parse_qs(query).get("force", ["0"])[0] == "1"
    ffprobe_path = doctor.find_ffprobe(REPO_ROOT)
    if ffprobe_path is None:
        send_error(handler, 500, "ffprobe_failed", "ffprobe not found")
        return
    source_dir = project_dir(project_id) / "source"
    source_dir.mkdir(parents=True, exist_ok=True)
    saved: list[str] = []
    first_info = None
    for index, part in enumerate(files, start=1):
        stored = f"{index:03d}_{part['filename']}"
        dest = source_dir / stored
        dest.write_bytes(part["content"])
        info = probe_media(ffprobe_path, dest)
        if info is None:
            send_error(handler, 500, "ffprobe_failed", f"could not read {part['filename']}")
            return
        if info.get("type") != "image":
            send_error(handler, 400, "not_an_image", f"{part['filename']} is not a photo")
            return
        width, height = info["width"], info["height"]
        if width and height and width != 2 * height and not force:
            send_error(handler, 400, "not_equirectangular",
                       f"{part['filename']} is {width}x{height}, expected width == 2*height")
            return
        saved.append(stored)
        if first_info is None:
            first_info = info
    project["media"] = {
        "type": "imageset",
        "filename": saved[0],
        "filenames": saved,
        "count": len(saved),
        "width": first_info["width"],
        "height": first_info["height"],
        "duration": 0,
    }
    save_project(project)
    send_json(handler, 200, project)


def handle_ingest_bundle(handler: "Handler") -> None:
    """Receive a ready-made bundle.zip from the phone app (Orbit Capture 'Send
    over Wi-Fi'). Raw application/zip body, project name in ?name=. Creates a
    fresh project holding the zip, with crops extracted for studio preview."""
    query = urllib.parse.urlsplit(handler.path).query
    name = (urllib.parse.parse_qs(query).get("name", ["phone-scan"])[0] or "phone-scan").strip() or "phone-scan"
    length = int(handler.headers.get("Content-Length", 0) or 0)
    if length <= 0:
        send_error(handler, 411, "length_required", "Content-Length required")
        return
    root = get_projects_dir()
    root.mkdir(parents=True, exist_ok=True)
    tmp = root / f".incoming-{secrets.token_hex(4)}.zip"
    try:
        remaining = length
        with tmp.open("wb") as out:
            while remaining > 0:
                chunk = handler.rfile.read(min(65536, remaining))
                if not chunk:
                    break
                out.write(chunk)
                remaining -= len(chunk)
        if remaining > 0:
            send_error(handler, 400, "truncated", "upload ended before Content-Length bytes")
            return
        try:
            with zipfile.ZipFile(tmp) as zf:
                if "manifest.json" not in zf.namelist():
                    send_error(handler, 400, "invalid_bundle", "zip has no manifest.json")
                    return
                crop_infos = [
                    info for info in zf.infolist()
                    if info.filename.startswith("crops/")
                    and info.filename.lower().endswith((".jpg", ".jpeg"))
                    and "/" not in info.filename[len("crops/"):]
                ]
                project = new_project(name)
                pdir = project_dir(project["id"])
                crops_dir = pdir / "crops"
                crops_dir.mkdir(parents=True, exist_ok=True)
                for info in crop_infos:
                    target = crops_dir / Path(info.filename).name
                    with zf.open(info) as src, target.open("wb") as dst:
                        shutil.copyfileobj(src, dst)
        except zipfile.BadZipFile:
            send_error(handler, 400, "invalid_zip", "not a valid zip file")
            return
        shutil.move(str(tmp), str(pdir / "bundle.zip"))
        project = load_project(project["id"]) or project
        project["counts"]["crops"] = len(crop_infos)
        project["message"] = f"bundle received from phone ({len(crop_infos)} crops)"
        save_project(project)
        send_json(handler, 200, project)
    finally:
        if tmp.exists():
            tmp.unlink()


def handle_run(handler: "Handler", project_id: str) -> None:
    project = load_project(project_id)
    if project is None:
        send_error(handler, 404, "not_found", "project not found")
        return
    payload = read_json_body(handler)
    if payload is None:
        send_error(handler, 400, "bad_request", "invalid json body")
        return
    stage = payload.get("stage")
    if stage not in STAGE_NAMES:
        send_error(handler, 400, "bad_request", f"unknown stage {stage}")
        return
    settings = payload.get("settings") or {}
    try:
        RUNNER.submit(project_id, stage, settings)
    except BusyError as exc:
        send_error(handler, 409, "busy", str(exc))
        return
    send_json(handler, 200, load_project(project_id))


def handle_cancel(handler: "Handler", project_id: str) -> None:
    project = load_project(project_id)
    if project is None:
        send_error(handler, 404, "not_found", "project not found")
        return
    ok = RUNNER.cancel(project_id)
    send_json(handler, 200, {"ok": ok, "project": load_project(project_id)})


def handle_events(handler: "Handler") -> None:
    handler.send_response(200)
    handler.send_header("Content-Type", "text/event-stream")
    handler.send_header("Cache-Control", "no-cache")
    handler.send_header("Connection", "keep-alive")
    cors_headers(handler)
    handler.end_headers()
    subscriber = BUS.subscribe()
    try:
        while True:
            try:
                event = subscriber.get(timeout=10)
            except queue.Empty:
                handler.wfile.write(b": heartbeat\n\n")
                handler.wfile.flush()
                continue
            handler.wfile.write(f"data: {json.dumps(event)}\n\n".encode("utf-8"))
            handler.wfile.flush()
    except (BrokenPipeError, ConnectionResetError, OSError):
        pass
    finally:
        BUS.unsubscribe(subscriber)


def handle_bundle_get(handler: "Handler", project_id: str) -> None:
    send_file(handler, project_dir(project_id) / "bundle.zip", "application/zip")


def handle_result_upload(handler: "Handler", project_id: str) -> None:
    project = load_project(project_id)
    if project is None:
        send_error(handler, 404, "not_found", "project not found")
        return
    content_type = handler.headers.get("Content-Type", "")
    if "multipart/form-data" not in content_type:
        send_error(handler, 400, "bad_request", "expected multipart/form-data")
        return
    _, params = parse_content_type(content_type)
    boundary = params.get("boundary", "")
    body = read_body(handler)
    fields = parse_multipart(body, boundary)
    file_field = fields.get("file")
    if file_field is None or not file_field.get("filename"):
        send_error(handler, 400, "bad_request", "missing file field")
        return
    filename = Path(file_field["filename"]).name
    ext = Path(filename).suffix.lower()
    pdir = project_dir(project_id)
    pdir.mkdir(parents=True, exist_ok=True)
    if ext == ".ply":
        raw_path = pdir / "artifact.ply"
        raw_path.write_bytes(file_field["content"])
        try:
            splatio.convert_ply_to_splat(raw_path, pdir / "artifact.splat")
        except Exception as exc:
            send_error(handler, 400, "invalid_ply", str(exc))
            return
        project["artifact"] = "artifact.splat"
    elif ext == ".splat":
        dest = pdir / "artifact.splat"
        dest.write_bytes(file_field["content"])
        try:
            splatio.validate_splat(dest)
        except Exception as exc:
            send_error(handler, 400, "invalid_splat", str(exc))
            return
        project["artifact"] = "artifact.splat"
    elif ext == ".spz":
        dest = pdir / "artifact.spz"
        dest.write_bytes(file_field["content"])
        project["artifact"] = "artifact.spz"
    else:
        send_error(handler, 400, "unsupported_type", f"unsupported extension {ext}")
        return
    project["status"] = "ready"
    save_project(project)
    send_json(handler, 200, project)


def handle_artifact_get(handler: "Handler", project_id: str, kind: str) -> None:
    content_types = {
        "splat": "application/octet-stream",
        "spz": "application/octet-stream",
        "ply": "application/octet-stream",
    }
    send_file(handler, project_dir(project_id) / f"artifact.{kind}", content_types[kind])


def handle_poster_upload(handler: "Handler", project_id: str) -> None:
    project = load_project(project_id)
    if project is None:
        send_error(handler, 404, "not_found", "project not found")
        return
    payload = read_json_body(handler)
    if payload is None:
        send_error(handler, 400, "bad_request", "invalid json body")
        return
    data_url = payload.get("dataUrl", "")
    if "," not in data_url:
        send_error(handler, 400, "bad_request", "malformed dataUrl")
        return
    _, _, encoded = data_url.partition(",")
    try:
        image_bytes = base64.b64decode(encoded)
    except Exception:
        send_error(handler, 400, "bad_request", "invalid base64 data")
        return
    pdir = project_dir(project_id)
    pdir.mkdir(parents=True, exist_ok=True)
    (pdir / "poster.jpg").write_bytes(image_bytes)
    send_json(handler, 200, {"ok": True})


def handle_poster_get(handler: "Handler", project_id: str) -> None:
    send_file(handler, project_dir(project_id) / "poster.jpg", "image/jpeg")


def handle_keyframes_get(handler: "Handler", project_id: str) -> None:
    project = load_project(project_id)
    if project is None:
        send_error(handler, 404, "not_found", "project not found")
        return
    send_json(handler, 200, {"keyframes": project.get("keyframes", [])})


def handle_keyframes_post(handler: "Handler", project_id: str) -> None:
    project = load_project(project_id)
    if project is None:
        send_error(handler, 404, "not_found", "project not found")
        return
    payload = read_json_body(handler)
    if payload is None:
        send_error(handler, 400, "bad_request", "invalid json body")
        return
    keyframes = payload.get("keyframes", [])
    project["keyframes"] = keyframes
    save_project(project)
    send_json(handler, 200, {"keyframes": keyframes})


def handle_crops_list(handler: "Handler", project_id: str) -> None:
    project = load_project(project_id)
    if project is None:
        send_error(handler, 404, "not_found", "project not found")
        return
    crops_dir = project_dir(project_id) / "crops"
    files = sorted(crops_dir.glob("*.jpg"))[:12] if crops_dir.exists() else []
    images = [f"/api/projects/{project_id}/crops/{f.name}" for f in files]
    send_json(handler, 200, {"images": images})


def handle_crop_file(handler: "Handler", project_id: str, name: str) -> None:
    safe_name = Path(name).name
    send_file(handler, project_dir(project_id) / "crops" / safe_name, "image/jpeg")


# ponytail: covers Kuula's 16384x8192 JPG ceiling with headroom; raise if real captures hit it
MAX_TOUR_UPLOAD_BYTES = 64 * 1024 * 1024

TOUR_FILE_TYPES = {
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".webp": "image/webp",
    ".mp3": "audio/mpeg",
    ".m4a": "audio/mp4",
    ".ogg": "audio/ogg",
}


def handle_tour_app(handler: "Handler") -> None:
    send_file(handler, REPO_ROOT / "tour" / "index.html", "text/html; charset=utf-8")


def handle_tours_list(handler: "Handler") -> None:
    send_json(handler, 200, {"tours": list_tours()})


def handle_tours_create(handler: "Handler") -> None:
    payload = read_json_body(handler)
    if payload is None:
        send_error(handler, 400, "bad_request", "invalid json body")
        return
    name = str(payload.get("name") or "untitled tour").strip() or "untitled tour"
    send_json(handler, 200, new_tour(name))


def handle_tour_get(handler: "Handler", tour_id: str) -> None:
    tour = load_tour(tour_id)
    if tour is None:
        send_error(handler, 404, "not_found", "tour not found")
        return
    send_json(handler, 200, tour)


def handle_tour_save(handler: "Handler", tour_id: str) -> None:
    existing = load_tour(tour_id)
    if existing is None:
        send_error(handler, 404, "not_found", "tour not found")
        return
    payload = read_json_body(handler)
    if payload is None or not isinstance(payload.get("scenes"), list):
        send_error(handler, 400, "bad_request", "expected a tour doc with a scenes list")
        return
    # Two editors on one tour used to overwrite each other in silence, because a
    # save posts the WHOLE document. If the client is working from a version
    # that is no longer the one on disk, refuse rather than clobber; the client
    # keeps its edits and decides. The comparison and the write happen under one
    # hold of the lock inside save_tour_if_current — see the note there for why
    # doing them separately did not actually stop the clobbering.
    payload["id"] = tour_id
    written = save_tour_if_current(payload, payload.get("updated"))
    if written is None:
        send_error(
            handler,
            409,
            "stale",
            "this tour was changed somewhere else since you loaded it",
        )
        return
    prune_tour_files(written)
    send_json(handler, 200, written)


def handle_tour_delete(handler: "Handler", tour_id: str) -> None:
    tdir = tour_dir(tour_id)
    if tdir is None or not (tdir / "tour.json").exists():
        send_error(handler, 404, "not_found", "tour not found")
        return
    shutil.rmtree(tdir, ignore_errors=True)
    send_json(handler, 200, {"ok": True})


def handle_tour_file_upload(handler: "Handler", tour_id: str) -> None:
    tour = load_tour(tour_id)
    if tour is None:
        send_error(handler, 404, "not_found", "tour not found")
        return
    content_type = handler.headers.get("Content-Type", "")
    if "multipart/form-data" not in content_type:
        send_error(handler, 400, "bad_request", "expected multipart/form-data")
        return
    length = int(handler.headers.get("Content-Length", 0) or 0)
    if length > MAX_TOUR_UPLOAD_BYTES:
        send_error(handler, 413, "too_large", f"upload exceeds {MAX_TOUR_UPLOAD_BYTES // (1024 * 1024)}MB limit")
        handler.close_connection = True  # body was never read; keep-alive would misparse it
        return
    _, params = parse_content_type(content_type)
    fields = parse_multipart(read_body(handler), params.get("boundary", ""))
    file_field = fields.get("file")
    if file_field is None or not file_field.get("filename"):
        send_error(handler, 400, "bad_request", "missing file field")
        return
    ext = Path(file_field["filename"]).suffix.lower()
    if ext not in TOUR_FILE_TYPES:
        send_error(handler, 400, "bad_type", f"expected one of {sorted(TOUR_FILE_TYPES)}")
        return
    name = f"{secrets.token_hex(8)}{ext}"
    files_dir = tour_dir(tour_id) / "files"
    files_dir.mkdir(parents=True, exist_ok=True)
    (files_dir / name).write_bytes(file_field["content"])
    send_json(handler, 200, {"file": name, "url": f"/api/tours/{tour_id}/files/{name}"})


def handle_tour_duplicate(handler: "Handler", tour_id: str) -> None:
    tour = load_tour(tour_id)
    if tour is None:
        send_error(handler, 404, "not_found", "tour not found")
        return
    fresh = new_tour(f"{tour['name']} copy")
    src_files = tour_dir(tour_id) / "files"
    if src_files.exists():
        shutil.copytree(src_files, tour_dir(fresh["id"]) / "files")
    dup = dict(tour)
    dup.update(id=fresh["id"], name=fresh["name"], created=fresh["created"], updated=fresh["updated"])
    save_tour(dup)
    send_json(handler, 200, dup)


EXPORT_README = """This folder is a self-contained Orbit Tour website.

Host it on any static file host (GitHub Pages, Netlify, S3/R2, nginx...)
and open index.html from there. Opening index.html straight from disk will
NOT work: browsers block ES modules on file:// pages. For a quick local
look, run any static server in this folder, e.g.:

    python -m http.server 8000

then visit http://localhost:8000
"""


def export_manifest(tour: dict) -> dict:
    """What this zip is, so a folder found in three years still explains itself.

    An inspection deliverable that cannot say which structure it is, when it was
    walked and who walked it is an archive of anonymous photographs. Everything
    here is derived from the tour rather than asked for again, so it cannot
    disagree with the record it ships beside.
    """
    # Defensive about shape on purpose: handle_tour_save only validates that
    # "scenes" is a list, so a hand-edited or third-party doc can put anything
    # inside it. Before this, one non-dict scene raised AttributeError and took
    # the WHOLE export down with a 500 — a regression against the pre-change
    # export, which never looked inside the document at all.
    scenes = [s for s in (tour.get("scenes") or []) if isinstance(s, dict)]
    defects = [
        h
        for s in scenes
        for h in (s.get("hotspots") or [])
        if isinstance(h, dict) and h.get("type") == "defect"
    ]
    inspection = tour.get("inspection")
    if not isinstance(inspection, dict):
        inspection = {}
    return {
        "app": "orbit-tour",
        "tour": {"id": tour.get("id"), "name": tour.get("name")},
        "inspection": {
            "structureId": inspection.get("structureId"),
            "date": inspection.get("date"),
            "inspectedBy": inspection.get("by"),
            "sheet": inspection.get("sheet"),
        },
        "counts": {
            "scenes": len(scenes),
            "defects": len(defects),
            # which of the eleven NBIS stops carry at least one photo
            "photoStopsCovered": len(
                {s["stop"] for s in scenes if isinstance(s.get("stop"), str)}
            ),
        },
        "defectCodes": sorted(
            (h["code"] for h in defects if isinstance(h.get("code"), str)),
            key=lambda c: int(c[1:]) if c[1:].isdigit() else 0,
        ),
        "exportedAt": datetime.now(timezone.utc).isoformat(),
        "viewer": {"photoSphereViewer": "5.14.3", "three": "0.184.0"},
        "note": (
            "360 capture supplements the inspection record; it does not replace "
            "hands-on NBIS judgement or access requirements."
        ),
    }


def handle_tour_export(handler: "Handler", tour_id: str) -> None:
    """Zip a tour into a static site: viewer html + vendored libs + media."""
    tour = load_tour(tour_id)
    if tour is None:
        send_error(handler, 404, "not_found", "tour not found")
        return
    html = (REPO_ROOT / "tour" / "index.html").read_text(encoding="utf-8")
    # import-map addresses must be absolute or start with / ./ ../ — bare
    # "vendor/x.js" is rejected by the spec, so rewrite to "./vendor/x.js"
    html = html.replace("/tour/vendor/", "./vendor/")
    html = html.replace(
        "<body>",
        f"<body>\n<script>window.ORBIT_STATIC_TOUR = {json.dumps(tour)};</script>",
        1,
    )
    # built on disk, not in memory: a tour of 40 panoramas is gigabytes
    fd, tmp_name = tempfile.mkstemp(suffix=".zip", prefix="orbit-export-")
    os.close(fd)
    tmp_path = Path(tmp_name)
    try:
        with zipfile.ZipFile(tmp_path, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("index.html", html)
            z.writestr("README.txt", EXPORT_README)
            z.writestr("manifest.json", json.dumps(export_manifest(tour), indent=2))
            for f in sorted((REPO_ROOT / "tour" / "vendor").iterdir()):
                if f.suffix in (".js", ".css"):
                    z.write(f, f"vendor/{f.name}")
            files_dir = tour_dir(tour_id) / "files"
            if files_dir.exists():
                for f in sorted(files_dir.iterdir()):
                    z.write(f, f"files/{f.name}")
        handler.send_response(200)
        handler.send_header("Content-Type", "application/zip")
        handler.send_header("Content-Disposition", f'attachment; filename="{tour_id}.zip"')
        handler.send_header("Content-Length", str(tmp_path.stat().st_size))
        cors_headers(handler)
        handler.end_headers()
        with tmp_path.open("rb") as source:
            shutil.copyfileobj(source, handler.wfile, 64 * 1024)
    finally:
        tmp_path.unlink(missing_ok=True)


def handle_tour_file_get(handler: "Handler", tour_id: str, name: str) -> None:
    tdir = tour_dir(tour_id)
    safe_name = Path(name).name
    if tdir is None or safe_name != name:
        send_error(handler, 404, "not_found", "file not found")
        return
    content_type = TOUR_FILE_TYPES.get(Path(safe_name).suffix.lower(), "application/octet-stream")
    send_file(handler, tdir / "files" / safe_name, content_type)


GET_ROUTES: list[tuple[re.Pattern, object]] = [
    (re.compile(r"^/$"), lambda h, m: handle_studio_index(h)),
    (re.compile(r"^/studio$"), lambda h, m: handle_studio_index(h)),
    (re.compile(r"^/demo/demo\.splat$"), lambda h, m: handle_demo_splat(h)),
    (re.compile(r"^/notebooks/([^/]+)$"), lambda h, m: handle_repo_file(h, "notebooks", m.group(1))),
    (re.compile(r"^/docs/([^/]+)$"), lambda h, m: handle_repo_file(h, "docs", m.group(1))),
    (re.compile(r"^/api/health$"), lambda h, m: handle_health(h)),
    (re.compile(r"^/api/doctor$"), lambda h, m: handle_doctor(h)),
    (re.compile(r"^/api/events$"), lambda h, m: handle_events(h)),
    (re.compile(r"^/api/projects$"), lambda h, m: handle_projects_list(h)),
    (re.compile(r"^/api/projects/([^/]+)/bundle\.zip$"), lambda h, m: handle_bundle_get(h, m.group(1))),
    (re.compile(r"^/api/projects/([^/]+)/artifact\.splat$"), lambda h, m: handle_artifact_get(h, m.group(1), "splat")),
    (re.compile(r"^/api/projects/([^/]+)/artifact\.spz$"), lambda h, m: handle_artifact_get(h, m.group(1), "spz")),
    (re.compile(r"^/api/projects/([^/]+)/artifact\.ply$"), lambda h, m: handle_artifact_get(h, m.group(1), "ply")),
    (re.compile(r"^/api/projects/([^/]+)/poster\.jpg$"), lambda h, m: handle_poster_get(h, m.group(1))),
    (re.compile(r"^/api/projects/([^/]+)/keyframes$"), lambda h, m: handle_keyframes_get(h, m.group(1))),
    (re.compile(r"^/api/projects/([^/]+)/crops$"), lambda h, m: handle_crops_list(h, m.group(1))),
    (re.compile(r"^/api/projects/([^/]+)/crops/([^/]+)$"), lambda h, m: handle_crop_file(h, m.group(1), m.group(2))),
    (re.compile(r"^/api/projects/([^/]+)$"), lambda h, m: handle_project_get(h, m.group(1))),
    (re.compile(r"^/tour$"), lambda h, m: handle_tour_app(h)),
    (re.compile(r"^/tour/view/([^/]+)$"), lambda h, m: handle_tour_app(h)),
    (re.compile(r"^/tour/vendor/([^/]+)$"), lambda h, m: handle_repo_file(h, "tour/vendor", m.group(1))),
    (re.compile(r"^/api/tours$"), lambda h, m: handle_tours_list(h)),
    (re.compile(r"^/api/tours/([^/]+)/files/([^/]+)$"), lambda h, m: handle_tour_file_get(h, m.group(1), m.group(2))),
    (re.compile(r"^/api/tours/([^/]+)/export\.zip$"), lambda h, m: handle_tour_export(h, m.group(1))),
    (re.compile(r"^/api/tours/([^/]+)$"), lambda h, m: handle_tour_get(h, m.group(1))),
]

POST_ROUTES: list[tuple[re.Pattern, object]] = [
    (re.compile(r"^/api/projects$"), lambda h, m: handle_projects_create(h)),
    (re.compile(r"^/api/tours$"), lambda h, m: handle_tours_create(h)),
    (re.compile(r"^/api/tours/([^/]+)/files$"), lambda h, m: handle_tour_file_upload(h, m.group(1))),
    (re.compile(r"^/api/tours/([^/]+)/duplicate$"), lambda h, m: handle_tour_duplicate(h, m.group(1))),
    (re.compile(r"^/api/tours/([^/]+)$"), lambda h, m: handle_tour_save(h, m.group(1))),
    (re.compile(r"^/api/ingest/bundle$"), lambda h, m: handle_ingest_bundle(h)),
    (re.compile(r"^/api/projects/([^/]+)/media$"), lambda h, m: handle_media_upload(h, m.group(1))),
    (re.compile(r"^/api/projects/([^/]+)/photoset$"), lambda h, m: handle_photoset_upload(h, m.group(1))),
    (re.compile(r"^/api/projects/([^/]+)/run$"), lambda h, m: handle_run(h, m.group(1))),
    (re.compile(r"^/api/projects/([^/]+)/cancel$"), lambda h, m: handle_cancel(h, m.group(1))),
    (re.compile(r"^/api/projects/([^/]+)/result$"), lambda h, m: handle_result_upload(h, m.group(1))),
    (re.compile(r"^/api/projects/([^/]+)/poster$"), lambda h, m: handle_poster_upload(h, m.group(1))),
    (re.compile(r"^/api/projects/([^/]+)/keyframes$"), lambda h, m: handle_keyframes_post(h, m.group(1))),
]

DELETE_ROUTES: list[tuple[re.Pattern, object]] = [
    (re.compile(r"^/api/projects/([^/]+)$"), lambda h, m: handle_project_delete(h, m.group(1))),
    (re.compile(r"^/api/tours/([^/]+)$"), lambda h, m: handle_tour_delete(h, m.group(1))),
]


class Handler(BaseHTTPRequestHandler):
    server_version = f"OrbitStudio/{VERSION}"
    protocol_version = "HTTP/1.1"

    def _dispatch(self, routes: list[tuple[re.Pattern, object]]) -> None:
        path = urllib.parse.urlsplit(self.path).path
        for pattern, func in routes:
            match = pattern.match(path)
            if match:
                try:
                    func(self, match)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                except Exception as exc:
                    send_error(self, 500, "internal_error", str(exc))
                return
        send_error(self, 404, "not_found", f"no route for {path}")

    def do_GET(self) -> None:
        self._dispatch(GET_ROUTES)

    def do_POST(self) -> None:
        self._dispatch(POST_ROUTES)

    def do_DELETE(self) -> None:
        self._dispatch(DELETE_ROUTES)

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        cors_headers(self)
        self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, format: str, *args) -> None:
        pass


def build_server(port: int, host: str = "127.0.0.1") -> ThreadingHTTPServer:
    return ThreadingHTTPServer((host, port), Handler)


def main() -> None:
    parser = argparse.ArgumentParser(description="orbit-studio backend")
    parser.add_argument("--port", type=int, default=7360)
    parser.add_argument(
        "--lan",
        action="store_true",
        help="also listen on the local network so the phone app can send bundles (default: localhost only)",
    )
    args = parser.parse_args()
    host = "0.0.0.0" if args.lan else "127.0.0.1"
    seed_sample_tour()
    server = build_server(args.port, host)
    print(f"orbit-studio serving on http://127.0.0.1:{args.port}", flush=True)
    print(f"  360 tours:  http://127.0.0.1:{args.port}/tour", flush=True)
    if args.lan:
        print(f"LAN mode: also reachable at http://<this-laptop's-IP>:{args.port} for Orbit Capture", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.shutdown()


if __name__ == "__main__":
    main()
