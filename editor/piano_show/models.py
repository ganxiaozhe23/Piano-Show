from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class NoteEvent:
    """A note-on event in server ticks."""

    tick: int
    note: int
    velocity: int
    duration_ticks: int = 1
    track: int = 0
    channel: int = 0
    order: int = 0


@dataclass(frozen=True)
class Pixel:
    """One visible image pixel in queue order."""

    x: int
    y: int
    palette_index: int
    queue_index: int


@dataclass
class CompileOptions:
    resolution: int = 128
    note_min: int = 21
    note_max: int = 108
    tempo_scale: float = 1.0
    palette_id: str = "minecraft_32"
    transparent_threshold: int = 8
    dither: bool = False
    origin: tuple[int, int, int] = (0, 64, 0)
    orientation: str = "wall_north"
    surface: str = "wall_north"
    canvas_gap: int = 8
    canvas_offset: tuple[int, int, int] = (0, 0, 0)
    keyboard_depth: int = 4
    backing_block: str = "minecraft:black_concrete"
    border_block: str = "minecraft:gray_concrete"
    random_seed: int = 0
    min_flying_seconds: float = 0.35
    max_flying_seconds: float = 1.25
    max_active_displays: int = 512
    max_active_falling_blocks: int = 512
    pixel_scale: int = 2
    visual_mode: str = "display"
    timing_mode: str = "adaptive"
    canvas_lift: int = 2
    image_rotation: int = 0
    motion_mode: str = "arc"
    motion_gravity: float = 0.04
    motion_drag: float = 0.98
    motion_arc_height: float = 1.5
    backing_thickness: int = 1
    border_thickness: int = 2
    base_spawn_per_tick: int = 32
    target_lead_ticks: int = 28
    max_spawn_per_tick: int = 64
    max_commit_per_tick: int = 512
    spark_burst_min: int = 4
    spark_burst_max: int = 12
    scatter_radius: float = 2.5
    flight_ticks_min: int = 12
    flight_ticks_max: int = 36
    snap_ticks: int = 5
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass
class CompiledShow:
    manifest: dict[str, Any]
    events: list[NoteEvent]
    pixels: list[Pixel]
    palette: list[dict[str, Any]]
    preview_png: bytes
    layout: dict[str, Any]
