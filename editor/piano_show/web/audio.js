export class AudioEngine {
  constructor(){this.ctx=null;this.master=null;this.muted=false;this.volume=.12;this.scheduled=new Map();}
  ensure(){if(this.ctx)return;const C=window.AudioContext||window.webkitAudioContext;if(!C)return;this.ctx=new C();this.master=this.ctx.createGain();this.master.gain.value=this.volume;this.master.connect(this.ctx.destination);}
  setMuted(v){this.muted=v;if(this.master)this.master.gain.value=v?0:this.volume;}
  setVolume(v){this.volume=Number(v);if(this.master&&!this.muted)this.master.gain.value=this.volume;}
  note(event,startTick,speed=1){this.ensure();if(!this.ctx||this.muted)return;const now=this.ctx.currentTime;const delay=Math.max(0,(Number(event.tick)-startTick)/20/speed);const duration=Math.max(.03,Number(event.durationTicks||1)/20/speed);const osc=this.ctx.createOscillator(),gain=this.ctx.createGain();osc.type='triangle';osc.frequency.value=440*Math.pow(2,(Number(event.note)-69)/12);gain.gain.setValueAtTime(0,now+delay);gain.gain.linearRampToValueAtTime(Math.max(.01,Number(event.velocity||80)/127*.35),now+delay+.008);gain.gain.exponentialRampToValueAtTime(.001,now+delay+duration);osc.connect(gain);gain.connect(this.master);osc.start(now+delay);osc.stop(now+delay+duration+.02);}
  play(events,start,end,speed=1){this.ensure();if(this.ctx?.state==='suspended')this.ctx.resume();for(const e of events||[])if(e.tick>=start&&e.tick<=end)this.note(e,start,speed);}
  stop(){for(const node of this.scheduled.values())try{node.stop()}catch(_){}this.scheduled.clear();}
}
