async function responseOrError(response) {
  if (response.ok) return response;
  let body = {}; try { body = await response.json(); } catch (_) { /* ignore */ }
  throw new Error(body.error || `请求失败 (${response.status})`);
}
export async function openProject(file) { const fd = new FormData(); fd.append('project', file); return (await responseOrError(await fetch('/api/project/open', {method:'POST', body:fd}))).json(); }
export async function analyzeMidi(base64) { return (await responseOrError(await fetch('/api/project/analyze-midi', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({midiBase64:base64})}))).json(); }
export async function palettes() { return (await responseOrError(await fetch('/api/palettes'))).json(); }
export async function compilePreview(state) { return (await responseOrError(await fetch('/api/compile/preview', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(state)}))).json(); }
export async function compileDownload(state) { return responseOrError(await fetch('/api/compile', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(state)})); }
export async function saveProject(state) { return responseOrError(await fetch('/api/project/save', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(state)})); }
export async function launchDebug(state) { const r = await fetch('/api/debug/launch', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(state)}); let body={}; try {body=await r.json();} catch (_) {} if (!r.ok) throw new Error(body.error || '启动调试失败'); return body; }
export async function debugStatus() { return (await fetch('/api/debug/status')).json(); }
export async function debugLogs(cursor) { return (await fetch(`/api/debug/logs?after=${cursor}`)).json(); }
export async function stopDebug() { return (await fetch('/api/debug/stop', {method:'POST'})).json(); }
