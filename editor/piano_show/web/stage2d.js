import {pixelKey, optionsOf} from './state.js';

export class Stage2D {
  constructor(canvas, store, history, inspector) {
    this.canvas = canvas; this.store = store; this.history = history; this.inspector = inspector;
    this.zoom = 1; this.pan = {x:0,y:0}; this.showGrid = true; this.tool = 'brush'; this.paletteIndex = 0;
    this.dragging = false; this.last = null; this.anchor = null; this.onChange = null;
    canvas.addEventListener('pointerdown', e => this.down(e)); canvas.addEventListener('pointermove', e => this.move(e));
    canvas.addEventListener('pointerup', e => this.up(e)); canvas.addEventListener('pointerleave', e => this.up(e));
    canvas.addEventListener('wheel', e => {e.preventDefault(); const old=this.zoom; this.zoom=Math.max(.25,Math.min(20,old*(e.deltaY<0?1.12:.89))); this.draw();}, {passive:false});
    window.addEventListener('resize', () => this.resize()); store.subscribe(() => this.draw()); this.resize();
  }
  resize() { const dpr=devicePixelRatio||1; this.canvas.width=Math.max(1,this.canvas.clientWidth*dpr); this.canvas.height=Math.max(1,this.canvas.clientHeight*dpr); this.canvas.getContext('2d').setTransform(dpr,0,0,dpr,0,0); this.draw(); }
  resetView() { this.zoom=1; this.pan={x:0,y:0}; this.draw(); }
  fit() { this.zoom=1; this.pan={x:0,y:0}; this.draw(); }
  setTool(tool) { this.tool=tool; }
  setPalette(index) { this.paletteIndex=Number(index)||0; }
  copySelection() {
    const selected=this.store.get().selectedPixels||[]; const map=this.pixelMap();
    this.clipboard=selected.map(k=>{const [x,y]=k.split(',').map(Number);return {x,y,value:map.get(k)};}).filter(p=>p.value!==undefined);
    return this.clipboard.length>0;
  }
  pasteSelection() {
    if(!this.clipboard?.length)return false; const before=this.history.snapshot();
    const minX=Math.min(...this.clipboard.map(p=>p.x)), minY=Math.min(...this.clipboard.map(p=>p.y)), selected=[];
    for(const item of this.clipboard){const p={x:item.x-minX+1,y:item.y-minY+1};if(this.valid(p)){this.applyPixel(p,item.value);selected.push(pixelKey(p.x,p.y));}}
    this.store.get().selectedPixels=selected; this.history.record('粘贴像素',before); this.onChange?.(); this.draw(); return true;
  }
  dims() { const s=this.store.get(); const o=optionsOf(s); return {w:Number(s.manifest?.logicalWidth||s.quantizedWidth||o.resolution||128),h:Number(s.manifest?.logicalHeight||s.quantizedHeight||o.resolution||128)}; }
  transform() { const {w,h}=this.dims(), cw=this.canvas.clientWidth,ch=this.canvas.clientHeight; const cell=Math.max(1,Math.min(cw/(w+2),ch/(h+2))*this.zoom); return {cell, ox:(cw-w*cell)/2+this.pan.x, oy:(ch-h*cell)/2+this.pan.y}; }
  point(e) { const r=this.canvas.getBoundingClientRect(), t=this.transform(); return {x:Math.floor((e.clientX-r.left-t.ox)/t.cell), y:this.dims().h-1-Math.floor((e.clientY-r.top-t.oy)/t.cell)}; }
  pixelMap() { const s=this.store.get(), map=new Map(); (s.quantizedPixels||[]).forEach(p=>map.set(pixelKey(p[0],p[1]), Number(p[2]))); Object.entries(s.overrides||{}).forEach(([k,v])=>{if(v===null)map.delete(k);else map.set(k,Number(v));}); return map; }
  color(index) { const c=this.store.get().palette?.[index]?.color || [100,110,130]; return `rgb(${c[0]},${c[1]},${c[2]})`; }
  draw() {
    const ctx=this.canvas.getContext('2d'), w=this.canvas.clientWidth,h=this.canvas.clientHeight; if(!w||!h)return;
    ctx.clearRect(0,0,w,h); const {w:pw,h:ph}=this.dims(), t=this.transform();
    ctx.fillStyle='#0b111d';ctx.fillRect(0,0,w,h); ctx.fillStyle='#171f31';ctx.fillRect(t.ox-3,t.oy-3,pw*t.cell+6,ph*t.cell+6);
    const map=this.pixelMap(); const minX=Math.max(0,Math.floor(-t.ox/t.cell)-1), maxX=Math.min(pw,Math.ceil((w-t.ox)/t.cell)+1), minY=Math.max(0,Math.floor(-t.oy/t.cell)-1), maxY=Math.min(ph,Math.ceil((h-t.oy)/t.cell)+1);
    for(let y=minY;y<maxY;y++) for(let x=minX;x<maxX;x++){const p=map.get(pixelKey(x,y)); if(p!==undefined){ctx.fillStyle=this.color(p);ctx.fillRect(t.ox+x*t.cell,t.oy+(ph-1-y)*t.cell,Math.ceil(t.cell+.2),Math.ceil(t.cell+.2));}}
    if(this.showGrid && t.cell>=3){ctx.strokeStyle='rgba(142,169,210,.18)';ctx.lineWidth=1;const step=pw>256?Math.ceil(pw/128):1;for(let x=0;x<=pw;x+=step){ctx.beginPath();ctx.moveTo(t.ox+x*t.cell,t.oy);ctx.lineTo(t.ox+x*t.cell,t.oy+ph*t.cell);ctx.stroke();}for(let y=0;y<=ph;y+=step){ctx.beginPath();ctx.moveTo(t.ox,t.oy+y*t.cell);ctx.lineTo(t.ox+pw*t.cell,t.oy+y*t.cell);ctx.stroke();}}
    if(this.anchor){const a=this.anchor,b=this.last||a;ctx.strokeStyle='#fff';ctx.setLineDash([4,3]);ctx.strokeRect(t.ox+Math.min(a.x,b.x)*t.cell,t.oy+(ph-1-Math.max(a.y,b.y))*t.cell,(Math.abs(a.x-b.x)+1)*t.cell,(Math.abs(a.y-b.y)+1)*t.cell);ctx.setLineDash([]);}
  }
  valid(p){const d=this.dims();return p.x>=0&&p.y>=0&&p.x<d.w&&p.y<d.h;}
  applyPixel(p, value){if(!this.valid(p))return;const s=this.store.get();const key=pixelKey(p.x,p.y);const base=(s.quantizedPixels||[]).find(q=>q[0]===p.x&&q[1]===p.y)?.[2];if(value===base && !(key in (s.overrides||{})))return;s.overrides=s.overrides||{};s.overrides[key]=value;}
  stroke(p){const tool=this.tool;if(tool==='picker'){const val=this.pixelMap().get(pixelKey(p.x,p.y));if(val!==undefined){this.paletteIndex=val;this.onChange?.();}return;}if(tool==='fill'){this.flood(p);return;}if(tool==='rect'||tool==='line'||tool==='select'){this.last=p;this.draw();return;}this.applyPixel(p,tool==='eraser'?null:this.paletteIndex);this.inspector?.({type:'pixel',x:p.x,y:p.y,paletteIndex:this.pixelMap().get(pixelKey(p.x,p.y))});this.onChange?.();}
  flood(start){const map=this.pixelMap(), old=map.get(pixelKey(start.x,start.y));if(old===this.paletteIndex)return;const q=[start], seen=new Set();while(q.length){const p=q.shift(),k=pixelKey(p.x,p.y);if(seen.has(k)||!this.valid(p)||map.get(k)!==old)continue;seen.add(k);this.applyPixel(p,this.paletteIndex);q.push({x:p.x+1,y:p.y},{x:p.x-1,y:p.y},{x:p.x,y:p.y+1},{x:p.x,y:p.y-1});}this.onChange?.();}
  down(e){if(e.button!==0)return;e.currentTarget.setPointerCapture?.(e.pointerId);const p=this.point(e);this.dragging=true;this.anchor=p;this.last=p;this.strokeBefore=this.history.snapshot();this.stroke(p);this.draw();}
  move(e){if(!this.dragging)return;const p=this.point(e);if(!this.valid(p)||p.x===this.last?.x&&p.y===this.last?.y)return;if(this.tool==='brush'||this.tool==='eraser'){const from=this.last;const n=Math.max(Math.abs(p.x-from.x),Math.abs(p.y-from.y));for(let i=1;i<=n;i++)this.stroke({x:Math.round(from.x+(p.x-from.x)*i/n),y:Math.round(from.y+(p.y-from.y)*i/n)});}else this.last=p;this.draw();}
  up(){if(!this.dragging)return;this.dragging=false;const a=this.anchor,b=this.last||a;if(this.tool==='rect'){for(let y=Math.min(a.y,b.y);y<=Math.max(a.y,b.y);y++)for(let x=Math.min(a.x,b.x);x<=Math.max(a.x,b.x);x++)this.applyPixel({x,y},this.paletteIndex);this.history.record('矩形像素',this.strokeBefore);this.onChange?.();}else if(this.tool==='select'){const selected=[];for(let y=Math.min(a.y,b.y);y<=Math.max(a.y,b.y);y++)for(let x=Math.min(a.x,b.x);x<=Math.max(a.x,b.x);x++)selected.push(pixelKey(x,y));this.store.get().selectedPixels=selected;this.inspector?.({type:'selection',count:selected.length});}else if(this.tool==='line'){const n=Math.max(Math.abs(b.x-a.x),Math.abs(b.y-a.y));for(let i=0;i<=n;i++)this.applyPixel({x:Math.round(a.x+(b.x-a.x)*i/n),y:Math.round(a.y+(b.y-a.y)*i/n)},this.paletteIndex);this.history.record('线条像素',this.strokeBefore);this.onChange?.();}else if(this.tool!=='picker'){this.history.record(`像素${this.tool}`,this.strokeBefore);this.onChange?.();}this.anchor=null;this.last=null;this.draw();}
}
