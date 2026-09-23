from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

from .compiler import compile_to_file
from .models import CompileOptions
from .package import reorder_show


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="piano-show", description="Compile MIDI and images for the Minecraft Piano Show Mod")
    sub = parser.add_subparsers(dest="command", required=True)
    import_parser = sub.add_parser("import", help="compile a MIDI file and image into a .pshow package")
    import_parser.add_argument("--midi", required=True, type=Path)
    import_parser.add_argument("--image", required=True, type=Path)
    import_parser.add_argument("--out", required=True, type=Path)
    import_parser.add_argument("--resolution", type=int, default=128)
    import_parser.add_argument("--palette", type=Path)
    import_parser.add_argument("--tempo-scale", type=float, default=1.0)
    import_parser.add_argument("--note-min", type=int, default=21)
    import_parser.add_argument("--note-max", type=int, default=108)
    import_parser.add_argument("--origin", nargs=3, type=int, default=(0, 64, 0), metavar=("X", "Y", "Z"))
    import_parser.add_argument("--orientation", default="wall_north", choices=("wall_north", "wall_south", "wall_east", "wall_west", "floor"))
    import_parser.add_argument("--surface", choices=("wall_north", "wall_south", "wall_east", "wall_west", "floor"), help="v2 canvas surface (overrides --orientation)")
    import_parser.add_argument("--canvas-gap", type=int, default=8)
    import_parser.add_argument("--canvas-offset", nargs=3, type=int, default=(0, 0, 0), metavar=("X", "Y", "Z"))
    import_parser.add_argument("--image-rotation", type=int, choices=(0, 90, 180, 270), default=0)
    import_parser.add_argument("--motion-mode", choices=("arc", "ballistic", "vanilla"), default="arc")
    import_parser.add_argument("--motion-gravity", type=float, default=0.04)
    import_parser.add_argument("--motion-drag", type=float, default=0.98)
    import_parser.add_argument("--motion-arc-height", type=float, default=1.5, help="arc height in blocks; any finite non-negative value")
    import_parser.add_argument("--keyboard-depth", type=int, default=4)
    import_parser.add_argument("--seed", type=int, default=0)
    import_parser.add_argument("--max-active-falling-blocks", type=int, default=512)
    import_parser.add_argument("--pixel-scale", type=int, choices=(1, 2, 3), default=2)
    import_parser.add_argument("--visual-mode", choices=("display", "physical"), default="display")
    import_parser.add_argument("--timing-mode", choices=("adaptive", "fixed"), default="adaptive")
    import_parser.add_argument("--canvas-lift", type=int, default=2)
    import_parser.add_argument("--backing-thickness", type=int, default=1)
    import_parser.add_argument("--border-thickness", type=int, default=2)
    import_parser.add_argument("--base-spawn-per-tick", type=int, default=32)
    import_parser.add_argument("--target-lead-ticks", type=int, default=28)
    import_parser.add_argument("--max-spawn-per-tick", type=int, default=64)
    import_parser.add_argument("--max-commit-per-tick", type=int, default=512)
    import_parser.add_argument("--spark-burst-min", type=int, default=4)
    import_parser.add_argument("--spark-burst-max", type=int, default=12)
    import_parser.add_argument("--scatter-radius", type=float, default=2.5)
    import_parser.add_argument("--flight-ticks-min", type=int, default=12)
    import_parser.add_argument("--flight-ticks-max", type=int, default=36)
    import_parser.add_argument("--snap-ticks", type=int, default=5)
    import_parser.add_argument("--dither", action="store_true")
    import_parser.add_argument("--transparent-threshold", type=int, default=8)
    sub.add_parser("gui", help="open the optional PySide6 desktop editor")
    editor_parser = sub.add_parser("editor", help="open the local web editor and Minecraft debug controls")
    editor_parser.add_argument("--host", default="127.0.0.1")
    editor_parser.add_argument("--port", type=int, default=0)
    editor_parser.add_argument("--no-open", action="store_true")
    preview_parser = sub.add_parser("preview", help="serve the standalone web preview")
    preview_parser.add_argument("--show", required=True, type=Path)
    preview_parser.add_argument("--host", default="127.0.0.1")
    preview_parser.add_argument("--port", type=int, default=0)
    preview_parser.add_argument("--open", action="store_true")
    debug_parser = sub.add_parser("debug", help="compile a .pwork and launch the Fabric development client")
    debug_parser.add_argument("--project", required=True, type=Path)
    project_parser = sub.add_parser("project", help="create a portable .pwork project from MIDI and image files")
    project_parser.add_argument("--midi", required=True, type=Path)
    project_parser.add_argument("--image", required=True, type=Path)
    project_parser.add_argument("--out", required=True, type=Path)
    project_parser.add_argument("--name", default="show")
    reorder_parser = sub.add_parser("reorder", help="rewrite an existing .pshow with spatial reveal order")
    reorder_parser.add_argument("--input", required=True, type=Path)
    reorder_parser.add_argument("--out", required=True, type=Path)
    reorder_parser.add_argument("--max-active-falling-blocks", type=int)
    reorder_parser.add_argument("--max-spawn-per-tick", type=int)
    reorder_parser.add_argument("--max-commit-per-tick", type=int)
    reorder_parser.add_argument("--flight-ticks-min", type=int)
    reorder_parser.add_argument("--flight-ticks-max", type=int)
    reorder_parser.add_argument("--snap-ticks", type=int)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    if args.command == "import":
        surface = args.surface or args.orientation
        options = CompileOptions(
            resolution=args.resolution,
            note_min=args.note_min,
            note_max=args.note_max,
            tempo_scale=args.tempo_scale,
            palette_id=args.palette.stem if args.palette else "minecraft_32",
            origin=tuple(args.origin),
            orientation=surface,
            surface=surface,
            canvas_gap=args.canvas_gap,
            canvas_offset=tuple(args.canvas_offset),
            image_rotation=args.image_rotation,
            motion_mode=args.motion_mode,
            motion_gravity=args.motion_gravity,
            motion_drag=args.motion_drag,
            motion_arc_height=args.motion_arc_height,
            keyboard_depth=args.keyboard_depth,
            pixel_scale=args.pixel_scale,
            visual_mode=args.visual_mode,
            timing_mode=args.timing_mode,
            canvas_lift=args.canvas_lift,
            backing_thickness=args.backing_thickness,
            border_thickness=args.border_thickness,
            base_spawn_per_tick=args.base_spawn_per_tick,
            target_lead_ticks=args.target_lead_ticks,
            random_seed=args.seed,
            dither=args.dither,
            transparent_threshold=args.transparent_threshold,
            max_active_falling_blocks=args.max_active_falling_blocks,
            max_spawn_per_tick=args.max_spawn_per_tick,
            max_commit_per_tick=args.max_commit_per_tick,
            spark_burst_min=args.spark_burst_min,
            spark_burst_max=args.spark_burst_max,
            scatter_radius=args.scatter_radius,
            flight_ticks_min=args.flight_ticks_min,
            flight_ticks_max=args.flight_ticks_max,
            snap_ticks=args.snap_ticks,
        )
        compiled = compile_to_file(args.midi, args.image, args.out, options, args.palette)
        print(f"wrote {args.out} ({len(compiled.events)} events, {len(compiled.pixels)} visible pixels)")
        return 0
    if args.command == "gui":
        from .gui import run
        return run()
    if args.command == "editor":
        from .editor_server import run_editor
        return run_editor(host=args.host, port=args.port, open_browser=not args.no_open)
    if args.command == "preview":
        from .web_preview import run_server
        return run_server(args.show, host=args.host, port=args.port, open_browser=args.open)
    if args.command == "debug":
        from .debug_runner import get_debug_runner
        runner = get_debug_runner()
        result = runner.launch(args.project.read_bytes())
        print(json.dumps(result, ensure_ascii=False, indent=2))
        if result.get("status") == "failed":
            return 1
        print("游戏内命令：")
        for command in result.get("instructions", []):
            print(command)
        cursor = 0
        try:
            while runner.status().get("status") in {"starting", "running"}:
                payload = runner.logs(cursor)
                cursor = payload["cursor"]
                for item in payload["lines"]:
                    print(item)
                time.sleep(0.5)
        except KeyboardInterrupt:
            runner.stop()
        return 0
    if args.command == "project":
        from .midi import read_midi
        from .project import default_project, event_records, write_project
        events = event_records(read_midi(args.midi))
        write_project(args.out, default_project(name=args.name), args.midi.read_bytes(), args.image.read_bytes(), events=events)
        print(f"wrote {args.out} (.pwork, {len(events)} events)")
        return 0
    if args.command == "reorder":
        overrides = {
            key: value for key, value in {
                "maxActiveFallingBlocks": args.max_active_falling_blocks,
                "maxSpawnPerTick": args.max_spawn_per_tick,
                "maxCommitPerTick": args.max_commit_per_tick,
                "flightTicksMin": args.flight_ticks_min,
                "flightTicksMax": args.flight_ticks_max,
                "snapTicks": args.snap_ticks,
            }.items() if value is not None
        }
        reorder_show(args.input, args.out, effect_limits=overrides)
        print(f"wrote {args.out} with deterministic spatial reveal order")
        return 0
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
