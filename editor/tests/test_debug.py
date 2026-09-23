from __future__ import annotations

from pathlib import Path
import hashlib
import subprocess
import sys

from PIL import Image

from piano_show.compiler import compile_project_state
from piano_show.debug_runner import DebugRunner
from piano_show.project import default_project, read_project, safe_project_name, write_project


def test_project_round_trip_and_safe_name(tmp_path: Path) -> None:
    assert safe_project_name(r"..\demo show!.pwork") == "demo_show"
    project_path = tmp_path / "demo.pwork"
    project = default_project(name="demo show")
    write_project(
        project_path,
        project,
        b"midi-bytes",
        b"image-bytes",
        events=[{"id": "e1", "tick": 0, "note": 60, "velocity": 100, "durationTicks": 8}],
        overrides={"1,2": 4, "3,4": None},
        history=[{"type": "pixel", "payload": {}}],
    )
    state = read_project(project_path)
    assert state["project"]["name"] == "demo_show"
    assert state["midi"] == b"midi-bytes"
    assert state["overrides"] == {"1,2": 4, "3,4": None}
    assert state["events"][0]["note"] == 60


def test_compile_project_applies_pixel_override(tmp_path: Path) -> None:
    midi = Path("test/test.mid").read_bytes()
    image_path = tmp_path / "image.png"
    Image.new("RGBA", (4, 4), (255, 0, 0, 255)).save(image_path)
    project = default_project(name="override", options={"resolution": 4, "pixelScale": 1})
    pwork = tmp_path / "override.pwork"
    write_project(pwork, project, midi, image_path.read_bytes(), overrides={"0,0": None})
    state = read_project(pwork)
    output = tmp_path / "override.pshow"
    compiled = compile_project_state(state, output)
    assert compiled.manifest["formatVersion"] == 2
    assert not any(pixel.x == 0 and pixel.y == 0 for pixel in compiled.pixels)


def test_legacy_project_without_events_override_uses_embedded_midi(tmp_path: Path) -> None:
    midi = (Path(__file__).parents[2] / "test" / "test.mid").read_bytes()
    image_path = tmp_path / "image.png"
    Image.new("RGBA", (2, 2), (255, 0, 0, 255)).save(image_path)
    pwork = tmp_path / "legacy.pwork"
    # Omitting events models v1 .pwork containers that predate working/events.json.
    write_project(pwork, default_project(name="legacy", options={"resolution": 2}), midi, image_path.read_bytes())
    state = read_project(pwork)
    assert state["events"] is None
    compiled = compile_project_state(state, tmp_path / "legacy.pshow")
    assert compiled.events


def test_debug_runner_log_cursor_and_state(tmp_path: Path) -> None:
    runner = DebugRunner(tmp_path)
    runner._append_log("one")
    runner._append_log("two")
    payload = runner.logs(1)
    assert payload["cursor"] == 2
    assert payload["lines"] == ["two"]
    assert runner.status()["status"] == "idle"


def test_debug_runner_rejects_different_project_while_running(tmp_path: Path) -> None:
    runner = DebugRunner(tmp_path)
    project = b"project-a"
    process = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(5)"])
    try:
        runner.process = process
        runner.state.update({"status": "running", "pid": process.pid, "projectHash": hashlib.sha256(project).hexdigest()})
        reused = runner.launch(project)
        assert reused.get("reused") is True
        conflict = runner.launch(b"project-b")
        assert conflict.get("conflict") is True
        assert "尚未加载" in conflict["error"]
    finally:
        process.terminate()
        process.wait(timeout=3)
