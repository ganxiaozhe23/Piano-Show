from __future__ import annotations

import json
from pathlib import Path
from zipfile import ZipFile

import mido
from PIL import Image

from piano_show.compiler import compile_show, compile_to_file
from piano_show.image import compile_image
from piano_show.models import CompileOptions
from piano_show.midi import read_midi
from piano_show.palette import DEFAULT_PALETTE, load_palette
from piano_show.cli import _build_parser
from piano_show.package import decode_pixels, reorder_show, write_show


def make_midi(path: Path) -> None:
    midi = mido.MidiFile(ticks_per_beat=480)
    track = mido.MidiTrack()
    track.append(mido.MetaMessage("set_tempo", tempo=500_000, time=0))
    track.append(mido.Message("note_on", note=60, velocity=100, time=0))
    track.append(mido.Message("note_off", note=60, velocity=0, time=480))
    track.append(mido.Message("note_on", note=64, velocity=80, time=240))
    track.append(mido.Message("note_off", note=64, velocity=0, time=240))
    midi.tracks.append(track)
    midi.save(path)


def test_midi_timing_and_duration(tmp_path: Path) -> None:
    path = tmp_path / "song.mid"
    make_midi(path)
    events = read_midi(path)
    assert [event.note for event in events] == [60, 64]
    assert events[0].tick == 0
    assert events[0].duration_ticks == 10
    assert events[1].tick == 15


def test_image_queue_is_serpentine_and_ignores_transparency(tmp_path: Path) -> None:
    image_path = tmp_path / "image.png"
    image = Image.new("RGBA", (4, 4), (255, 0, 0, 255))
    image.putpixel((0, 0), (0, 0, 0, 0))
    image.save(image_path)
    pixels, preview, width, height = compile_image(image_path, 4, list(DEFAULT_PALETTE))
    assert (width, height) == (4, 4)
    assert len(preview) > 0
    assert pixels[0].y == 0 and pixels[0].x == 1
    row_one = [(pixel.x, pixel.y) for pixel in pixels if pixel.y == 1]
    assert row_one == [(3, 1), (2, 1), (1, 1), (0, 1)]


def test_pshow_package_is_deterministic_and_has_required_entries(tmp_path: Path) -> None:
    midi_path = tmp_path / "song.mid"
    image_path = tmp_path / "image.png"
    out_path = tmp_path / "show.pshow"
    make_midi(midi_path)
    Image.new("RGBA", (3, 3), (20, 100, 220, 255)).save(image_path)
    compiled = compile_to_file(midi_path, image_path, out_path, CompileOptions(resolution=4))
    assert out_path.exists()
    assert compiled.manifest["formatVersion"] == 2
    assert compiled.manifest["eventCount"] == 2
    assert compiled.manifest["effectLimits"]["maxActiveFallingBlocks"] == 512
    assert compiled.manifest["effectLimits"]["maxSpawnPerTick"] == 64
    assert compiled.manifest["effectLimits"]["flightTicksMin"] == 12
    assert compiled.manifest["effectLimits"]["flightTicksMax"] == 36
    assert compiled.manifest["effectLimits"]["snapTicks"] == 5
    assert compiled.manifest["allocation"] == "deterministic_random_spatial"
    with ZipFile(out_path) as package:
        assert set(package.namelist()) == {
            "manifest.json", "events.bin", "pixels.bin", "palette.json", "preview.png", "layout.json", "preview.json"
        }
        assert package.read("events.bin").startswith(b"PSHOWEV1")
        assert package.read("pixels.bin").startswith(b"PSHOWPX1")


def test_spatial_reorder_is_deterministic_and_preserves_pixels(tmp_path: Path) -> None:
    midi_path = tmp_path / "song.mid"
    image_path = tmp_path / "image.png"
    source_path = tmp_path / "legacy.pshow"
    reordered_path = tmp_path / "reordered.pshow"
    make_midi(midi_path)
    Image.new("RGBA", (8, 8), (20, 100, 220, 255)).save(image_path)
    options = CompileOptions(resolution=8, random_seed=1234)
    compiled = compile_show(midi_path, image_path, options)
    legacy_pixels, _, _, _ = compile_image(image_path, 8, list(DEFAULT_PALETTE))
    legacy_manifest = dict(compiled.manifest)
    legacy_manifest["allocation"] = "normalized_queue_serpentine"
    legacy_manifest.pop("revealOrder", None)
    write_show(source_path, legacy_manifest, compiled.events, legacy_pixels, load_palette(None), compiled.preview_png, compiled.layout)
    reorder_show(source_path, reordered_path)
    with ZipFile(source_path) as source, ZipFile(reordered_path) as reordered:
        source_pixels = decode_pixels(source.read("pixels.bin"))
        reordered_pixels = decode_pixels(reordered.read("pixels.bin"))
        manifest = json.loads(reordered.read("manifest.json"))
    assert sorted(source_pixels, key=lambda pixel: pixel.queue_index) == sorted(reordered_pixels, key=lambda pixel: pixel.queue_index)
    assert [pixel.queue_index for pixel in source_pixels] != [pixel.queue_index for pixel in reordered_pixels]
    assert manifest["allocation"] == "deterministic_random_spatial"
    assert manifest["revealOrder"] == "seeded_pixel_permutation"


def test_new_cli_defaults_to_floor_stage_layout() -> None:
    args = _build_parser().parse_args(["import", "--midi", "song.mid", "--image", "image.png", "--out", "show.pshow"])
    assert args.orientation == "wall_north"
    assert args.canvas_gap == 8
    assert args.keyboard_depth == 4
