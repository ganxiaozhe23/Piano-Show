from __future__ import annotations

"""Safe, single-instance Fabric runClient process manager for the local editor."""

import os
import json
import re
import subprocess
import tempfile
import threading
from collections import deque
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .compiler import compile_project_state
from .project import read_project, safe_project_name


class DebugRunner:
    def __init__(self, workspace: str | Path | None = None) -> None:
        self.workspace = Path(workspace or Path(__file__).resolve().parents[2]).resolve()
        self.mod_dir = self.workspace / "mod"
        self.pid_file = self.mod_dir / "run" / ".piano-show-debug.json"
        self.process: subprocess.Popen[str] | None = None
        self.lock = threading.RLock()
        self.lines: deque[tuple[int, str]] = deque(maxlen=2000)
        self.cursor = 0
        self.state: dict[str, Any] = {
            "status": "idle", "pid": None, "startedAt": None, "showFile": None,
            "showId": None, "command": "gradlew.bat runClient", "exitCode": None,
            "logCursor": 0, "instructions": [], "error": None,
        }
        self._restore_managed_process()

    def _restore_managed_process(self) -> None:
        """Recover a still-running client if the editor service was restarted."""
        try:
            metadata = json.loads(self.pid_file.read_text(encoding="utf-8"))
            pid = int(metadata["pid"])
            os.kill(pid, 0)
        except (OSError, ValueError, KeyError, json.JSONDecodeError, FileNotFoundError):
            return
        self.state.update(metadata)
        self.state["status"] = "running"
        self.state["recovered"] = True

    def _append_log(self, line: str) -> None:
        with self.lock:
            self.cursor += 1
            self.lines.append((self.cursor, line.rstrip("\r\n")))
            self.state["logCursor"] = self.cursor

    def _active(self) -> bool:
        if self.process is not None:
            return self.process.poll() is None
        if self.state.get("status") != "running" or not self.state.get("pid"):
            return False
        try:
            os.kill(int(self.state["pid"]), 0)
            return True
        except OSError:
            self.state["status"] = "exited"
            self.state["pid"] = None
            self.pid_file.unlink(missing_ok=True)
            return False

    def _java_home(self) -> Path:
        candidates: list[Path] = []
        if os.environ.get("JAVA_HOME"):
            candidates.append(Path(os.environ["JAVA_HOME"]))
        report = self.workspace / "environment-report.txt"
        if report.is_file():
            match = re.search(r"^JAVA_HOME=(.+)$", report.read_text(encoding="utf-8"), re.MULTILINE)
            if match:
                candidates.append(Path(match.group(1).strip()))
        candidates.append(Path(r"D:\Dev\Java\temurin-21"))
        for candidate in candidates:
            java = candidate / "bin" / ("java.exe" if os.name == "nt" else "java")
            if java.is_file():
                return candidate
        raise RuntimeError("找不到 Java 21。请设置 JAVA_HOME，或安装到 D:\\Dev\\Java\\temurin-21。")

    def _reader(self, process: subprocess.Popen[str]) -> None:
        assert process.stdout is not None
        for line in process.stdout:
            self._append_log(line)
        code = process.wait()
        with self.lock:
            if self.process is process:
                self.state["exitCode"] = code
                self.state["status"] = "exited" if code == 0 else "failed"
                self.process = None
                self.state["pid"] = None
                self.pid_file.unlink(missing_ok=True)

    def status(self) -> dict[str, Any]:
        with self.lock:
            self._active()
            if self.process is not None and self.process.poll() is not None:
                self.state["exitCode"] = self.process.returncode
                self.state["status"] = "exited" if self.process.returncode == 0 else "failed"
                self.process = None
                self.state["pid"] = None
                self.pid_file.unlink(missing_ok=True)
            return dict(self.state)

    def launch(self, project_bytes: bytes) -> dict[str, Any]:
        with self.lock:
            if self._active():
                result = dict(self.state)
                result["reused"] = True
                result["message"] = "已有 Minecraft 调试实例正在运行，已复用现有实例。"
                return result
            self.state.update({"status": "preparing", "error": None, "exitCode": None})
        try:
            state = read_project(project_bytes)
            project = state["project"]
            name = safe_project_name(str(project.get("name", "show")))
            show_dir = self.mod_dir / "run" / "config" / "piano-shows"
            show_dir.mkdir(parents=True, exist_ok=True)
            with tempfile.TemporaryDirectory(prefix="piano-show-debug-") as directory:
                temporary_show = Path(directory) / f"{name}.pshow"
                compiled = compile_project_state(state, temporary_show)
                target = show_dir / f"{name}.pshow"
                temporary_target = show_dir / f".{name}.pshow.tmp"
                temporary_target.write_bytes(temporary_show.read_bytes())
                temporary_target.replace(target)
            java_home = self._java_home()
            env = os.environ.copy()
            env["JAVA_HOME"] = str(java_home)
            env["PATH"] = str(java_home / "bin") + os.pathsep + env.get("PATH", "")
            gradle = self.mod_dir / ("gradlew.bat" if os.name == "nt" else "gradlew")
            if not gradle.is_file():
                raise RuntimeError(f"找不到 Gradle wrapper: {gradle}")
            with self.lock:
                self.state.update({
                    "status": "starting", "showFile": target.name,
                    "showId": compiled.manifest.get("showId"),
                    "startedAt": datetime.now(timezone.utc).isoformat(),
                    "instructions": [f"/piano load {target.name}", "/piano build 0 64 0", "/piano preview", "/piano play"],
                })
            creationflags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
            process = subprocess.Popen(
                [str(gradle), "runClient"], cwd=self.mod_dir, env=env,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                encoding="utf-8", errors="replace", bufsize=1, creationflags=creationflags,
            )
            with self.lock:
                self.process = process
                self.state.update({"status": "running", "pid": process.pid})
                self.pid_file.parent.mkdir(parents=True, exist_ok=True)
                self.pid_file.write_text(json.dumps({"pid": process.pid, "status": "running", "showFile": self.state["showFile"], "showId": self.state["showId"], "startedAt": self.state["startedAt"], "instructions": self.state["instructions"]}), encoding="utf-8")
            threading.Thread(target=self._reader, args=(process,), daemon=True, name="piano-show-debug-log").start()
            return self.status()
        except Exception as error:
            with self.lock:
                self.state.update({"status": "failed", "error": str(error), "pid": None})
            return self.status()

    def logs(self, after: int = 0) -> dict[str, Any]:
        with self.lock:
            lines = [line for cursor, line in self.lines if cursor > after]
            return {"cursor": self.cursor, "lines": lines, "closed": not self._active() and self.state["status"] in {"exited", "failed", "stopped"}}

    def stop(self) -> dict[str, Any]:
        with self.lock:
            process = self.process
            recovered_pid = int(self.state["pid"]) if process is None and self.state.get("pid") else None
            if process is None and recovered_pid is None:
                if self.state["status"] in {"running", "starting"}:
                    self.state["status"] = "stopped"
                return dict(self.state)
            if process is not None and process.poll() is not None:
                self.state["status"] = "stopped"
                self.state["exitCode"] = process.returncode
                self.process = None
                self.state["pid"] = None
                self.pid_file.unlink(missing_ok=True)
                return dict(self.state)
            pid = process.pid if process is not None else recovered_pid
        try:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], check=False, capture_output=True, text=True)
            else:
                process.terminate()
            if process is not None:
                process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            if process is not None:
                process.kill()
        with self.lock:
            self.state["status"] = "stopped"
            self.state["exitCode"] = process.returncode if process is not None else None
            self.process = None
            self.state["pid"] = None
            self.pid_file.unlink(missing_ok=True)
            return dict(self.state)


_RUNNER: DebugRunner | None = None


def get_debug_runner(workspace: str | Path | None = None) -> DebugRunner:
    global _RUNNER
    if _RUNNER is None:
        _RUNNER = DebugRunner(workspace)
    return _RUNNER
