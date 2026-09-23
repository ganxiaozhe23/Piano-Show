from __future__ import annotations

"""Portable .pwork project containers used by the local web editor."""

import base64
import io
import json
import re
import uuid
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

from .models import NoteEvent

MAX_PROJECT_BYTES = 64 * 1024 * 1024
SAFE_NAME = re.compile(r"[^A-Za-z0-9_.-]+")


def safe_project_name(name: str) -> str:
    stem = Path(name or "show").stem
    stem = SAFE_NAME.sub("_", stem).strip("._") or "show"
    return stem[:80]


def _json_bytes(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True).encode("utf-8")


def default_project(*, name: str = "show", options: dict[str, object] | None = None) -> dict[str, object]:
    defaults = {
        "resolution": 128,
        "surface": "wall_north",
        "orientation": "wall_north",
        "pixelScale": 2,
        "visualMode": "display",
        "timingMode": "adaptive",
        "canvasGap": 8,
        "canvasLift": 2,
        "canvasOffset": [0, 0, 0],
        "imageRotation": 0,
        "motionMode": "arc",
        "motionGravity": 0.04,
        "motionDrag": 0.98,
        "motionArcHeight": 1.5,
    }
    if options:
        defaults.update(options)
        # Keep the legacy orientation alias and v2 surface in sync when a
        # caller supplies only one of them while creating a new project.
        if "surface" not in options and "orientation" in options:
            defaults["surface"] = options["orientation"]
        elif "orientation" not in options and "surface" in options:
            defaults["orientation"] = options["surface"]
    return {
        "formatVersion": 1,
        "projectId": str(uuid.uuid4()),
        "name": safe_project_name(name),
        "compileOptions": defaults,
        "sources": {"midi": "source/midi.mid", "image": "source/image.png"},
    }


def write_project(
    path: str | Path,
    project: dict[str, object],
    midi: bytes,
    image: bytes,
    *,
    events: list[dict[str, object]] | None = None,
    overrides: dict[str, int | None] | None = None,
    history: list[dict[str, object]] | None = None,
) -> None:
    project = dict(project)
    project["formatVersion"] = 1
    project["name"] = safe_project_name(str(project.get("name", "show")))
    project.setdefault("sources", {"midi": "source/midi.mid", "image": "source/image.png"})
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    try:
        with ZipFile(temporary, "w", compression=ZIP_DEFLATED, compresslevel=9) as archive:
            archive.writestr("project.json", _json_bytes(project))
            archive.writestr("source/midi.mid", midi)
            archive.writestr("source/image.png", image)
            # Omit the working event override when callers did not provide one.
            # This preserves the v1 behaviour of compiling directly from the
            # embedded MIDI.  An explicitly supplied empty list is meaningful
            # (the editor can use it to represent "all notes deleted") and is
            # therefore still serialized.
            if events is not None:
                archive.writestr("working/events.json", _json_bytes(events))
            archive.writestr("working/image-overrides.json", _json_bytes({"overrides": overrides or {}}))
            archive.writestr("history.json", _json_bytes((history or [])[-100:]))
    finally:
        if temporary.exists():
            temporary.replace(path)


def read_project(path_or_bytes: str | Path | bytes) -> dict[str, object]:
    if isinstance(path_or_bytes, (str, Path)):
        raw = Path(path_or_bytes).read_bytes()
    else:
        raw = path_or_bytes
    if len(raw) > MAX_PROJECT_BYTES:
        raise ValueError("project exceeds 64 MiB limit")
    with ZipFile(io.BytesIO(raw)) as archive:
        names = set(archive.namelist())
        required = {"project.json", "source/midi.mid", "source/image.png"}
        if not required.issubset(names):
            raise ValueError("invalid .pwork: required entry missing")
        project = json.loads(archive.read("project.json"))
        if int(project.get("formatVersion", 0)) != 1:
            raise ValueError("unsupported .pwork format")
        events = json.loads(archive.read("working/events.json")) if "working/events.json" in names else None
        override_data = json.loads(archive.read("working/image-overrides.json")) if "working/image-overrides.json" in names else {}
        history = json.loads(archive.read("history.json")) if "history.json" in names else []
        return {
            "project": project,
            "midi": archive.read("source/midi.mid"),
            "image": archive.read("source/image.png"),
            "events": events,
            "overrides": override_data.get("overrides", {}),
            "history": history[-100:],
        }


def project_json_state(state: dict[str, object]) -> dict[str, object]:
    """Return a JSON-safe state for the browser API."""
    return {
        "project": state["project"],
        "midiBase64": base64.b64encode(state["midi"]).decode("ascii"),
        "imageBase64": base64.b64encode(state["image"]).decode("ascii"),
        # ``None`` means this is a legacy project without an events override;
        # the compiler should then decode the embedded MIDI source.
        "events": state.get("events"),
        "overrides": state.get("overrides", {}),
        "history": state.get("history", [])[-100:],
    }


def state_from_json(value: dict[str, object]) -> dict[str, object]:
    try:
        return {
            "project": value["project"],
            "midi": base64.b64decode(str(value["midiBase64"]), validate=True),
            "image": base64.b64decode(str(value["imageBase64"]), validate=True),
            "events": value.get("events"),
            "overrides": value.get("overrides", {}),
            "history": list(value.get("history", []))[-100:],
        }
    except (KeyError, ValueError, TypeError) as error:
        raise ValueError("invalid project JSON state") from error


def event_records(events: list[NoteEvent]) -> list[dict[str, object]]:
    return [
        {"id": f"event-{event.order}", "tick": event.tick, "note": event.note, "velocity": event.velocity,
         "durationTicks": event.duration_ticks, "track": event.track, "channel": event.channel, "order": event.order}
        for event in events
    ]


def events_from_records(records: list[dict[str, object]]) -> list[NoteEvent]:
    result: list[NoteEvent] = []
    for index, record in enumerate(records):
        try:
            result.append(NoteEvent(
                int(record.get("tick", 0)), int(record["note"]), int(record.get("velocity", 100)),
                int(record.get("durationTicks", record.get("duration_ticks", 1))),
                int(record.get("track", 0)), int(record.get("channel", 0)),
                int(record.get("order", index)),
            ))
        except (KeyError, TypeError, ValueError) as error:
            raise ValueError(f"invalid event record at index {index}") from error
    result.sort(key=lambda event: (event.tick, event.track, event.channel, event.order))
    return result
