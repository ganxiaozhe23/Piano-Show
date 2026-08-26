from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import json


@dataclass(frozen=True)
class PaletteEntry:
    block: str
    color: tuple[int, int, int]
    name: str = ""


DEFAULT_PALETTE: tuple[PaletteEntry, ...] = (
    PaletteEntry("minecraft:white_concrete", (207, 213, 214), "white"),
    PaletteEntry("minecraft:light_gray_concrete", (125, 125, 115), "light_gray"),
    PaletteEntry("minecraft:gray_concrete", (55, 58, 62), "gray"),
    PaletteEntry("minecraft:black_concrete", (8, 10, 15), "black"),
    PaletteEntry("minecraft:red_concrete", (142, 32, 32), "red"),
    PaletteEntry("minecraft:orange_concrete", (224, 97, 0), "orange"),
    PaletteEntry("minecraft:yellow_concrete", (240, 175, 21), "yellow"),
    PaletteEntry("minecraft:lime_concrete", (94, 169, 24), "lime"),
    PaletteEntry("minecraft:green_concrete", (73, 91, 36), "green"),
    PaletteEntry("minecraft:cyan_concrete", (21, 119, 136), "cyan"),
    PaletteEntry("minecraft:light_blue_concrete", (35, 137, 198), "light_blue"),
    PaletteEntry("minecraft:blue_concrete", (44, 46, 143), "blue"),
    PaletteEntry("minecraft:purple_concrete", (100, 31, 156), "purple"),
    PaletteEntry("minecraft:magenta_concrete", (169, 48, 159), "magenta"),
    PaletteEntry("minecraft:pink_concrete", (214, 101, 143), "pink"),
    PaletteEntry("minecraft:brown_concrete", (96, 59, 33), "brown"),
    PaletteEntry("minecraft:white_wool", (234, 236, 236), "white_wool"),
    PaletteEntry("minecraft:light_gray_wool", (142, 142, 134), "light_gray_wool"),
    PaletteEntry("minecraft:gray_wool", (63, 68, 72), "gray_wool"),
    PaletteEntry("minecraft:black_wool", (21, 21, 26), "black_wool"),
    PaletteEntry("minecraft:red_wool", (160, 39, 34), "red_wool"),
    PaletteEntry("minecraft:orange_wool", (234, 126, 44), "orange_wool"),
    PaletteEntry("minecraft:yellow_wool", (249, 199, 41), "yellow_wool"),
    PaletteEntry("minecraft:lime_wool", (112, 185, 25), "lime_wool"),
    PaletteEntry("minecraft:green_wool", (84, 109, 27), "green_wool"),
    PaletteEntry("minecraft:cyan_wool", (21, 137, 145), "cyan_wool"),
    PaletteEntry("minecraft:light_blue_wool", (58, 175, 217), "light_blue_wool"),
    PaletteEntry("minecraft:blue_wool", (53, 57, 158), "blue_wool"),
    PaletteEntry("minecraft:purple_wool", (121, 42, 172), "purple_wool"),
    PaletteEntry("minecraft:magenta_wool", (191, 61, 174), "magenta_wool"),
    PaletteEntry("minecraft:pink_wool", (238, 141, 172), "pink_wool"),
    PaletteEntry("minecraft:brown_wool", (115, 71, 41), "brown_wool"),
)


def load_palette(path: str | Path | None = None) -> list[PaletteEntry]:
    if path is None:
        return list(DEFAULT_PALETTE)
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    entries = raw.get("entries", raw)
    result = []
    for item in entries:
        color = item.get("color")
        if not isinstance(color, list) or len(color) != 3:
            raise ValueError("palette color must be [r, g, b]")
        result.append(PaletteEntry(item["block"], tuple(int(v) for v in color), item.get("name", "")))
    if not result:
        raise ValueError("palette must contain at least one entry")
    return result


def palette_json(entries: list[PaletteEntry]) -> list[dict[str, object]]:
    return [{"block": e.block, "color": list(e.color), "name": e.name} for e in entries]
