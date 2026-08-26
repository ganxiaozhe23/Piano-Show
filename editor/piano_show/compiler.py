from __future__ import annotations

import hashlib
import json
import tempfile
from pathlib import Path

from .image import compile_image, render_preview_png
from .midi import event_summary, read_midi
from .models import CompileOptions, CompiledShow, NoteEvent, Pixel
from .package import spatial_permutation, write_show
from .palette import load_palette, palette_json
from .project import events_from_records


def _input_hash(midi_path: Path, image_path: Path, options: CompileOptions, palette_data: list[dict[str, object]]) -> str:
    digest = hashlib.sha256()
    digest.update(midi_path.read_bytes())
    digest.update(image_path.read_bytes())
    digest.update(json.dumps(options.__dict__, sort_keys=True, default=list).encode("utf-8"))
    digest.update(json.dumps(palette_data, sort_keys=True).encode("utf-8"))
    return digest.hexdigest()


def compile_show(
    midi_path: str | Path,
    image_path: str | Path,
    options: CompileOptions,
    palette_path: str | Path | None = None,
    *,
    events_override: list[NoteEvent] | None = None,
    pixel_overrides: dict[str, int | None] | None = None,
) -> CompiledShow:
    midi_path = Path(midi_path)
    image_path = Path(image_path)
    palette = load_palette(palette_path)
    events = events_override if events_override is not None else read_midi(midi_path, tempo_scale=options.tempo_scale)
    if not events:
        raise ValueError("MIDI contains no playable note events")
    if options.pixel_scale not in (1, 2, 3):
        raise ValueError("pixel_scale must be 1, 2 or 3")
    if options.visual_mode not in ("display", "physical"):
        raise ValueError("visual_mode must be display or physical")
    if options.timing_mode not in ("adaptive", "fixed"):
        raise ValueError("timing_mode must be adaptive or fixed")
    pixels, preview_png, width, height = compile_image(
        image_path,
        options.resolution,
        palette,
        transparent_threshold=options.transparent_threshold,
        dither=options.dither,
    )
    if pixel_overrides:
        by_coord = {(pixel.x, pixel.y): pixel for pixel in pixels}
        next_queue = max((pixel.queue_index for pixel in pixels), default=-1) + 1
        for key, palette_index in pixel_overrides.items():
            try:
                x_text, y_text = str(key).split(",", 1)
                x, y = int(x_text), int(y_text)
            except (TypeError, ValueError) as error:
                raise ValueError(f"invalid pixel override key: {key}") from error
            if not (0 <= x < width and 0 <= y < height):
                raise ValueError(f"pixel override outside image: {key}")
            existing = by_coord.get((x, y))
            if palette_index is None:
                by_coord.pop((x, y), None)
            else:
                palette_index = int(palette_index)
                if not 0 <= palette_index < len(palette):
                    raise ValueError(f"invalid palette index for pixel override: {key}")
                by_coord[(x, y)] = Pixel(x, y, palette_index, existing.queue_index if existing else next_queue)
                if existing is None:
                    next_queue += 1
        pixels = list(by_coord.values())
        preview_png = render_preview_png(width, height, pixels, palette)
    palette_data = palette_json(palette)
    input_hash = _input_hash(midi_path, image_path, options, palette_data)
    if events_override is not None:
        input_hash = hashlib.sha256((input_hash + json.dumps([event.__dict__ for event in events], sort_keys=True)).encode()).hexdigest()
    if pixel_overrides:
        input_hash = hashlib.sha256((input_hash + json.dumps(pixel_overrides, sort_keys=True)).encode()).hexdigest()
    # Keep the deterministic default within Java's signed long range.
    seed = options.random_seed or (int(input_hash[:16], 16) & ((1 << 63) - 1))
    pixels = spatial_permutation(pixels, seed)
    manifest = {
        "formatVersion": 2,
        "targetMinecraftVersion": "1.21.x",
        "showId": input_hash[:16],
        "inputSha256": input_hash,
        "imageWidth": width,
        "imageHeight": height,
        "logicalWidth": width,
        "logicalHeight": height,
        "physicalWidth": width * options.pixel_scale,
        "physicalHeight": height * options.pixel_scale,
        "estimatedBlockCount": len(pixels) * options.pixel_scale * options.pixel_scale,
        "pixelScale": options.pixel_scale,
        "eventCount": len(events),
        "pixelCount": len(pixels),
        "allocation": "deterministic_random_spatial",
        "revealOrder": "seeded_pixel_permutation",
        "tempoScale": options.tempo_scale,
        "paletteId": options.palette_id,
        "surface": options.surface,
        "visualMode": options.visual_mode,
        "timingMode": options.timing_mode,
        "canvasLift": options.canvas_lift,
        "noteMin": options.note_min,
        "noteMax": options.note_max,
        "origin": list(options.origin),
        "orientation": options.orientation,
        "randomSeed": seed,
        "effectLimits": {
            "maxActiveDisplays": options.max_active_displays,
            "maxActiveFallingBlocks": options.max_active_falling_blocks,
            "baseSpawnPerTick": options.base_spawn_per_tick,
            "targetLeadTicks": options.target_lead_ticks,
            "maxSpawnPerTick": options.max_spawn_per_tick,
            "maxCommitPerTick": options.max_commit_per_tick,
            "sparkBurstMin": options.spark_burst_min,
            "sparkBurstMax": options.spark_burst_max,
            "scatterRadius": options.scatter_radius,
            "flightTicksMin": options.flight_ticks_min,
            "flightTicksMax": options.flight_ticks_max,
            "snapTicks": options.snap_ticks,
        },
        "eventSummary": event_summary(events),
        "metadata": options.metadata,
    }
    canvas_axes = {
        "floor": ([1, 0, 0], [0, 0, 1], [0, 1, 0]),
        "wall_north": ([1, 0, 0], [0, 1, 0], [0, 0, -1]),
        "wall_south": ([1, 0, 0], [0, 1, 0], [0, 0, 1]),
        "wall_east": ([0, 0, 1], [0, 1, 0], [1, 0, 0]),
        "wall_west": ([0, 0, -1], [0, 1, 0], [-1, 0, 0]),
    }
    axis_x, axis_y, normal = canvas_axes.get(options.surface, canvas_axes["wall_north"])
    layout = {
        "surface": options.surface,
        "keyboard": {
            "noteMin": options.note_min,
            "noteMax": options.note_max,
            "keys": options.note_max - options.note_min + 1,
            "originOffset": [0, 0, 0],
            "depth": options.keyboard_depth,
        },
        "image": {
            "width": width,
            "height": height,
            "origin": list(options.origin),
            "orientation": options.orientation,
        },
        "canvas": {
            "offset": [0, 0, options.canvas_gap],
            "pixelY": 1,
            "pixelScale": options.pixel_scale,
            "canvasLift": options.canvas_lift,
            "backingThickness": options.backing_thickness,
            "borderThickness": options.border_thickness,
            "surface": options.surface,
            "axisX": axis_x,
            "axisY": axis_y,
            "normal": normal,
            "anchor": [options.origin[0], options.origin[1] + options.canvas_lift, options.origin[2]],
            "backingBlock": options.backing_block,
            "borderBlock": options.border_block,
        },
    }
    return CompiledShow(manifest, events, pixels, palette_data, preview_png, layout)


