import {clone} from './state.js';

export class History {
  constructor(store, limit = 100) { this.store = store; this.limit = limit; this.undoStack = []; this.redoStack = []; }
  snapshot() {
    const state = clone(this.store.get());
    // Compiled pixels/palette are derived data; keeping them in every command
    // would make a 100-step history unnecessarily huge for 512×512 images.
    delete state.quantizedPixels; delete state.palette; delete state.manifest;
    delete state.layout; delete state.previewPngBase64;
    delete state.history;
    return state;
  }
  restore(snapshot) {
    const current = this.store.get();
    return {...snapshot, palette: current.palette, quantizedPixels: current.quantizedPixels,
      manifest: current.manifest, layout: current.layout, previewPngBase64: current.previewPngBase64,
      history: current.history};
  }
  execute(label, mutate) {
    const before = this.snapshot(); mutate(this.store.get());
    const after = this.snapshot();
    this.undoStack.push({label, before, after}); if (this.undoStack.length > this.limit) this.undoStack.shift();
    this.redoStack.length = 0; this.store.markDirty(); this.onChange?.();
  }
  record(label, before) {
    const after = this.snapshot(); this.undoStack.push({label, before, after});
    if (this.undoStack.length > this.limit) this.undoStack.shift(); this.redoStack.length = 0;
    this.store.markDirty(); this.onChange?.();
  }
  undo() { const cmd = this.undoStack.pop(); if (!cmd) return false; this.store.set(this.restore(cmd.before), true); this.redoStack.push(cmd); this.onChange?.(); return true; }
  redo() { const cmd = this.redoStack.pop(); if (!cmd) return false; this.store.set(this.restore(cmd.after), true); this.undoStack.push(cmd); this.onChange?.(); return true; }
  canUndo() { return this.undoStack.length > 0; } canRedo() { return this.redoStack.length > 0; }
  export() { return this.undoStack.slice(-this.limit).map(c => ({label: c.label, before: c.before, after: c.after})); }
  import(records = []) { this.undoStack = records.slice(-this.limit); this.redoStack = []; this.onChange?.(); }
}
