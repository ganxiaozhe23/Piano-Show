export const DEFAULT_OPTIONS = {
  resolution: 128, surface: 'wall_north', orientation: 'wall_north', pixelScale: 2,
  visualMode: 'display', timingMode: 'adaptive', canvasGap: 8, canvasLift: 2,
  canvasOffset: [0, 0, 0], imageRotation: 0, motionMode: 'arc', motionGravity: 0.04,
  motionDrag: 0.98, motionArcHeight: 1.5,
  keyboardDepth: 4, backingThickness: 1, borderThickness: 2, baseSpawnPerTick: 32,
  targetLeadTicks: 28, maxSpawnPerTick: 64, maxCommitPerTick: 512,
  maxActiveDisplays: 512, flightTicksMin: 12, flightTicksMax: 36,
};

export function emptyProject(name = '新工程') {
  return {
    project: {formatVersion: 1, projectId: crypto.randomUUID?.() || String(Date.now()), name,
      compileOptions: {...DEFAULT_OPTIONS}},
    midiBase64: '', imageBase64: '', events: [], eventsOverride: false, overrides: {}, history: [],
    quantizedPixels: [], palette: [], selectedPixels: [],
  };
}

export function createStore(initial = null) {
  let value = initial || emptyProject();
  const listeners = new Set();
  let dirty = false;
  function emit() { listeners.forEach(fn => fn(value, dirty)); }
  return {
    get: () => value,
    set(next, markDirty = true) { value = next; dirty = markDirty; emit(); },
    update(fn, markDirty = true) { value = fn(value) || value; dirty = markDirty; emit(); },
    subscribe(fn) { listeners.add(fn); return () => listeners.delete(fn); },
    isDirty: () => dirty,
    markSaved() { dirty = false; emit(); },
    markDirty() { dirty = true; emit(); },
  };
}

export function clone(value) {
  return typeof structuredClone === 'function' ? structuredClone(value) : JSON.parse(JSON.stringify(value));
}

export function optionsOf(state) {
  return {...DEFAULT_OPTIONS, ...(state?.project?.compileOptions || {})};
}

export function projectPayload(state) {
  const payload = clone(state);
  delete payload.quantizedPixels; delete payload.palette; delete payload.selectedPixels;
  // Legacy .pwork files may not contain working/events.json. Omit the field so
  // the server compiles from the embedded MIDI; an explicit [] remains a real
  // editor override after the user deletes all notes.
  if (state.eventsOverride !== true) delete payload.events;
  delete payload.eventsOverride;
  return payload;
}

export function pixelKey(x, y) { return `${x},${y}`; }

export function maxTick(state) {
  return Math.max(1, ...(state.events || []).map(e => Number(e.tick || 0) + Number(e.durationTicks || 1)), 120);
}
