#!/usr/bin/env python3
"""Renders the Android launcher icon and the Play Store icon from the Lodestar artwork.

The artwork is the iOS app icon (tools/app_icon_art.py, a copy of Tools/make_app_icon.py in the iOS repository),
so both apps share one icon:

- Adaptive icon (Android 8+): the full artwork fills the 72 dp viewport of the 108 dp background layer; the outer
  18 dp (only revealed by launcher effects) continue the sky. The foreground layer is empty.
- Themed icon (Android 13+): a monochrome layer with the star and reticle.
- Play Store icon: 512 x 512 px, opaque, full bleed (Google Play rounds the corners itself).

Usage: python3 tools/make_android_icons.py   (requires Pillow and NumPy)
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import app_icon_art as art  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def extend_to_layer(viewport: Image.Image, mode: str) -> Image.Image:
    """Places a 72 dp viewport image in the centre of a 108 dp layer, repeating its edge pixels outward."""
    size = viewport.size[0]
    margin = size // 4  # 18 dp of 72 dp
    array = np.asarray(viewport)
    pad = ((margin, margin), (margin, margin)) + (((0, 0),) if array.ndim == 3 else ())
    return Image.fromarray(np.pad(array, pad, mode=mode))


def main() -> int:
    dither = np.random.default_rng(art.SEED + 1)
    artwork = art.quantise(art.render(art.DEFAULT), dither)  # 1024 px RGB
    star = art._star_coverage(art.STAR_HALF_LENGTH, art.STAR_SHARPNESS)
    reticle = art._reticle_coverage()
    monochrome = Image.fromarray(np.clip(np.maximum(star, reticle) * 255, 0, 255).astype(np.uint8), mode="L")

    for density, scale in DENSITIES.items():
        viewport = round(72 * scale)
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        background = extend_to_layer(artwork.resize((viewport, viewport), Image.Resampling.LANCZOS), "edge")
        background.save(folder / "ic_launcher_background.png", optimize=True)
        alpha = extend_to_layer(monochrome.resize((viewport, viewport), Image.Resampling.LANCZOS), "constant")
        mono = Image.merge("RGBA", (alpha.point(lambda _: 255),) * 3 + (alpha,))
        mono.save(folder / "ic_launcher_monochrome.png", optimize=True)

    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    adaptive = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@android:color/transparent" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
"""
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (anydpi / name).write_text(adaptive)

    store = ROOT / "PlayStore" / "graphics"
    store.mkdir(parents=True, exist_ok=True)
    artwork.resize((512, 512), Image.Resampling.LANCZOS).save(store / "icon-512.png", optimize=True)
    print("wrote launcher icons for", ", ".join(DENSITIES), "and", store / "icon-512.png")
    return 0


if __name__ == "__main__":
    sys.exit(main())
