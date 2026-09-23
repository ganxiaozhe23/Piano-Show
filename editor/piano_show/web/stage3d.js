import {optionsOf} from './state.js';

export const BLACK_PITCH_CLASSES = new Set([1, 3, 6, 8, 10]);

export function isBlackNote(note) {
  return BLACK_PITCH_CLASSES.has(Math.floor(Number(note)) % 12);
}

export function whiteIndexFor(note, noteMin = 21) {
  let index = 0;
  for (let candidate = Number(noteMin); candidate < Number(note); candidate++) {
    if (!isBlackNote(candidate)) index++;
  }
  return index;
}

export function keyGeometry(options, note) {
  const origin = Array.isArray(options.origin) ? options.origin.map(Number) : [0, 64, 0];
  const keyboardDepth = Math.max(1, Number(options.keyboardDepth || 4));
  const black = isBlackNote(note);
  const x = origin[0] + whiteIndexFor(note, options.noteMin || 21) * 2 + (black ? 1 : 0);
  const y = origin[1] + (black ? 1 : 0);
  const keyDepth = Math.min(1, Math.max(0, keyboardDepth - 1));
  const z = origin[2] + (black ? keyDepth : 0);
  return {
    block: [x, y, z],
    width: black ? 1 : 2,
    height: black ? 10 / 16 : 4 / 16,
    depth: black ? Math.max(1, keyboardDepth - 2) : keyboardDepth,
    launch: [x + (black ? 0.5 : 1), y + (black ? 1.8 : 1.2), origin[2] + keyDepth + 0.5],
  };
}

export function pixelEventIndex(pixelIndex, pixelCount, eventCount) {
  if (!pixelCount || !eventCount) return -1;
  // Java allocates [floor(e*P/E), floor((e+1)*P/E)) to event e.  The
  // inverse mapping below is ceil((i+1)*E/P)-1, expressed with integer-safe
  // arithmetic so Web preview and Mod playback agree at bucket boundaries.
  return Math.min(eventCount - 1, Math.floor(((pixelIndex + 1) * eventCount - 1) / pixelCount));
}

export function canvasGeometry(options, width, height) {
  const o = options || {};
  const surface = o.surface || 'wall_north';
  const origin = Array.isArray(o.origin) ? o.origin.map(Number) : [0, 64, 0];
  const offset = Array.isArray(o.canvasOffset) ? o.canvasOffset.map(Number) : [0, 0, 0];
  const scale = Math.max(1, Math.min(3, Number(o.pixelScale || 2)));
  const gap = Number(o.canvasGap || 8);
  const lift = Number(o.canvasLift || 2);
  const keyboardDepth = Math.max(1, Number(o.keyboardDepth || 4));
  let white = 0;
  for (let note = Number(o.noteMin || 21); note <= Number(o.noteMax || 108); note++) {
    if (!isBlackNote(note)) white++;
  }
  const keyboardWidth = white * 2;
  const physicalHeight = Number(height) * scale;
  let anchor;
  if (surface === 'floor') anchor = [origin[0], origin[1] + 1 + lift, origin[2] + keyboardDepth + gap];
  else if (surface === 'wall_south') anchor = [origin[0], origin[1] + lift + physicalHeight - 1, origin[2] + keyboardDepth + gap];
  else if (surface === 'wall_east') anchor = [origin[0] + keyboardWidth + gap, origin[1] + lift + physicalHeight - 1, origin[2]];
  else if (surface === 'wall_west') anchor = [origin[0] - gap, origin[1] + lift + physicalHeight - 1, origin[2]];
  else anchor = [origin[0], origin[1] + lift + physicalHeight - 1, origin[2] - gap];
  anchor = anchor.map((value, index) => value + Number(offset[index] || 0));
  const basis = {
    x: surface === 'wall_east' || surface === 'wall_west' ? [0, 0, 1] : [1, 0, 0],
    y: surface === 'floor' ? [0, 0, 1] : [0, 1, 0],
    normal: surface === 'floor' ? [0, 1, 0] : surface === 'wall_south' ? [0, 0, 1]
      : surface === 'wall_east' ? [1, 0, 0] : surface === 'wall_west' ? [-1, 0, 0] : [0, 0, -1],
    verticalSign: surface === 'floor' ? 1 : -1,
  };
  return {anchor, basis, physicalWidth: Number(width) * scale, physicalHeight, keyboardWidth, keyboardDepth};
}

