# Fabric runtime

This module targets Minecraft 1.21.1, Java 21 and Fabric API. The server looks for packages in `config/piano-shows/`.

Commands (permission level 2):

```text
/piano load <file.pshow>
/piano build <x> <y> <z>
/piano preview
/piano play [speed]
/piano pause
/piano stop
/piano seek <serverTick>
/piano preview
/piano clear
/piano restore
/piano debug falling
/piano debug perf
/piano debug target <queueIndex>
/piano debug clear_entities
```

The runtime keeps the server authoritative. v2 packages use client-interpolated `BlockDisplayEntity` payloads by default, while `visualMode=physical` and legacy v1 packages retain the `FallingBlockEntity` fallback. Adaptive timing uses note density and queue backlog to keep the show responsive; if any payload disappears early, it is queued for an exact-position commit so the final image is not lost. `/piano restore` restores blocks overwritten during the current loaded show.

`/piano build` creates a complete 88-key black/white keyboard, a scaled backing canvas and a border. New packages default to a vertical `wall_north` canvas with `pixelScale=2`; use `/piano preview` after the stage has finished building to inspect logical/physical dimensions and queue state.
