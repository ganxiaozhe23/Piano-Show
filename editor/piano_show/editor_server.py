from __future__ import annotations

"""Local web editor service.

The editor deliberately stays a small stdlib HTTP server. All state remains in
the browser until a save/compile/debug request is made; no source is uploaded
to a remote service.
"""

import base64
import cgi
import json
import mimetypes
import tempfile
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from .compiler import compile_project_state
from .debug_runner import get_debug_runner
from .midi import event_summary, read_midi
from .palette import load_palette, palette_json
from .project import event_records, project_json_state, read_project, state_from_json, write_project

WEB_ROOT = Path(__file__).with_name("web")


def _json_response(handler: BaseHTTPRequestHandler, value: object, status: int = 200) -> None:
    body = json.dumps(value, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Cache-Control", "no-store")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def _error(handler: BaseHTTPRequestHandler, message: str, status: int, *, detail: object | None = None) -> None:
    payload: dict[str, object] = {"error": message, "status": status}
    if detail is not None:
        payload["detail"] = detail
    _json_response(handler, payload, status)


def _read_json_body(handler: BaseHTTPRequestHandler, max_bytes: int = 70 * 1024 * 1024) -> dict[str, object]:
    length = int(handler.headers.get("Content-Length", "0"))
    if length < 0 or length > max_bytes:
        raise ValueError("request is too large")
    value = json.loads(handler.rfile.read(length))
    if not isinstance(value, dict):
        raise ValueError("JSON body must be an object")
    return value


def _multipart_field(handler: BaseHTTPRequestHandler, name: str) -> bytes:
    form = cgi.FieldStorage(
        fp=handler.rfile,
        headers=handler.headers,
        environ={"REQUEST_METHOD": "POST", "CONTENT_TYPE": handler.headers.get("Content-Type", "")},
    )
    item = form[name] if name in form else None
    if item is None:
        raise ValueError(f"multipart field '{name}' is required")
    if isinstance(item, list):
        item = item[0]
    return item.file.read() if getattr(item, "file", None) is not None else str(item.value).encode()


def _state_from_request(handler: BaseHTTPRequestHandler) -> dict[str, object]:
    content_type = handler.headers.get("Content-Type", "")
    if content_type.startswith("application/json"):
        return state_from_json(_read_json_body(handler))
    return read_project(_multipart_field(handler, "project"))


def _compiled_json(compiled) -> dict[str, object]:
    return {
        "manifest": compiled.manifest,
        "layout": compiled.layout,
        "events": event_records(compiled.events),
        "pixels": [[p.x, p.y, p.palette_index, p.queue_index] for p in compiled.pixels],
        "palette": compiled.palette,
        "previewPngBase64": base64.b64encode(compiled.preview_png).decode("ascii"),
        "compiledAt": datetime.now(timezone.utc).isoformat(),
    }


class EditorHandler(BaseHTTPRequestHandler):
    runner = None

    def _serve_static(self, path: str) -> None:
        relative = "index.html" if path in ("", "/", "/index.html") else path.lstrip("/")
        candidate = (WEB_ROOT / relative).resolve()
        root = WEB_ROOT.resolve()
        if root not in candidate.parents and candidate != root:
            self.send_error(404)
            return
        if not candidate.is_file():
            self.send_error(404)
            return
        body = candidate.read_bytes()
        content_type = mimetypes.guess_type(candidate.name)[0] or "application/octet-stream"
        if candidate.suffix == ".js":
            content_type = "text/javascript; charset=utf-8"
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path
        runner = self.runner
        if not path.startswith("/api/"):
            self._serve_static(path)
            return
        if path == "/api/health":
            _json_response(self, {"ok": True})
        elif path == "/api/palettes":
            _json_response(self, {"palettes": palette_json(load_palette(None)), "paletteId": "minecraft_32"})
        elif path == "/api/debug/status":
            _json_response(self, runner.status())
        elif path == "/api/debug/instructions":
            status = runner.status()
            _json_response(self, {"showFile": status.get("showFile"), "commands": status.get("instructions", [])})
        elif path == "/api/debug/logs":
            query = parse_qs(parsed.query)
            try:
                after = max(0, int(query.get("after", [0])[0]))
            except ValueError:
                after = 0
            _json_response(self, runner.logs(after))
        elif path == "/api/debug/stream":
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.end_headers()
            cursor = 0
            try:
                for _ in range(120):
                    status = runner.status()
                    log_data = runner.logs(cursor)
                    cursor = log_data["cursor"]
                    payload = {"status": status, **log_data}
                    self.wfile.write(f"data: {json.dumps(payload, ensure_ascii=False)}\n\n".encode("utf-8"))
                    self.wfile.flush()
                    if log_data["closed"]:
                        break
                    time.sleep(0.5)
            except (BrokenPipeError, ConnectionResetError):
                pass
        else:
            self.send_error(404)

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        runner = self.runner
        try:
            if path == "/api/project/open":
                _json_response(self, project_json_state(read_project(_multipart_field(self, "project"))))
                return
            if path == "/api/project/save":
                state = state_from_json(_read_json_body(self))
                with tempfile.TemporaryDirectory(prefix="piano-show-save-") as directory:
                    project_path = Path(directory) / "project.pwork"
                    write_project(project_path, state["project"], state["midi"], state["image"], events=state.get("events"), overrides=state.get("overrides"), history=state.get("history"))
                    body = project_path.read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", "application/zip")
                self.send_header("Content-Disposition", "attachment; filename=project.pwork")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if path == "/api/project/analyze-midi":
                try:
                    content_type = self.headers.get("Content-Type", "")
                    if content_type.startswith("application/json"):
                        encoded = str(_read_json_body(self).get("midiBase64", ""))
                        raw = base64.b64decode(encoded, validate=True)
                    else:
                        raw = _multipart_field(self, "midi")
                    # Close the temporary file before mido opens it again; this is
                    # required on Windows where an open NamedTemporaryFile cannot
                    # be reopened by another handle.
                    with tempfile.TemporaryDirectory(prefix="piano-show-midi-") as directory:
                        midi_path = Path(directory) / "source.mid"
                        midi_path.write_bytes(raw)
                        events = read_midi(midi_path)
                    _json_response(self, {"events": event_records(events), "summary": event_summary(events), "eventCount": len(events)})
                except Exception as error:
                    _error(self, str(error) or error.__class__.__name__, 400)
                return
            if path in ("/api/compile/preview", "/api/compile"):
                try:
                    state = state_from_json(_read_json_body(self))
                    if path.endswith("preview"):
                        with tempfile.TemporaryDirectory(prefix="piano-show-preview-") as directory:
                            compiled = compile_project_state(state, Path(directory) / "preview.pshow")
                        _json_response(self, _compiled_json(compiled))
                    else:
                        with tempfile.TemporaryDirectory(prefix="piano-show-export-") as directory:
                            output = Path(directory) / "show.pshow"
                            compiled = compile_project_state(state, output)
                            body = output.read_bytes()
                        name = str(state.get("project", {}).get("name", "show")) if isinstance(state.get("project"), dict) else "show"
                        safe = "".join(ch if ch.isalnum() or ch in "_-." else "_" for ch in name).strip("._")[:80] or "show"
                        self.send_response(200)
                        self.send_header("Content-Type", "application/zip")
                        self.send_header("Content-Disposition", f'attachment; filename="{safe}.pshow"')
                        self.send_header("X-Show-Id", str(compiled.manifest.get("showId", "")))
                        self.send_header("Content-Length", str(len(body)))
                        self.end_headers()
                        self.wfile.write(body)
                except Exception as error:
                    _error(self, str(error) or error.__class__.__name__, 422)
                return
            if path == "/api/debug/launch":
                if self.headers.get("Content-Type", "").startswith("application/json"):
                    state = state_from_json(_read_json_body(self))
                    with tempfile.TemporaryDirectory(prefix="piano-show-debug-request-") as directory:
                        project_path = Path(directory) / "project.pwork"
                        write_project(project_path, state["project"], state["midi"], state["image"], events=state.get("events"), overrides=state.get("overrides"), history=state.get("history"))
                        project_bytes = project_path.read_bytes()
                else:
                    project_bytes = _multipart_field(self, "project")
                result = runner.launch(project_bytes)
                response_status = 409 if result.get("conflict") else (500 if result.get("status") == "failed" else 200)
                _json_response(self, result, response_status)
                return
            if path == "/api/debug/stop":
                _json_response(self, runner.stop())
                return
            self.send_error(404)
        except ValueError as error:
            _error(self, str(error) or error.__class__.__name__, 422 if path in ("/api/compile", "/api/compile/preview") else 400)
        except Exception as error:  # pragma: no cover - defensive HTTP boundary
            _error(self, str(error) or error.__class__.__name__, 500)

    def log_message(self, *_args: object) -> None:
        return


def run_editor(*, host: str = "127.0.0.1", port: int = 0, open_browser: bool = True) -> int:
    EditorHandler.runner = get_debug_runner()
    server = ThreadingHTTPServer((host, port), EditorHandler)
    url = f"http://{host}:{server.server_port}/"
    print(f"editor: {url} (Ctrl+C to stop)")
    if open_browser:
        import threading
        threading.Timer(0.15, lambda: __import__("webbrowser").open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        EditorHandler.runner.stop()
        server.server_close()
    return 0