/** World-space center of a physical pixel, matching PianoLayout.canvasPixelCenter. */
export function canvasPixelCenter(origin, basis, surface, x, y, scale) {
  const down = basis.y.map(v => v * (basis.verticalSign ?? (surface === 'floor' ? 1 : -1)));
  const topLeft = [
    origin[0] + basis.x[0] * x * scale + down[0] * y * scale,
    origin[1] + basis.x[1] * x * scale + down[1] * y * scale,
    origin[2] + basis.x[2] * x * scale + down[2] * y * scale,
  ];
  const half = scale / 2;
  const alongY = surface === 'floor' ? half : half - 0.5;
  const normal = basis.normal;
  return [
    topLeft[0] + basis.x[0] * half + down[0] * alongY + normal[0] * 0.5,
    topLeft[1] + basis.x[1] * half + down[1] * alongY + normal[1] * 0.5,
    topLeft[2] + basis.x[2] * half + down[2] * alongY + normal[2] * 0.5,
  ];
}

export function motionSamples(start, target, durationTicks, options, basis, seed = 0) {
  const duration = Math.max(1, Math.floor(Number(durationTicks) || 1));
  const mode = options.motionMode || 'arc';
  // Keep decorative scatter on the canvas tangent only.  The image-down axis
  // is vertical for wall surfaces, so using it would add an unintended wobble
  // that does not exist in the Minecraft MotionCalculator.
  const axisX = basis.x;
  let state = (Number(seed) >>> 0) || 1;
  const random = () => { state = (1664525 * state + 1013904223) >>> 0; return state / 4294967296; };
  const phase = random() * Math.PI * 2;
  const amplitude = (mode === 'vanilla' ? 0 : Math.max(0, Number(options.scatterRadius ?? 2.5))) * (.35 + random() * .65);
  const samples = [start];
  if (mode === 'ballistic' || mode === 'vanilla') {
    const gravity = Math.max(0, Math.min(1, Number(options.motionGravity ?? .04)));
    const drag = Math.max(0, Math.min(1, Number(options.motionDrag ?? .98)));
    const sum = drag === 1 ? duration : (1 - Math.pow(drag, duration)) / (1 - drag);
    let gravityDisplacement = 0, probeVelocity = 0;
    for (let i=0;i<duration;i++) { gravityDisplacement += probeVelocity; probeVelocity = probeVelocity * drag - gravity; }
    let velocity = [(target[0]-start[0])/sum, (target[1]-start[1]-gravityDisplacement)/sum, (target[2]-start[2])/sum];
    let position = [...start];
    for (let i=1;i<=duration;i++) {
      position = [position[0]+velocity[0], position[1]+velocity[1], position[2]+velocity[2]];
      velocity = [velocity[0]*drag, velocity[1]*drag-gravity, velocity[2]*drag];
      const t=i/duration, env=Math.sin(Math.PI*t);
      const s=Math.sin(phase+t*Math.PI*2)*amplitude*env, s2=Math.cos(phase+t*Math.PI*2)*amplitude*.35*env;
      const tangentOffset=s+s2;
      samples.push(i===duration?[...target]:[position[0]+axisX[0]*tangentOffset,position[1]+axisX[1]*tangentOffset,position[2]+axisX[2]*tangentOffset]);
    }
  } else {
    const rawHeight=Number(options.motionArcHeight ?? 1.5);
    const height=Number.isFinite(rawHeight)?Math.max(0, rawHeight):1.5;
    for (let i=1;i<=duration;i++) { const t=i/duration, env=Math.sin(Math.PI*t), s=Math.sin(phase+t*Math.PI*2)*amplitude*env, s2=Math.cos(phase+t*Math.PI*2)*amplitude*.35*env, tangentOffset=s+s2; samples.push(i===duration?[...target]:[start[0]+(target[0]-start[0])*t+axisX[0]*tangentOffset,start[1]+(target[1]-start[1])*t+height*env+axisX[1]*tangentOffset,start[2]+(target[2]-start[2])*t+axisX[2]*tangentOffset]); }
  }
  return samples;
}

/* A dependency-free stage renderer.  It uses the same transform data as the
   Minecraft layout and intentionally keeps a local Three.js-compatible vendor
   slot for deployments that want to replace this renderer with WebGL. */
