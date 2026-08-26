# Local editor and compiler

The editor intentionally keeps the `.pshow` format independent from the Minecraft runtime. The compiler is deterministic: the same MIDI, image, palette and options produce the same `inputSha256`, queue order and preview.

Portable `.pwork` projects embed the MIDI and image sources. Create one with `piano-show project`, open the browser editor with `piano-show editor`, and use **启动 Minecraft 调试** to compile the current project and launch Fabric's `runClient`. The editor never sends commands into Minecraft; use the commands shown in the debug panel after the game opens.

```powershell
$py = "C:\Users\2\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
& $py -m pip install -e ".[gui,test]"
& $py -m piano_show import --midi song.mid --image image.png --out show.pshow --resolution 128 --orientation wall_north --pixel-scale 2
# Migrate an existing package when the original MIDI/PNG is unavailable.
& $py -m piano_show reorder --input old.pshow --out show-random.pshow
& $py -m piano_show gui
# Launch the offline browser preview.
& $py -m piano_show preview --show show.pshow --open

# Open the local editor with project saving and Minecraft debug controls.
& $py -m piano_show editor
```

Custom palettes are JSON arrays (or `{ "entries": [...] }`) with `block`, `color: [r,g,b]`, and optional `name` fields. The Mod resolves each `block` identifier through the server registry and falls back to its own magenta block for unknown identifiers.
