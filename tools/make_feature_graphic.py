#!/usr/bin/env python3
"""Renders the Google Play feature graphic (1024 x 500 px) from the Lodestar icon artwork.

The lodestar star and reticle (tools/app_icon_art.py) sit on the left of a wide night-sky gradient, with the app
name and tagline on the right. Deterministic: fixed seeds, no current-time data.

Usage: python3 tools/make_feature_graphic.py   (requires Pillow and NumPy; uses Liberation Sans)
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
import app_icon_art as art  # noqa: E402

WIDTH, HEIGHT = 1024, 500
FONT_DIR = Path("/usr/share/fonts/truetype/liberation2")
OUTPUT = Path(__file__).resolve().parent.parent / "PlayStore" / "graphics" / "feature-graphic-1024x500.png"


def sky() -> np.ndarray:
    """The icon's tilted vertical gradient, widened to the banner, with scattered faint stars."""
    y = (np.arange(HEIGHT, dtype=np.float32) + 0.5) / HEIGHT
    x = (np.arange(WIDTH, dtype=np.float32) + 0.5) / WIDTH
    t = art._smoothstep(0.02, 1.0, y[:, None] * 0.85 + x[None, :] * 0.15)
    top = np.array(art.DEFAULT.sky_top, dtype=np.float32)
    bottom = np.array(art.DEFAULT.sky_bottom, dtype=np.float32)
    image = top + (bottom - top) * t[..., None]
    rng = np.random.default_rng(art.SEED + 7)
    for _ in range(140):
        cx, cy = rng.uniform(0, WIDTH), rng.uniform(0, HEIGHT)
        level = 0.25 + 0.6 * rng.random() ** 3
        r = 0.6 + 1.2 * rng.random() ** 4
        yy, xx = np.ogrid[max(0, int(cy) - 4):min(HEIGHT, int(cy) + 5), max(0, int(cx) - 4):min(WIDTH, int(cx) + 5)]
        glow = np.exp(-((xx - cx) ** 2 + (yy - cy) ** 2) / (2 * r * r)) * level
        image[yy, xx] = art._screen(image[yy, xx], glow[..., None] * np.array([0.9, 0.93, 1.0], dtype=np.float32))
    return image


def main() -> int:
    background = sky()
    icon = art.render(art.DEFAULT)  # 1024 px, float RGB
    size = 470
    crop = Image.fromarray((icon * 255).astype(np.uint8)).resize((size, size), Image.Resampling.LANCZOS)
    star = np.asarray(crop, dtype=np.float32) / 255.0
    # Feathered circular mask, so the icon's haze melts into the banner sky.
    axis = (np.arange(size, dtype=np.float32) + 0.5) / size - 0.5
    radius = np.hypot(axis[None, :], axis[:, None]) / 0.5
    mask = (1.0 - art._smoothstep(0.62, 0.98, radius))[..., None]
    left, top = 40, (HEIGHT - size) // 2
    region = background[top:top + size, left:left + size]
    background[top:top + size, left:left + size] = region * (1 - mask) + star * mask

    image = art.quantise(background, np.random.default_rng(art.SEED + 8))  # dithered, no banding
    draw = ImageDraw.Draw(image)
    x, right_margin = 540, 44

    def fitted(name: str, text: str, size: int) -> ImageFont.FreeTypeFont:
        """The largest font up to `size` px whose rendering of `text` fits between x and the right margin."""
        while size > 10:
            font = ImageFont.truetype(str(FONT_DIR / name), size)
            if x + 4 + draw.textlength(text, font=font) <= WIDTH - right_margin:
                return font
            size -= 1
        return ImageFont.truetype(str(FONT_DIR / name), size)

    tagline_text = "Aim your camera at the stars"
    draw.text((x, 128), "Lodestar", font=fitted("LiberationSans-Bold.ttf", "Lodestar", 104), fill=(242, 244, 248))
    draw.text((x + 4, 262), tagline_text, font=fitted("LiberationSans-Regular.ttf", tagline_text, 38), fill=(245, 196, 112))
    detail = ImageFont.truetype(str(FONT_DIR / "LiberationSans-Regular.ttf"), 27)
    draw.text((x + 4, 326), "Guided pan & tilt arrows for", font=detail, fill=(167, 175, 194))
    draw.text((x + 4, 362), "night-sky photography", font=detail, fill=(167, 175, 194))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUTPUT, optimize=True)
    print("wrote", OUTPUT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
