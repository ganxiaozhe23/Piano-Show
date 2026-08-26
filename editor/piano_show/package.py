from __future__ import annotations

from io import BytesIO
import hashlib
import json
import os
from pathlib import Path
import struct
from zipfile import ZIP_DEFLATED, ZipFile

from .models import NoteEvent, Pixel
from .palette import PaletteEntry, palette_json


def _write_varint(value: int) -> bytes:
    if value < 0:
        raise ValueError("varint values must be non-negative")
    result = bytearray()
    while value >= 0x80:
        result.append((value & 0x7F) | 0x80)
        value >>= 7
    result.append(value)
    return bytes(result)


def encode_events(events: list[NoteEvent]) -> bytes:
    out = bytearray(b"PSHOWEV1")
    out.extend(struct.pack(">I", len(events)))
    previous_tick = 0
    for event in events:
        out.extend(_write_varint(event.tick - previous_tick))
        out.extend(bytes((event.note & 0xFF, event.velocity & 0xFF)))
        out.extend(_write_varint(event.duration_ticks))
        out.extend(_write_varint(event.track))
        out.append(event.channel & 0x0F)
        previous_tick = event.tick
    return bytes(out)


def encode_pixels(pixels: list[Pixel]) -> bytes:
    out = bytearray(b"PSHOWPX1")
    out.extend(struct.pack(">I", len(pixels)))
    for pixel in pixels:
        out.extend(struct.pack(">HHHI", pixel.x, pixel.y, pixel.palette_index, pixel.queue_index))
    return bytes(out)


def decode_pixels(data: bytes) -> list[Pixel]:
    if not data.startswith(b"PSHOWPX1"):
        raise ValueError("Invalid pixels.bin magic")
    if len(data) < 12:
        raise ValueError("Invalid pixels.bin header")
    count = struct.unpack(">I", data[8:12])[0]
    record_size = struct.calcsize(">HHHI")
    expected = 12 + count * record_size
    if len(data) != expected:
        raise ValueError("Invalid pixels.bin length")
    pixels: list[Pixel] = []
    offset = 12
    for _ in range(count):
        x, y, palette_index, queue_index = struct.unpack(">HHHI", data[offset:offset + record_size])
        pixels.append(Pixel(x, y, palette_index, queue_index))
        offset += record_size
    return pixels


def spatial_permutation(pixels: list[Pixel], seed: int) -> list[Pixel]:
    """Return a stable, seed-based spatial order without changing pixel identity."""
    return sorted(
        pixels,
        key=lambda pixel: (
            hashlib.sha256(f"{seed}:{pixel.queue_index}".encode("ascii")).digest(),
            pixel.queue_index,
        ),
    )


def reorder_show(
    input_path: str | Path,
    output_path: str | Path,
    *,
    effect_limits: dict[str, int] | None = None,
) -> None:
    """Rewrite an existing package with deterministic spatial reveal ordering."""
    input_path = Path(input_path)
    output_path = Path(output_path)
    if input_path.resolve() == output_path.resolve():
        raise ValueError("reorder output must be different from input")
    with ZipFile(input_path) as source:
        names = source.namelist()
        if "manifest.json" not in names or "pixels.bin" not in names:
            raise ValueError("Invalid .pshow package: manifest.json and pixels.bin are required")
        manifest = json.loads(source.read("manifest.json"))
        pixels = decode_pixels(source.read("pixels.bin"))
        seed = int(manifest.get("randomSeed", 0))
        reordered = spatial_permutation(pixels, seed)
        old_allocation = manifest.get("allocation", "unknown")
        metadata = manifest.setdefault("metadata", {})
        if not isinstance(metadata, dict):
            metadata = {}
            manifest["metadata"] = metadata
        metadata["reorderedFrom"] = old_allocation
        manifest["allocation"] = "deterministic_random_spatial"
        manifest["revealOrder"] = "seeded_pixel_permutation"
        if effect_limits:
            limits = manifest.setdefault("effectLimits", {})
            if not isinstance(limits, dict):
                limits = {}
                manifest["effectLimits"] = limits
            limits.update({key: int(value) for key, value in effect_limits.items()})

        output_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = output_path.with_name(output_path.name + ".tmp")
        try:
            with ZipFile(temporary, "w", compression=ZIP_DEFLATED, compresslevel=9) as target:
                for name in names:
                    if name == "manifest.json":
                        content = json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True).encode("utf-8")
                    elif name == "pixels.bin":
                        content = encode_pixels(reordered)
                    elif name == "preview.json":
                        preview = json.loads(source.read(name))
                        preview["manifest"] = manifest
                        preview["pixels"] = [[p.x, p.y, p.palette_index, p.queue_index] for p in reordered]
                        content = json.dumps(preview, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
                    else:
                        content = source.read(name)
                    target.writestr(name, content)
            os.replace(temporary, output_path)
        finally:
            if temporary.exists():
                temporary.unlink()


def write_show(
    path: str | Path,
    manifest: dict[str, object],
    events: list[NoteEvent],
    pixels: list[Pixel],
    palette: list[PaletteEntry],
    preview_png: bytes,
    layout: dict[str, object],
) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(path, "w", compression=ZIP_DEFLATED, compresslevel=9) as archive:
        archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True))
        archive.writestr("events.bin", encode_events(events))
        archive.writestr("pixels.bin", encode_pixels(pixels))
        archive.writestr("palette.json", json.dumps(palette_json(palette), ensure_ascii=False, indent=2))
        archive.writestr("preview.png", preview_png)
        archive.writestr("layout.json", json.dumps(layout, ensure_ascii=False, indent=2, sort_keys=True))
        # A small, browser-friendly sidecar avoids teaching the web preview how to
        # decode the compact binary event/pixel streams.  It is deterministic and
        # intentionally contains no source MIDI/image data.
        preview = {
            "formatVersion": int(manifest.get("formatVersion", 1)),
            "manifest": manifest,
            "layout": layout,
            "events": [
                [event.tick, event.note, event.velocity, event.duration_ticks]
                for event in events
            ],
            "pixels": [
                [pixel.x, pixel.y, pixel.palette_index, pixel.queue_index]
                for pixel in pixels
            ],
            "palette": palette_json(palette),
        }
        archive.writestr("preview.json", json.dumps(preview, ensure_ascii=False, separators=(",", ":"), sort_keys=True))


def read_manifest(path: str | Path) -> dict[str, object]:
    with ZipFile(path) as archive:
        return json.loads(archive.read("manifest.json"))