def compile_to_file(midi_path: str | Path, image_path: str | Path, output_path: str | Path, options: CompileOptions, palette_path: str | Path | None = None, *, events_override: list[NoteEvent] | None = None, pixel_overrides: dict[str, int | None] | None = None) -> CompiledShow:
    compiled = compile_show(midi_path, image_path, options, palette_path, events_override=events_override, pixel_overrides=pixel_overrides)
    palette = load_palette(palette_path)
    write_show(output_path, compiled.manifest, compiled.events, compiled.pixels, palette, compiled.preview_png, compiled.layout)
    return compiled


def options_from_project(project: dict[str, object]) -> CompileOptions:
    raw = project.get("compileOptions", {})
    if not isinstance(raw, dict):
        raise ValueError("project.compileOptions must be an object")
    aliases = {
        "pixelScale": "pixel_scale", "visualMode": "visual_mode", "timingMode": "timing_mode",
        "canvasGap": "canvas_gap", "canvasLift": "canvas_lift", "keyboardDepth": "keyboard_depth",
        "paletteId": "palette_id", "transparentThreshold": "transparent_threshold", "randomSeed": "random_seed",
        "maxActiveDisplays": "max_active_displays", "baseSpawnPerTick": "base_spawn_per_tick",
        "maxSpawnPerTick": "max_spawn_per_tick", "maxCommitPerTick": "max_commit_per_tick",
        "targetLeadTicks": "target_lead_ticks", "flightTicksMin": "flight_ticks_min", "flightTicksMax": "flight_ticks_max",
        "noteMin": "note_min", "noteMax": "note_max", "surface": "surface", "orientation": "orientation",
        "resolution": "resolution", "dither": "dither",
    }
    values = {aliases[key]: value for key, value in raw.items() if key in aliases}
    if "surface" in values and "orientation" not in values:
        values["orientation"] = values["surface"]
    if "orientation" in values and "surface" not in values:
        values["surface"] = values["orientation"]
    return CompileOptions(**values)


def compile_project_state(state: dict[str, object], output_path: str | Path) -> CompiledShow:
    project = state.get("project")
    if not isinstance(project, dict):
        raise ValueError("project state is missing project metadata")
    options = options_from_project(project)
    records = state.get("events", [])
    events = events_from_records(records) if isinstance(records, list) and records else None
    overrides = state.get("overrides", {})
    if not isinstance(overrides, dict):
        raise ValueError("project overrides must be an object")
    with tempfile.TemporaryDirectory(prefix="piano-show-project-") as directory:
        midi_path = Path(directory) / "source.mid"
        image_path = Path(directory) / "source.png"
        midi_path.write_bytes(state.get("midi", b""))
        image_path.write_bytes(state.get("image", b""))
        return compile_to_file(midi_path, image_path, output_path, options, events_override=events, pixel_overrides=overrides)