export class Stage3D {
  constructor(canvas, store){this.canvas=canvas;this.store=store;this.preview=null;this.three=null;this.yaw=-.35;this.pitch=.25;this.zoom=1;this.drag=null;this.playhead=0;import('./vendor/three.module.js').then(m=>{this.three=m;}).catch(()=>{});window.addEventListener('resize',()=>this.resize());store.subscribe(()=>this.draw());canvas.addEventListener('pointerdown',e=>{this.drag={x:e.clientX,y:e.clientY};canvas.setPointerCapture?.(e.pointerId)});canvas.addEventListener('pointermove',e=>{if(!this.drag)return;this.yaw+=(e.clientX-this.drag.x)*.01;this.pitch+=(e.clientY-this.drag.y)*.01;this.drag={x:e.clientX,y:e.clientY};this.draw()});canvas.addEventListener('pointerup',()=>this.drag=null);canvas.addEventListener('wheel',e=>{e.preventDefault();this.zoom=Math.max(.35,Math.min(3,this.zoom*(e.deltaY<0?1.1:.9)));this.draw()},{passive:false});this.resize();}
  resize(){const d=devicePixelRatio||1;this.canvas.width=Math.max(1,this.canvas.clientWidth*d);this.canvas.height=Math.max(1,this.canvas.clientHeight*d);this.canvas.getContext('2d').setTransform(d,0,0,d,0,0);this.draw();}
  setPreview(data){this.preview=data;this.draw();}
  setPlayhead(t){this.playhead=t;this.draw();}
  project(x,y,z){const c=Math.cos(this.yaw),s=Math.sin(this.yaw),cp=Math.cos(this.pitch),sp=Math.sin(this.pitch);const center=this.sceneCenter||[0,0,0];x-=center[0];y-=center[1];z-=center[2];let xx=x*c-z*s,zz=x*s+z*c;let yy=y*cp-zz*sp, depth=y*sp+zz*cp;const scale=Math.min(this.canvas.clientWidth,this.canvas.clientHeight)/260*this.zoom;return [this.canvas.clientWidth/2+xx*scale,this.canvas.clientHeight*.63-yy*scale,depth];}
  line(ctx,a,b,color,width=1){const p=this.project(...a),q=this.project(...b);ctx.strokeStyle=color;ctx.lineWidth=width;ctx.beginPath();ctx.moveTo(p[0],p[1]);ctx.lineTo(q[0],q[1]);ctx.stroke();}
  box(ctx,x,y,z,w,h,d,color){const pts=[[x,y,z],[x+w,y,z],[x+w,y+h,z],[x,y+h,z],[x,y,z+d],[x+w,y,z+d],[x+w,y+h,z+d],[x,y+h,z+d]];const edges=[[0,1],[1,2],[2,3],[3,0],[4,5],[5,6],[6,7],[7,4],[0,4],[1,5],[2,6],[3,7]];ctx.fillStyle=color;const poly=[0,1,2,3].map(i=>this.project(...pts[i]));ctx.beginPath();poly.forEach((p,i)=>i?ctx.lineTo(p[0],p[1]):ctx.moveTo(p[0],p[1]));ctx.closePath();ctx.fill();for(const [a,b] of edges)this.line(ctx,pts[a],pts[b],'rgba(180,205,245,.23)');}
  add(a,b){return [a[0]+b[0],a[1]+b[1],a[2]+b[2]];}
  mul(a,n){return [a[0]*n,a[1]*n,a[2]*n];}
  orientedBox(ctx,anchor,basis,width,height,depth,color){
    // ``anchor`` is the top-left logical pixel.  The Java layout uses a
    // downward world vector for wall canvases and a forward vector on the
    // floor, so keep that convention in the preview as well.
    const vx=this.mul(basis.x,width), vy=this.mul(basis.y,height*basis.verticalSign), vz=this.mul(basis.normal,depth);
    const p0=anchor,p1=this.add(p0,vx),p3=this.add(p0,vy),p2=this.add(p1,vy);
    const p4=this.add(p0,vz),p5=this.add(p1,vz),p7=this.add(p3,vz),p6=this.add(p2,vz);
    const pts=[p0,p1,p2,p3,p4,p5,p6,p7], edges=[[0,1],[1,2],[2,3],[3,0],[4,5],[5,6],[6,7],[7,4],[0,4],[1,5],[2,6],[3,7]];
    ctx.fillStyle=color;const poly=[p0,p1,p2,p3].map(p=>this.project(...p));ctx.beginPath();poly.forEach((p,i)=>i?ctx.lineTo(p[0],p[1]):ctx.moveTo(p[0],p[1]));ctx.closePath();ctx.fill();
    for(const [a,b] of edges)this.line(ctx,pts[a],pts[b],'rgba(180,205,245,.23)');
  }
  layoutAnchor(width,height){return canvasGeometry(optionsOf(this.store.get()),width,height).anchor;}
  draw(){const ctx=this.canvas.getContext('2d'),w=this.canvas.clientWidth,h=this.canvas.clientHeight;if(!w||!h)return;ctx.clearRect(0,0,w,h);ctx.fillStyle='#070c14';ctx.fillRect(0,0,w,h);const data=this.preview,s=this.store.get(),o=optionsOf(s),man=data?.manifest||{},lay=data?.layout||{},cw=Number(man.logicalWidth||o.resolution||32),ch=Number(man.logicalHeight||o.resolution||32),ps=Number(lay.canvas?.pixelScale||man.pixelScale||o.pixelScale||2), pixels=data?.pixels||[];
    const geometry=canvasGeometry(o,cw,ch);
    const computedOrigin=geometry.anchor;
    // A compiled layout carries the exact world anchor. Use it when it still
    // describes the current editor options; while options are being edited,
    // fall back to the live calculation so the preview moves immediately.
    const compiledAnchor=Array.isArray(lay.canvas?.anchor)?lay.canvas.anchor.map(Number):null;
    const compiledOffset=Array.isArray(lay.canvas?.positionOffset)?lay.canvas.positionOffset.map(Number):null;
    const currentOffset=Array.isArray(o.canvasOffset)?o.canvasOffset.map(Number):[0,0,0];
    const sameOffset=compiledOffset&&compiledOffset.length>=3&&compiledOffset.every((v,i)=>v===currentOffset[i]);
    const sameSurface=!lay.canvas?.surface||lay.canvas.surface===o.surface;
    const sameScale=!lay.canvas?.pixelScale||Number(lay.canvas.pixelScale)===ps;
    const layoutOrigin=Array.isArray(lay.image?.origin)?lay.image.origin.map(Number)
      :(Array.isArray(man.origin)?man.origin.map(Number):null);
    const currentOrigin=Array.isArray(o.origin)?o.origin.map(Number):[0,64,0];
    const sameOrigin=!layoutOrigin||layoutOrigin.length<3||layoutOrigin.every((v,i)=>v===currentOrigin[i]);
    const compiledGap=Array.isArray(lay.canvas?.offset)?Number(lay.canvas.offset[2]):null;
    const sameGap=compiledGap===null||compiledGap===Number(o.canvasGap||8);
    const sameLift=lay.canvas?.canvasLift === undefined || Number(lay.canvas.canvasLift)===Number(o.canvasLift||2);
    const sameResolution=!man.logicalWidth||Number(man.logicalWidth)===cw;
    const origin=compiledAnchor&&sameOffset&&sameSurface&&sameScale&&sameOrigin&&sameGap&&sameLift&&sameResolution?compiledAnchor:computedOrigin;
    // World coordinates must match PianoLayout exactly: one logical pixel
    // expands to pixelScale blocks in both axes.  The camera projection does
    // the visual fitting, so do not halve this value here.
    const scale=ps, surface=o.surface||lay.canvas?.surface||'wall_north';
    const basis=geometry.basis;
    const physicalWidth=cw*scale, physicalHeight=ch*scale;
    const keyboardWidth=geometry.keyboardWidth, keyboardDepth=geometry.keyboardDepth, keyboardOrigin=Array.isArray(o.origin)?o.origin:[0,64,0];
    // Keep world-coordinate layout identical to PianoLayout, then recenter the
    // camera around both the keyboard and canvas. This avoids the old preview
    // disappearing off-screen when the default wall anchor is around y=321.
    const vx=this.mul(basis.x,physicalWidth), vy=this.mul(basis.y,physicalHeight*basis.verticalSign);
    const canvasCorners=[origin,this.add(origin,vx),this.add(origin,vy),this.add(this.add(origin,vx),vy)];
    const keyboardCorners=[[keyboardOrigin[0],keyboardOrigin[1]-1,keyboardOrigin[2]],[keyboardOrigin[0]+keyboardWidth,keyboardOrigin[1]+1.625,keyboardOrigin[2]+keyboardDepth]];
    const all=canvasCorners.concat(keyboardCorners), bounds=[0,1,2].map(i=>{const vals=all.map(p=>p[i]);return [Math.min(...vals),Math.max(...vals)];});
    this.sceneCenter=bounds.map(pair=>(pair[0]+pair[1])/2);
    // floor grid
    const gridY=keyboardOrigin[1]-1;for(let i=-120;i<=120;i+=10)this.line(ctx,[this.sceneCenter[0]+i,gridY,this.sceneCenter[2]-80],[this.sceneCenter[0]+i,gridY,this.sceneCenter[2]+80],'rgba(90,120,160,.12)');for(let i=-80;i<=80;i+=10)this.line(ctx,[this.sceneCenter[0]-120,gridY,this.sceneCenter[2]+i],[this.sceneCenter[0]+120,gridY,this.sceneCenter[2]+i],'rgba(90,120,160,.12)');
    // keyboard: white/black keys aligned to the configured MIDI range
    for(let n=Number(o.noteMin||21);n<=Number(o.noteMax||108);n++){const key=keyGeometry(o,n);this.box(ctx,key.block[0],key.block[1],key.block[2],key.width,key.height,key.depth,isBlackNote(n)?'#28344a':'#e7edf4');}
    this.orientedBox(ctx,origin,basis,cw*scale,ch*scale,.8,'#171d2b');
    const visible=Math.min(pixels.length,Math.floor(Math.max(0,Math.min(1,this.playhead/Math.max(1,this.maxTick())))*pixels.length));for(let i=0;i<visible;i++){const p=pixels[i],col=data.palette?.[p[2]]?.color||[130,140,160];const pos=this.add(this.add(origin,this.mul(basis.x,p[0]*scale)),this.mul(basis.y,p[1]*scale*basis.verticalSign));this.orientedBox(ctx,pos,basis,scale,scale,.9,`rgb(${col[0]},${col[1]},${col[2]})`);}
    this.drawFlights(ctx, pixels, data?.events||s.events||[], data?.palette||s.palette||[], origin, basis, keyboardOrigin, o, scale, surface, cw, ch, Number(man.randomSeed||0));
    if(!data){ctx.fillStyle='#7184a7';ctx.font='14px system-ui';ctx.fillText('点击“编译预览”查看舞台',w/2-90,h/2);}
  }
  drawFlights(ctx,pixels,events,palette,origin,basis,keyboardOrigin,o,scale,surface,cw,ch,randomSeed=0){
    if(!this.playhead||!pixels.length||!events.length)return;
    const maxFlights=96, flightTicks=Math.max(12,Math.min(36,Number(o.targetLeadTicks||28)));
    const mode=o.visualMode||'display'; let drawn=0;
    const lerp=(a,b,t)=>[a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t,a[2]+(b[2]-a[2])*t];
    for(let i=0;i<pixels.length&&drawn<maxFlights;i++){
      const eventIndex=pixelEventIndex(i,pixels.length,events.length);
      const event=eventIndex<0?null:events[eventIndex];
      if(!event)continue;
      const progress=(this.playhead-Number(event.tick||0))/flightTicks;
      if(progress<0||progress>1)continue;
      const p=pixels[i],target=canvasPixelCenter(origin,basis,surface,p[0],p[1],scale);
      const key=keyGeometry({...o,origin:keyboardOrigin},Number(event.note||60));
      const start=key.launch;
      let seed=0;try{seed=Number((BigInt(Math.trunc(randomSeed))^(BigInt(eventIndex)*0x9E3779B97F4A7C15n)^BigInt(i))&0xffffffffn);}catch(_){seed=(eventIndex+1)*8191+i;}
      const samples=motionSamples(start,target,flightTicks,o,basis,seed); const frame=Math.min(samples.length-1,Math.max(0,Math.floor(progress*flightTicks)));
      const current=samples[frame];
      ctx.strokeStyle='rgba(121,219,255,.42)';ctx.lineWidth=1.1;ctx.beginPath();samples.forEach((point,index)=>{const q=this.project(...point);if(index===0)ctx.moveTo(q[0],q[1]);else ctx.lineTo(q[0],q[1]);});ctx.stroke();
      const source=this.project(...start);ctx.fillStyle='rgba(255,215,96,.9)';ctx.beginPath();ctx.arc(source[0],source[1],2.5,0,Math.PI*2);ctx.fill();
      const col=palette[p[2]]?.color||[130,180,220];this.orientedBox(ctx,current,basis,scale,scale,.9,`rgb(${col[0]},${col[1]},${col[2]})`);drawn++;
    }
  }
  maxTick(){return Math.max(1,...(this.store.get().events||[]).map(e=>Number(e.tick||0)+Number(e.durationTicks||1)));}
}
