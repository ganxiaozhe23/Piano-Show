from __future__ import annotations

from io import BytesIO
from pathlib import Path

from PIL import Image, ImageOps

from .models import Pixel
from .palette import PaletteEntry


def render_preview_png(width: int, height: int, pixels: list[Pixel], palette: list[PaletteEntry]) -> bytes:
    preview = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    for pixel in pixels:
        if 0 <= pixel.palette_index < len(palette) and 0 <= pixel.x < width and 0 <= pixel.y < height:
            preview.putpixel((pixel.x, pixel.y), (*palette[pixel.palette_index].color, 255))
    output = BytesIO()
    preview.save(output, format="PNG", optimize=True)
    return output.getvalue()


def _nearest_palette(rgb: tuple[int, int, int], palette: list[PaletteEntry]) -> int:
    return min(
        range(len(palette)),
        key=lambda index: sum((rgb[channel] - palette[index].color[channel]) ** 2 for channel in range(3)),
    )


def compile_image(
    path: str | Path,
    resolution: int,
    palette: list[PaletteEntry],
    *,
    transparent_threshold: int = 8,
    dither: bool = False,
) -> tuple[list[Pixel], bytes, int, int]:
    """Resize an image, quantize it and return a serpentine pixel queue plus preview PNG."""
    if resolution <= 0 or resolution > 65535:
        raise ValueError("resolution must be in the range 1..65535")
    if not palette:
        raise ValueError("palette cannot be empty")
    source = Image.open(path).convert("RGBA")
    image = ImageOps.fit(source, (resolution, resolution), method=Image.Resampling.LANCZOS)

    pixels: list[Pixel] = []
    preview = Image.new("RGBA", image.size, (0, 0, 0, 0))
    queue_index = 0
    # A small ordered dither gives texture without changing the deterministic queue.
    matrix = ((0, 2), (3, 1))
    for y in range(resolution):
        xs = range(resolution) if y % 2 == 0 else range(resolution - 1, -1, -1)
        for x in xs:
            r, g, b, alpha = image.getpixel((x, y))
            if alpha < transparent_threshold:
                continue
            if dither:
                offset = (matrix[y % 2][x % 2] - 1.5) * 8
                sample = (max(0, min(255, int(r + offset))), max(0, min(255, int(g + offset))), max(0, min(255, int(b + offset))))
            else:
                sample = (r, g, b)
            palette_index = _nearest_palette(sample, palette)
            pixels.append(Pixel(x=x, y=y, palette_index=palette_index, queue_index=queue_index))
            preview.putpixel((x, y), (*palette[palette_index].color, 255))
            queue_index += 1

    output = BytesIO()
    preview.save(output, format="PNG", optimize=True)
    return pixels, output.getvalue(), resolution, resolution
