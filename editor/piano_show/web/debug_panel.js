import {debugStatus,debugLogs,stopDebug} from './api.js';
export class DebugPanel {
  constructor(els){this.els=els;this.cursor=0;this.timer=null;this.onState=null;}
  render(s){const status=s?.status||'idle';this.els.status.textContent=status+(s?.reused?'（已复用）':'');this.els.status.className='state-pill '+(status==='running'?'good':status==='failed'?'bad':'');this.els.meta.textContent=[s?.pid?`PID: ${s.pid}`:'',s?.showFile?`show: ${s.showFile}`:'',s?.showId?`showId: ${s.showId}`:'',s?.startedAt?`启动: ${new Date(s.startedAt).toLocaleString()}`:'',s?.error?`错误: ${s.error}`:''].filter(Boolean).join('\n');this.els.commands.textContent=(s?.instructions||[]).join('\n');this.els.stop.disabled=!['running','starting'].includes(status);this.onState?.(s);}
  async refresh(){try{const s=await debugStatus();this.render(s);const l=await debugLogs(this.cursor);if(l.lines?.length){this.els.logs.textContent+=(this.els.logs.textContent==='等待启动…'?'':'\n')+l.lines.join('\n');this.els.logs.scrollTop=this.els.logs.scrollHeight;}this.cursor=l.cursor||this.cursor;if(['running','starting'].includes(s.status)){if(!this.timer)this.timer=setInterval(()=>this.refresh(),1000);}else if(this.timer){clearInterval(this.timer);this.timer=null;}}catch(e){this.els.meta.textContent=e.message;}}
  start(s){this.cursor=0;this.els.logs.textContent='等待启动…';this.render(s);this.refresh();}
  async stop(){await stopDebug();await this.refresh();}
}
