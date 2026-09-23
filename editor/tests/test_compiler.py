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


def test_rotation_and_canvas_offset_are_encoded_deterministically(tmp_path: Path) -> None:
    midi_path = tmp_path / "song.mid"
    image_path = tmp_path / "image.png"
    make_midi(midi_path)
    image = Image.new("RGBA", (2, 2))
    image.putpixel((0, 0), (255, 0, 0, 255))
    image.putpixel((1, 0), (0, 255, 0, 255))
    image.putpixel((0, 1), (0, 0, 255, 255))
    image.putpixel((1, 1), (255, 255, 0, 255))
    image.save(image_path)
    options = CompileOptions(resolution=2, image_rotation=90, canvas_offset=(1, 2, 3))
    first = compile_show(midi_path, image_path, options)
    second = compile_show(midi_path, image_path, options)
    assert first.manifest["showId"] == second.manifest["showId"]
    assert first.manifest["imageRotation"] == 90
    assert first.manifest["canvasOffset"] == [1, 2, 3]
    assert first.layout["canvas"]["positionOffset"] == [1, 2, 3]
    assert first.layout["canvas"]["anchor"] == [1, 71, -5]
    assert [(p.x, p.y, p.palette_index) for p in first.pixels] == [(p.x, p.y, p.palette_index) for p in second.pixels]
    base_pixels, _, _, _ = compile_image(image_path, 2, list(DEFAULT_PALETTE), image_rotation=0)
    pixels, _, _, _ = compile_image(image_path, 2, list(DEFAULT_PALETTE), image_rotation=90)
    base = {(p.x, p.y): p.palette_index for p in base_pixels}
    rotated = {(p.x, p.y): p.palette_index for p in pixels}
    assert rotated[(1, 0)] == base[(0, 0)]  # red moves from (0, 0) to (1, 0)


def test_invalid_rotation_and_canvas_offset_are_rejected(tmp_path: Path) -> None:
    midi_path = tmp_path / "song.mid"
    image_path = tmp_path / "image.png"
    make_midi(midi_path)
    Image.new("RGBA", (2, 2), (255, 0, 0, 255)).save(image_path)
    import pytest
    with pytest.raises(ValueError, match="image_rotation"):
        compile_show(midi_path, image_path, CompileOptions(resolution=2, image_rotation=45))
    with pytest.raises(ValueError, match="canvas_offset"):
        compile_show(midi_path, image_path, CompileOptions(resolution=2, canvas_offset=(129, 0, 0)))


def test_canvas_anchor_applies_offset_for_every_surface(tmp_path: Path) -> None:
    midi_path = tmp_path / "song.mid"
    image_path = tmp_path / "image.png"
    make_midi(midi_path)
    Image.new("RGBA", (2, 2), (255, 0, 0, 255)).save(image_path)
    expected = {
        "floor": [2, 68, 15],
        "wall_north": [2, 68, -5],
        "wall_south": [2, 68, 15],
        "wall_east": [114, 68, 3],
        "wall_west": [-6, 68, 3],
    }
    for surface, anchor in expected.items():
        compiled = compile_show(
            midi_path,
            image_path,
            CompileOptions(
                resolution=2,
                pixel_scale=1,
                canvas_lift=0,
                canvas_gap=8,
                canvas_offset=(2, 3, 3),
                surface=surface,
                orientation=surface,
            ),
        )
        assert compiled.layout["canvas"]["anchor"] == anchor


def test_motion_options_are_encoded_and_change_hash(tmp_path: Path) -> None:
    midi_path = tmp_path / "song.mid"
    image_path = tmp_path / "image.png"
    make_midi(midi_path)
    Image.new("RGBA", (2, 2), (255, 0, 0, 255)).save(image_path)
    arc = compile_show(midi_path, image_path, CompileOptions(resolution=2, motion_mode="arc"))
    ballistic = compile_show(midi_path, image_path, CompileOptions(
        resolution=2, surface="floor", orientation="floor", motion_mode="ballistic",
        motion_gravity=0.04, motion_drag=0.98, motion_arc_height=3.0,
    ))
    assert arc.manifest["motionMode"] == "arc"
    assert ballistic.manifest["motionMode"] == "ballistic"
    assert ballistic.manifest["motionGravity"] == 0.04
    assert ballistic.manifest["motionDrag"] == 0.98
    assert ballistic.manifest["motionArcHeight"] == 3.0
    assert arc.manifest["showId"] != ballistic.manifest["showId"]
    assert ballistic.layout["canvas"]["surface"] == "floor"


def test_motion_options_are_validated(tmp_path: Path) -> None:
    midi_path = tmp_path / "song.mid"
    image_path = tmp_path / "image.png"
    make_midi(midi_path)
    Image.new("RGBA", (2, 2), (255, 0, 0, 255)).save(image_path)
    import pytest
    with pytest.raises(ValueError, match="motion_mode"):
        compile_show(midi_path, image_path, CompileOptions(resolution=2, motion_mode="bad"))
    with pytest.raises(ValueError, match="motion_gravity"):
        compile_show(midi_path, image_path, CompileOptions(resolution=2, motion_gravity=2))
    with pytest.raises(ValueError, match="motion_drag"):
        compile_show(midi_path, image_path, CompileOptions(resolution=2, motion_drag=-1))
    with pytest.raises(ValueError, match="motion_arc_height"):
        compile_show(midi_path, image_path, CompileOptions(resolution=2, motion_arc_height=-1))


def test_arc_height_has_no_artificial_upper_bound(tmp_path: Path) -> None:
    midi_path = tmp_path / "song.mid"
    image_path = tmp_path / "image.png"
    make_midi(midi_path)
    Image.new("RGBA", (2, 2), (255, 0, 0, 255)).save(image_path)
    heights = (33.0, 128.0, 1024.5)
    compiled = [compile_show(midi_path, image_path, CompileOptions(resolution=2, motion_arc_height=value)) for value in heights]
    assert [item.manifest["motionArcHeight"] for item in compiled] == list(heights)
    assert len({item.manifest["showId"] for item in compiled}) == len(heights)
    import pytest
    for invalid in (float("nan"), float("inf"), float("-inf")):
        with pytest.raises(ValueError, match="motion_arc_height"):
            compile_show(midi_path, image_path, CompileOptions(resolution=2, motion_arc_height=invalid))


def test_vanilla_motion_requires_physical_and_is_encoded(tmp_path: Path) -> None:
    midi_path = tmp_path / "song.mid"
    image_path = tmp_path / "image.png"
    make_midi(midi_path)
    Image.new("RGBA", (2, 2), (255, 0, 0, 255)).save(image_path)
    import pytest
    with pytest.raises(ValueError, match="requires visual_mode=physical"):
        compile_show(midi_path, image_path, CompileOptions(resolution=2, motion_mode="vanilla"))
    compiled = compile_show(
        midi_path,
        image_path,
        CompileOptions(resolution=2, surface="floor", orientation="floor", visual_mode="physical", motion_mode="vanilla"),
    )
    assert compiled.manifest["motionMode"] == "vanilla"
    assert compiled.manifest["motionGravity"] == 0.04
    assert compiled.manifest["motionDrag"] == 0.98
    assert compiled.layout["canvas"]["surface"] == "floor"
