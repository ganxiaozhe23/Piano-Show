from __future__ import annotations

"""Standalone, dependency-free browser preview for a .pshow package.

The preview deliberately uses one HTML canvas rather than a framework so it can
run offline and remain easy to ship with the Python editor.  It mirrors the
package's logical pixel order and adaptive timing model closely enough to tune
composition and budgets before launching Minecraft.
"""

import json
import threading
import webbrowser
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse


_HTML = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Piano Show Preview</title>
<style>
html,body{margin:0;height:100%;background:#090b12;color:#e8edf7;font:14px system-ui,sans-serif;overflow:hidden}
#app{display:grid;grid-template-columns:1fr 310px;height:100%}canvas{width:100%;height:100%;display:block;background:radial-gradient(circle at 50% 35%,#202a44,#090b12 70%)}
aside{padding:18px;background:#111522;border-left:1px solid #293148;overflow:auto}h1{font-size:18px;margin:0 0 14px}label{display:block;margin:12px 0 4px;color:#aeb9d2}input[type=range]{width:100%}button{background:#2b6de0;color:#fff;border:0;border-radius:5px;padding:7px 10px;margin:3px 2px;cursor:pointer}button.secondary{background:#2a3144}#stats{white-space:pre-line;color:#aeb9d2;line-height:1.45;margin-top:14px}.hint{color:#74809a;font-size:12px;margin-top:18px}
</style></head><body><div id="app"><canvas id="view"></canvas><aside><h1>Piano Show · Web Preview</h1>
<button id="play">Play</button><button class="secondary" id="step">Step</button><button class="secondary" id="reset">Reset</button>
<label>Timeline <span id="tickLabel">0</span></label><input id="timeline" type="range" min="0" max="1" value="0">
<label>Speed <span id="speedLabel">1.0×</span></label><input id="speed" type="range" min="0.25" max="4" value="1" step="0.25">
<label>Zoom</label><input id="zoom" type="range" min="0.35" max="2.5" value="1" step="0.05">
<label>Mode</label><select id="mode"><option value="display">Display (smooth)</option><option value="physical">Physical (arc)</option></select>
<div id="stats"></div><div class="hint">This local preview contains only compiled timing, pixels and palette data. No source files leave your machine.</div>
</aside></div><script>
const DATA=__DATA__;
const canvas=document.querySelector('#view'),ctx=canvas.getContext('2d');
const timeline=document.querySelector('#timeline'),tickLabel=document.querySelector('#tickLabel'),stats=document.querySelector('#stats');
const events=DATA.events||[], pixels=DATA.pixels||[], manifest=DATA.manifest||{}, layout=DATA.layout||{};
const scale=Number(layout.canvas?.pixelScale||manifest.pixelScale||2), width=Number(manifest.logicalWidth||manifest.imageWidth||1), height=Number(manifest.logicalHeight||manifest.imageHeight||1);
const firstTick=events.length?events[0][0]:0,lastTick=events.length?events[events.length-1][0]:1, endTick=Math.max(lastTick+80,firstTick+1);
timeline.max=endTick; timeline.value=firstTick;
let tick=firstTick,playing=false,lastTime=performance.now(),speed=1,zoom=1;
function resize(){const dpr=devicePixelRatio||1;canvas.width=canvas.clientWidth*dpr;canvas.height=canvas.clientHeight*dpr;ctx.setTransform(dpr,0,0,dpr,0,0)}
addEventListener('resize',resize);resize();
function project(x,y){const s=Math.min(canvas.clientWidth/(width*scale+40),canvas.clientHeight/(height*scale+70))*zoom;return [canvas.clientWidth/2+(x-width*scale/2)*s,canvas.clientHeight/2+(height*scale/2-y)*s]}
function draw(){const w=canvas.clientWidth,h=canvas.clientHeight;ctx.clearRect(0,0,w,h);const s=Math.min(w/(width*scale+40),h/(height*scale+70))*zoom;
  ctx.save();ctx.translate(w/2-(width*scale/2)*s,h/2+(height*scale/2)*s);ctx.fillStyle='#141a28';ctx.fillRect(-10*s,-10*s,width*scale*s+20*s,height*scale*s+20*s);
  const visible=Math.floor(Math.max(0,(tick-firstTick)/(endTick-firstTick))*pixels.length);
  for(let i=0;i<visible;i++){const p=pixels[i],c=(DATA.palette?.[p[2]]?.color)||[180,180,180];ctx.fillStyle=`rgb(${c[0]},${c[1]},${c[2]})`;ctx.fillRect(p[0]*scale*s,(height-1-p[1])*scale*s,scale*s+.2,scale*s+.2)}
  const active=Math.min(pixels.length-visible,Math.max(0,Math.floor((tick-firstTick)/2)));for(let i=0;i<Math.min(active,700);i++){const p=pixels[visible+i];if(!p)break;const t=Math.min(1,Math.max(0,(tick-firstTick)/(endTick-firstTick)+i/Math.max(1,pixels.length)));const c=(DATA.palette?.[p[2]]?.color)||[180,180,180];const sx=width*scale*.5,sy=height*scale+8;const tx=p[0]*scale,ty=(height-1-p[1])*scale;const arc=Math.sin(t*Math.PI)*Math.min(width,height)*.12;const x=sx+(tx-sx)*t,y=sy+(ty-sy)*t-arc;ctx.fillStyle=`rgb(${c[0]},${c[1]},${c[2]})`;ctx.shadowColor=ctx.fillStyle;ctx.shadowBlur=8*s;ctx.fillRect(x*s,y*s,scale*s,scale*s);ctx.shadowBlur=0}
  ctx.restore();
  tickLabel.textContent=Math.floor(tick);timeline.value=Math.floor(tick);const density=events.filter(e=>e[0]>=tick&&e[0]<tick+40).length/40;stats.textContent=`events: ${events.length}\npixels: ${pixels.length}\ncanvas: ${width}×${height} logical / ${width*scale}×${height*scale} physical\nactive preview: ${Math.min(active,700)}\nnext-40t density: ${density.toFixed(2)} notes/tick\nmode: ${document.querySelector('#mode').value}`;
}
function frame(now){const dt=Math.min(100,now-lastTime);lastTime=now;if(playing){tick+=dt/50*speed;if(tick>=endTick){tick=endTick;playing=false;document.querySelector('#play').textContent='Play'}}draw();requestAnimationFrame(frame)}requestAnimationFrame(frame);
document.querySelector('#play').onclick=()=>{playing=!playing;document.querySelector('#play').textContent=playing?'Pause':'Play'};
document.querySelector('#step').onclick=()=>{tick=Math.min(endTick,tick+1);draw()};document.querySelector('#reset').onclick=()=>{tick=firstTick;playing=false;draw()};timeline.oninput=()=>{tick=Number(timeline.value);draw()};document.querySelector('#speed').oninput=e=>{speed=Number(e.target.value);document.querySelector('#speedLabel').textContent=speed.toFixed(2)+'×'};document.querySelector('#zoom').oninput=e=>{zoom=Number(e.target.value)};document.querySelector('#mode').onchange=draw;draw();
</script></body></html>"""


def _load_preview(path: Path) -> dict[str, object]:
    with zipfile.ZipFile(path) as archive:
        try:
            return json.loads(archive.read("preview.json"))
        except KeyError:
            manifest = json.loads(archive.read("manifest.json"))
            layout = json.loads(archive.read("layout.json")) if "layout.json" in archive.namelist() else {}
            return {"manifest": manifest, "layout": layout, "events": [], "pixels": [], "palette": []}


class _Handler(BaseHTTPRequestHandler):
    data: dict[str, object] = {}

    def do_GET(self) -> None:  # noqa: N802
        if urlparse(self.path).path not in ("/", "/index.html"):
            self.send_error(404)
            return
        payload = json.dumps(self.data, ensure_ascii=False, separators=(",", ":")).replace("</", "<\\/")
        body = _HTML.replace("__DATA__", payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_args: object) -> None:
        return


def run_server(show: str | Path, *, host: str = "127.0.0.1", port: int = 0, open_browser: bool = False) -> int:
    path = Path(show)
    if not path.is_file():
        raise FileNotFoundError(path)
    _Handler.data = _load_preview(path)
    server = ThreadingHTTPServer((host, port), _Handler)
    url = f"http://{host}:{server.server_port}/"
    print(f"preview: {url} (Ctrl+C to stop)")
    if open_browser:
        threading.Timer(0.15, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0
