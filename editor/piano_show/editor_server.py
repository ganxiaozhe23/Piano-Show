from __future__ import annotations

"""Local browser editor service with project and Minecraft debug endpoints."""

import cgi
import io
import json
import tempfile
import time
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from .debug_runner import get_debug_runner
from .project import project_json_state, read_project, state_from_json, write_project


_HTML = r"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Piano Show 编辑器</title><style>
html,body{margin:0;height:100%;background:#0b0f18;color:#edf2ff;font:14px system-ui,sans-serif}body{display:grid;grid-template-rows:58px 1fr}header{display:flex;align-items:center;gap:10px;padding:0 18px;background:#141b2a;border-bottom:1px solid #2b3851}h1{font-size:18px;margin:0 18px 0 0}button{border:0;border-radius:6px;padding:9px 14px;background:#3275dc;color:white;cursor:pointer}button:disabled{opacity:.45;cursor:not-allowed}button.stop{background:#aa3f4b}.wrap{display:grid;grid-template-columns:260px 1fr 340px;min-height:0}.panel{padding:16px;overflow:auto;border-right:1px solid #273149}.panel:last-child{border-right:0;border-left:1px solid #273149}h2{font-size:14px;color:#aebbd4;margin:4px 0 12px}.drop{border:1px dashed #536686;border-radius:8px;padding:22px;text-align:center;color:#aebbd4}.drop input{max-width:100%}#canvas{width:100%;height:100%;display:block;background:radial-gradient(circle at 50% 40%,#243352,#0b0f18 72%)}.center{min-width:0;position:relative}.status{padding:8px 10px;border-radius:5px;background:#1d2940;color:#b9c8e4;margin:8px 0}.status.ok{background:#164c3e;color:#9df3cc}.status.bad{background:#5a2631;color:#ffd2d8}pre{white-space:pre-wrap;word-break:break-word;background:#080b12;border:1px solid #25304a;border-radius:6px;padding:10px;max-height:300px;overflow:auto;color:#b9c8e4}.hint{color:#75829e;line-height:1.5}.cmd{color:#e7c77b;display:block;margin:4px 0;font-family:ui-monospace,monospace}.metric{display:flex;justify-content:space-between;border-bottom:1px solid #202a40;padding:7px 0;color:#b6c1d8}
</style></head><body><header><h1>Piano Show</h1><input id="file" type="file" accept=".pwork"><button id="open">打开工程</button><button id="save" disabled>保存 .pwork</button><button id="launch" disabled>启动 Minecraft 调试</button><button id="stop" class="stop" disabled>停止调试</button><span id="topStatus" class="status">未加载工程</span></header><div class="wrap"><aside class="panel"><h2>工程</h2><div id="projectInfo" class="hint">请选择 .pwork 工程。</div><h2>当前演出</h2><div id="metrics"></div><h2>调试命令</h2><div id="commands" class="hint">启动后显示。</div></aside><main class="center"><canvas id="canvas"></canvas></main><aside class="panel"><h2>调试状态</h2><div id="debugStatus" class="status">idle</div><pre id="logs">等待启动…</pre><button id="copy">复制日志</button><h2>说明</h2><p class="hint">按钮会把当前工程编译成 v2 .pshow，复制到 mod/run/config/piano-shows，并启动 Fabric runClient。不会自动向游戏输入命令。</p></aside></div><script>
const fileInput=document.querySelector('#file'),openButton=document.querySelector('#open'),saveButton=document.querySelector('#save'),launchButton=document.querySelector('#launch'),stopButton=document.querySelector('#stop'),topStatus=document.querySelector('#topStatus'),debugStatus=document.querySelector('#debugStatus'),logs=document.querySelector('#logs'),projectInfo=document.querySelector('#projectInfo'),metrics=document.querySelector('#metrics'),commands=document.querySelector('#commands'),canvas=document.querySelector('#canvas');let projectFile=null,projectState=null,lastCursor=0,pollTimer=null;
function setStatus(el,text,kind=''){el.textContent=text;el.className='status '+kind}
function renderProject(state){projectState=state;const p=state.project||{};setStatus(topStatus,`已加载：${p.name||'show'}`,'ok');projectInfo.textContent=`工程：${p.name||'show'}\n事件：${(state.events||[]).length||'由 MIDI 编译'}\n像素修改：${Object.keys(state.overrides||{}).length}`;metrics.innerHTML='';const o=p.compileOptions||{};for(const [k,v] of Object.entries({分辨率:o.resolution||128,画布:o.surface||'wall_north',缩放:o.pixelScale||2,模式:o.visualMode||'display'})){const d=document.createElement('div');d.className='metric';d.innerHTML=`<span>${k}</span><strong>${v}</strong>`;metrics.appendChild(d)}launchButton.disabled=false}
function draw(){const dpr=devicePixelRatio||1,w=canvas.clientWidth,h=canvas.clientHeight;canvas.width=w*dpr;canvas.height=h*dpr;const c=canvas.getContext('2d');c.setTransform(dpr,0,0,dpr,0,0);c.clearRect(0,0,w,h);c.fillStyle='#141d31';c.fillRect(40,40,w-80,h-80);c.fillStyle='#6e82ab';c.font='16px system-ui';c.fillText(projectState?'工程已加载，可启动 Minecraft 调试':'请先打开 .pwork 工程',w/2-120,h/2)}addEventListener('resize',draw);draw();
async function openProject(){const f=fileInput.files[0];if(!f)return;projectFile=f;const fd=new FormData();fd.append('project',f);const r=await fetch('/api/project/open',{method:'POST',body:fd});const data=await r.json();if(!r.ok){setStatus(topStatus,data.error||'打开失败','bad');return}renderProject(data)}
async function launch(){if(!projectState)return;launchButton.disabled=true;setStatus(topStatus,'准备并启动 Minecraft…');const r=await fetch('/api/debug/launch',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(projectState)});const data=await r.json();if(!r.ok){setStatus(topStatus,data.error||'启动失败','bad');launchButton.disabled=false;return}renderDebug(data);startPolling()}
function renderDebug(s){const reused=s.reused?'（已复用现有实例）':'';setStatus(debugStatus,`${s.status} ${reused}`,s.status==='running'?'ok':s.status==='failed'?'bad':'');stopButton.disabled=!['running','starting'].includes(s.status);if(s.showFile){commands.innerHTML=(s.instructions||[]).map(x=>`<span class="cmd">${x}</span>`).join('')}if(s.error)setStatus(debugStatus,s.error,'bad')}
async function refresh(){const r=await fetch('/api/debug/status');const s=await r.json();renderDebug(s);const lr=await fetch('/api/debug/logs?after='+lastCursor);const l=await lr.json();if(l.lines?.length){logs.textContent+=(logs.textContent==='等待启动…'?'':'\n')+l.lines.join('\n');logs.scrollTop=logs.scrollHeight;lastCursor=l.cursor}if(['exited','failed','stopped'].includes(s.status)){clearInterval(pollTimer);launchButton.disabled=!projectFile}}
function startPolling(){clearInterval(pollTimer);pollTimer=setInterval(refresh,1000);refresh()}
async function stop(){await fetch('/api/debug/stop',{method:'POST'});refresh()}
async function saveProject(){if(!projectState)return;const r=await fetch('/api/project/save',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(projectState)});if(!r.ok){setStatus(topStatus,'保存失败','bad');return}const blob=await r.blob(),a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=(projectState.project?.name||'show')+'.pwork';a.click();URL.revokeObjectURL(a.href);setStatus(topStatus,'工程已保存','ok')}
openButton.onclick=openProject;saveButton.onclick=saveProject;launchButton.onclick=launch;stopButton.onclick=stop;document.querySelector('#copy').onclick=()=>navigator.clipboard?.writeText(logs.textContent);fileInput.onchange=()=>{launchButton.disabled=!fileInput.files.length;saveButton.disabled=!fileInput.files.length};
</script></body></html>"""


def _json_response(handler: BaseHTTPRequestHandler, value: object, status: int = 200) -> None:
    body = json.dumps(value, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


class EditorHandler(BaseHTTPRequestHandler):
    runner = None

    def _multipart(self) -> bytes:
        form = cgi.FieldStorage(fp=self.rfile, headers=self.headers, environ={"REQUEST_METHOD": "POST", "CONTENT_TYPE": self.headers.get("Content-Type", "")})
        item = form["project"] if "project" in form else None
        if item is None:
            raise ValueError("project multipart field is required")
        return item.file.read()

    def _body_json(self) -> dict[str, object]:
        length = int(self.headers.get("Content-Length", "0"))
        if length > 70 * 1024 * 1024:
            raise ValueError("request is too large")
        return json.loads(self.rfile.read(length))

    def do_GET(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path in ("/", "/index.html"):
            body = _HTML.encode("utf-8")
            self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8"); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body); return
        runner = self.runner
        if path == "/api/health": _json_response(self, {"ok": True}); return
        if path == "/api/debug/status": _json_response(self, runner.status()); return
        if path == "/api/debug/instructions": _json_response(self, {"showFile": runner.status().get("showFile"), "commands": runner.status().get("instructions", [])}); return
        if path == "/api/debug/logs":
            query = parse_qs(urlparse(self.path).query); after = int(query.get("after", [0])[0]); _json_response(self, runner.logs(after)); return
        if path == "/api/debug/stream":
            self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.send_header("Cache-Control", "no-cache"); self.send_header("Connection", "keep-alive"); self.end_headers()
            cursor = 0
            for _ in range(60):
                payload = {"status": runner.status(), **runner.logs(cursor)}; cursor = payload["cursor"]; self.wfile.write(f"data: {json.dumps(payload, ensure_ascii=False)}\n\n".encode()); self.wfile.flush()
                if payload["closed"]: break
                time.sleep(0.5)
            return
        self.send_error(404)

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        runner = self.runner
        try:
            if path == "/api/project/open":
                _json_response(self, project_json_state(read_project(self._multipart()))); return
            if path == "/api/project/save":
                state = state_from_json(self._body_json())
                with tempfile.TemporaryDirectory(prefix="piano-show-save-") as directory:
                    project_path = Path(directory) / "project.pwork"
                    write_project(project_path, state["project"], state["midi"], state["image"], events=state.get("events"), overrides=state.get("overrides"), history=state.get("history"))
                    body = project_path.read_bytes()
                self.send_response(200); self.send_header("Content-Type", "application/zip"); self.send_header("Content-Disposition", "attachment; filename=project.pwork"); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body); return
            if path == "/api/debug/launch":
                content_type = self.headers.get("Content-Type", "")
                if content_type.startswith("application/json"):
                    state = state_from_json(self._body_json())
                    with tempfile.TemporaryDirectory(prefix="piano-show-debug-request-") as directory:
                        project_path = Path(directory) / "project.pwork"
                        write_project(project_path, state["project"], state["midi"], state["image"], events=state.get("events"), overrides=state.get("overrides"), history=state.get("history"))
                        project_bytes = project_path.read_bytes()
                else:
                    project_bytes = self._multipart()
                result = runner.launch(project_bytes); _json_response(self, result, 200 if result.get("status") != "failed" else 500); return
            if path == "/api/debug/stop": _json_response(self, runner.stop()); return
            self.send_error(404)
        except ValueError as error:
            _json_response(self, {"error": str(error)}, 400)
        except Exception as error:
            _json_response(self, {"error": str(error)}, 500)

    def log_message(self, *_args: object) -> None:
        return


def run_editor(*, host: str = "127.0.0.1", port: int = 0, open_browser: bool = True) -> int:
    from .debug_runner import get_debug_runner
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
