#!/usr/bin/env python3
"""Renders the Lodestar app icon: default, dark and tinted appearances (iOS 18 single-size format).

The artwork is a deep night sky with a bright four-pointed "lodestar" star, a soft glow, a thin
aiming reticle ring and a few small stars.  Everything is drawn procedurally from fixed seeds and
constants, so re-running the script reproduces the same artwork.

Usage:
    python3 Tools/make_app_icon.py [--output-dir DIR] [--contact-sheet FILE]

By default the three 1024x1024 PNGs are written into
Lodestar/Resources/Assets.xcassets/AppIcon.appiconset, next to the Contents.json that references
them.  Every image is opaque RGB (no alpha channel) and carries an embedded sRGB profile.

Requires Pillow (9.1 or newer) and NumPy.
"""

from __future__ import annotations

import argparse
import math
import struct
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image, ImageCms, ImageDraw

ICON_SIZE = 1024
"""Edge length of the final icons in pixels (the App Store marketing size)."""

SUPERSAMPLE = 3
"""Crisp shapes (star, reticle, small stars) are drawn at this multiple of ICON_SIZE."""

SEED = 20240917
"""Seed for the small background stars; the dither noise uses SEED + 1."""

PROFILE_DATE = (2024, 9, 17, 0, 0, 0)
"""Fixed creation date for the embedded sRGB profile (lcms would stamp the current time)."""

Color = tuple[float, float, float]
"""An sRGB-encoded colour with components in 0...1."""

# Geometry in canvas units: 1.0 is the icon's width and the origin is its centre.
STAR_HALF_LENGTH = 0.415
STAR_SHARPNESS = 5.0  # exponent k of the astroid-like outline; larger means thinner spikes
RING_RADIUS = 0.315
RING_WIDTH = 0.0075
RING_GAP_DEGREES = 9.0  # the ring is interrupted on each side of the four star spikes
TICK_INNER_RADIUS = 0.262
TICK_WIDTH = 0.0055

# Small stars stay inside this superellipse so the system's rounded-corner mask never clips them.
FIELD_STAR_COUNT = 64
FIELD_STAR_MIN_RADIUS = 0.372
FIELD_STAR_REACH = 0.415


@dataclass(frozen=True)
class Palette:
    """Colours and intensities of one icon appearance."""

    sky_top: Color
    sky_bottom: Color
    haze: Color  # faint tint of the sky around the star
    haze_strength: float
    star_core: Color  # white-hot centre of the star
    star_edge: Color  # gold of the spikes
    glow_near: Color  # tight bloom around the star
    glow_far: Color  # wide halo that melts into the sky
    glow_gain: float
    ring: Color
    ring_strength: float
    field_star_strength: float


DEFAULT = Palette(
    sky_top=(0.010, 0.013, 0.045),
    sky_bottom=(0.118, 0.105, 0.390),
    haze=(0.17, 0.20, 0.62),
    haze_strength=0.55,
    star_core=(1.00, 0.985, 0.93),
    star_edge=(1.00, 0.78, 0.33),
    glow_near=(1.00, 0.76, 0.30),
    glow_far=(0.50, 0.46, 1.00),
    glow_gain=1.0,
    ring=(0.78, 0.84, 1.00),
    ring_strength=0.52,
    field_star_strength=1.0,
)

DARK = Palette(
    sky_top=(0.004, 0.005, 0.018),
    sky_bottom=(0.040, 0.036, 0.150),
    haze=(0.12, 0.14, 0.50),
    haze_strength=0.40,
    star_core=(1.00, 0.97, 0.90),
    star_edge=(0.98, 0.74, 0.30),
    glow_near=(1.00, 0.72, 0.27),
    glow_far=(0.42, 0.38, 0.92),
    glow_gain=0.85,
    ring=(0.72, 0.78, 0.96),
    ring_strength=0.42,
    field_star_strength=0.85,
)


# --------------------------------------------------------------------------------------------
# Shapes
# --------------------------------------------------------------------------------------------


def _to_pixels(point: tuple[float, float], size: int) -> tuple[float, float]:
    """Maps canvas units (centre origin, y down) to pixel coordinates of a size x size image."""
    return ((point[0] + 0.5) * size, (point[1] + 0.5) * size)


def _downsample_mask(image: Image.Image) -> np.ndarray:
    """Averages a supersampled single-channel drawing down to ICON_SIZE (float32, 0...1)."""
    small = image.resize((ICON_SIZE, ICON_SIZE), Image.Resampling.BOX)
    return np.asarray(small, dtype=np.float32) / 255.0


def _twinkle_outline(half_length: float, samples: int = 360) -> list[tuple[float, float]]:
    """Outline of a small concave four-pointed star (astroid) centred on the origin."""
    t = np.linspace(0.0, 2.0 * math.pi, samples, endpoint=False)
    c, s = np.cos(t), np.sin(t)
    x = half_length * np.sign(c) * np.abs(c) ** 3
    y = half_length * np.sign(s) * np.abs(s) ** 3
    return list(zip(x.tolist(), y.tolist()))


def _star_coverage(half_length: float, sharpness: float) -> np.ndarray:
    """Anti-aliased coverage of the lodestar, evaluated analytically per sample.

    The star is the region (|x|/a)^p + (|y|/a)^p <= 1 with p = 2/k, the implicit form of
    x = a*cos^k(t), y = a*sin^k(t).  The distance to its boundary is estimated from the first-order
    gradient, which keeps the needle-thin tips smooth where a scan-line polygon fill would leave
    stray spans.  The coverage is evaluated at SUPERSAMPLE x ICON_SIZE and averaged down.
    """
    big = ICON_SIZE * SUPERSAMPLE
    axis = np.abs((np.arange(big, dtype=np.float32) + 0.5) / big - 0.5) / half_length
    p = 2.0 / sharpness
    ax = axis[None, :]
    ay = axis[:, None]
    f = ax**p + ay**p
    gradient = (p / half_length) * np.sqrt(ax ** (2.0 * p - 2.0) + ay ** (2.0 * p - 2.0))
    distance_in_samples = (f - 1.0) / gradient * big
    coverage = np.clip(0.5 - distance_in_samples, 0.0, 1.0)
    return coverage.reshape(ICON_SIZE, SUPERSAMPLE, ICON_SIZE, SUPERSAMPLE).mean(axis=(1, 3))


def _reticle_coverage() -> np.ndarray:
    """The reticle: a ring interrupted where the spikes cross it, plus four diagonal ticks."""
    big = ICON_SIZE * SUPERSAMPLE
    image = Image.new("L", (big, big), 0)
    draw = ImageDraw.Draw(image)
    centre = big / 2.0
    radius = RING_RADIUS * big
    width = max(1, round(RING_WIDTH * big))
    box = [centre - radius, centre - radius, centre + radius, centre + radius]
    for quadrant in range(4):
        start = quadrant * 90.0 + RING_GAP_DEGREES
        end = quadrant * 90.0 + 90.0 - RING_GAP_DEGREES
        draw.arc(box, start, end, fill=255, width=width)

    half_width = TICK_WIDTH * big / 2.0
    inner = TICK_INNER_RADIUS * big
    outer = radius - width * 0.5
    for diagonal in (45.0, 135.0, 225.0, 315.0):
        ux, uy = math.cos(math.radians(diagonal)), math.sin(math.radians(diagonal))
        nx, ny = -uy, ux
        draw.polygon(
            [
                (centre + ux * inner + nx * half_width, centre + uy * inner + ny * half_width),
                (centre + ux * outer + nx * half_width, centre + uy * outer + ny * half_width),
                (centre + ux * outer - nx * half_width, centre + uy * outer - ny * half_width),
                (centre + ux * inner - nx * half_width, centre + uy * inner - ny * half_width),
            ],
            fill=255,
        )
    return _downsample_mask(image)


def _field_stars(rng: np.random.Generator, strength: float) -> np.ndarray:
    """Small background stars as an RGB float32 layer of additive light on black."""
    big = ICON_SIZE * SUPERSAMPLE
    image = Image.new("RGB", (big, big), (0, 0, 0))
    draw = ImageDraw.Draw(image)
    tints = np.array([(1.0, 1.0, 1.0), (0.80, 0.88, 1.0), (1.0, 0.92, 0.76)])
    placed = 0
    for _ in range(10_000):
        if placed == FIELD_STAR_COUNT:
            break
        x, y = rng.uniform(-0.5, 0.5, size=2)
        reach = (abs(x) ** 4 + abs(y) ** 4) ** 0.25
        if math.hypot(x, y) < FIELD_STAR_MIN_RADIUS or reach > FIELD_STAR_REACH:
            continue
        luminance = 0.28 + 0.72 * rng.random() ** 2
        radius = (0.55 + 1.7 * rng.random() ** 3) / ICON_SIZE
        tint = tints[rng.choice(3, p=[0.55, 0.25, 0.20])]
        colour = tuple(int(round(255 * strength * luminance * c)) for c in tint)
        cx, cy = _to_pixels((x, y), big)
        r = radius * big
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=colour)
        if luminance > 0.82:  # the brightest few get a tiny four-pointed twinkle
            outline = _twinkle_outline(radius * 5.5)
            draw.polygon([_to_pixels((x + px, y + py), big) for px, py in outline], fill=colour)
        placed += 1
    small = image.resize((ICON_SIZE, ICON_SIZE), Image.Resampling.BOX)
    return np.asarray(small, dtype=np.float32) / 255.0


# --------------------------------------------------------------------------------------------
# Light
# --------------------------------------------------------------------------------------------


class GlowBank:
    """Blurs one mask with several Gaussian radii, sharing a single forward FFT.

    Blurring in floating point keeps the faint outer glow free of the banding that blurred 8-bit
    masks would show.
    """

    def __init__(self, mask: np.ndarray, max_sigma: float) -> None:
        self._height, self._width = mask.shape
        self._pad = int(math.ceil(4.0 * max_sigma))
        padded = np.pad(mask.astype(np.float64), self._pad)
        self._shape = padded.shape
        self._spectrum = np.fft.rfft2(padded)
        self._fy = np.fft.fftfreq(self._shape[0])[:, None]
        self._fx = np.fft.rfftfreq(self._shape[1])[None, :]

    def blur(self, sigma: float) -> np.ndarray:
        """Returns the mask convolved with a Gaussian of standard deviation `sigma` pixels."""
        kernel = np.exp(-2.0 * (math.pi * sigma) ** 2 * (self._fx**2 + self._fy**2))
        full = np.fft.irfft2(self._spectrum * kernel, s=self._shape)
        pad = self._pad
        return full[pad : pad + self._height, pad : pad + self._width].astype(np.float32)


def _screen(base: np.ndarray, light: np.ndarray) -> np.ndarray:
    """Screen blend: brightens `base` by `light` without ever exceeding 1."""
    return 1.0 - (1.0 - base) * (1.0 - np.clip(light, 0.0, 1.0))


def _smoothstep(edge0: float, edge1: float, x: np.ndarray) -> np.ndarray:
    t = np.clip((x - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def _tinted_layer(colour: Color, weight: np.ndarray) -> np.ndarray:
    """Spreads a single-channel weight map over the three colour channels."""
    return weight[..., None] * np.array(colour, dtype=np.float32)


# --------------------------------------------------------------------------------------------
# Compositing
# --------------------------------------------------------------------------------------------


def render(palette: Palette) -> np.ndarray:
    """Renders one appearance as float32 RGB in 0...1, shaped (ICON_SIZE, ICON_SIZE, 3)."""
    rng = np.random.default_rng(SEED)
    axis = (np.arange(ICON_SIZE, dtype=np.float32) + 0.5) / ICON_SIZE - 0.5
    x, y = np.meshgrid(axis, axis)
    radius = np.hypot(x, y)

    # Sky: a slightly tilted vertical gradient with a faint indigo haze around the star.
    t = _smoothstep(0.02, 1.0, (y + 0.5) * 0.85 + (x + 0.5) * 0.15)
    top = np.array(palette.sky_top, dtype=np.float32)
    bottom = np.array(palette.sky_bottom, dtype=np.float32)
    image = top + (bottom - top) * t[..., None]
    haze = np.exp(-(radius**2) / (2.0 * 0.23**2))
    image = _screen(image, _tinted_layer(palette.haze, haze * palette.haze_strength))

    star = _star_coverage(STAR_HALF_LENGTH, STAR_SHARPNESS)
    reticle = _reticle_coverage()
    field = _field_stars(rng, palette.field_star_strength)

    # Small stars with a faint bloom.
    field_bank = GlowBank(field.max(axis=2), 12.0)
    bloom = field_bank.blur(3.0) * 0.9 + field_bank.blur(11.0) * 0.5
    image = _screen(image, field)
    image = _screen(image, _tinted_layer((0.85, 0.90, 1.00), bloom))

    # Reticle with a soft halo, drawn beneath the star.
    reticle_bank = GlowBank(reticle, 6.0)
    ring_light = reticle * 0.9 + reticle_bank.blur(2.5) * 0.9 + reticle_bank.blur(6.0) * 0.35
    image = _screen(image, _tinted_layer(palette.ring, ring_light * palette.ring_strength))

    # Star glow: a tight warm bloom out to a wide indigo halo.
    star_bank = GlowBank(star, 0.20 * ICON_SIZE)
    near = star_bank.blur(0.010 * ICON_SIZE) * 0.55 + star_bank.blur(0.026 * ICON_SIZE) * 0.50
    mid = star_bank.blur(0.060 * ICON_SIZE) * 0.70
    far = star_bank.blur(0.130 * ICON_SIZE) * 1.00 + star_bank.blur(0.200 * ICON_SIZE) * 0.70
    image = _screen(image, _tinted_layer(palette.glow_far, far * palette.glow_gain))
    image = _screen(image, _tinted_layer(palette.glow_near, (mid + near) * palette.glow_gain))

    # Star body: white-hot centre fading to gold along the spikes, plus a hot centre point.
    blend = _smoothstep(0.0, 0.55, np.clip(radius / STAR_HALF_LENGTH, 0.0, 1.0))[..., None]
    core = np.array(palette.star_core, dtype=np.float32)
    edge = np.array(palette.star_edge, dtype=np.float32)
    image = _screen(image, star[..., None] * (core + (edge - core) * blend))
    hot = np.exp(-(radius**2) / (2.0 * 0.014**2))
    image = _screen(image, _tinted_layer(palette.star_core, hot * 0.9))
    return np.clip(image, 0.0, 1.0)


def tinted(dark_render: np.ndarray) -> np.ndarray:
    """The tinted appearance: luminance of the dark artwork, contrast-stretched onto near black.

    iOS recolours this image with the user's tint, mapping luminance to tint intensity, so the sky
    must stay dark and the star must reach full white.
    """
    weights = np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
    luma = np.clip((dark_render @ weights - 0.02) / 0.80, 0.0, 1.0) ** 0.85
    return np.repeat(luma[..., None], 3, axis=2)


def quantise(image: np.ndarray, rng: np.random.Generator) -> Image.Image:
    """Converts to 8-bit RGB, adding triangular dither (shared by all channels) against banding."""
    noise = rng.random(image.shape[:2]) + rng.random(image.shape[:2]) - 1.0
    scaled = image * 255.0 + noise[..., None]
    return Image.fromarray(np.clip(np.rint(scaled), 0, 255).astype(np.uint8), mode="RGB")


def srgb_profile() -> bytes:
    """The built-in sRGB profile with a fixed header date, so repeated runs write identical PNGs."""
    profile = bytearray(ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes())
    profile[24:36] = struct.pack(">6H", *PROFILE_DATE)  # ICC header: dateTimeNumber at offset 24
    return bytes(profile)


def save_png(image: Image.Image, path: Path, profile: bytes) -> None:
    """Writes an opaque ICON_SIZE x ICON_SIZE RGB PNG with an embedded colour profile."""
    if image.mode != "RGB" or image.size != (ICON_SIZE, ICON_SIZE):
        raise ValueError(f"expected {ICON_SIZE}x{ICON_SIZE} RGB, got {image.size} {image.mode}")
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=True, icc_profile=profile)


def contact_sheet(icons: list[Image.Image]) -> Image.Image:
    """Previews the icons with the iOS rounded-corner mask on a neutral background."""
    tile, gap, supersample = 360, 40, 4
    sheet = Image.new("RGB", (gap + len(icons) * (tile + gap), tile + 2 * gap), (96, 96, 100))
    mask = Image.new("L", (tile * supersample,) * 2, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, tile * supersample - 1, tile * supersample - 1],
        radius=round(tile * 0.2237) * supersample,
        fill=255,
    )
    mask = mask.resize((tile, tile), Image.Resampling.LANCZOS)
    for index, icon in enumerate(icons):
        preview = icon.resize((tile, tile), Image.Resampling.LANCZOS)
        sheet.paste(preview, (gap + index * (tile + gap), gap), mask)
    return sheet


def main(argv: list[str] | None = None) -> int:
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description="Render the Lodestar app icon variants.")
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=root / "Lodestar" / "Resources" / "Assets.xcassets" / "AppIcon.appiconset",
        help="directory that receives AppIcon.png, AppIcon-Dark.png and AppIcon-Tinted.png",
    )
    parser.add_argument(
        "--contact-sheet",
        type=Path,
        help="also write a preview PNG showing the three icons with rounded corners",
    )
    args = parser.parse_args(argv)

    dither = np.random.default_rng(SEED + 1)
    dark_render = render(DARK)
    images = {
        "AppIcon.png": quantise(render(DEFAULT), dither),
        "AppIcon-Dark.png": quantise(dark_render, dither),
        "AppIcon-Tinted.png": quantise(tinted(dark_render), dither),
    }
    profile = srgb_profile()
    for name, image in images.items():
        save_png(image, args.output_dir / name, profile)
        print(f"wrote {args.output_dir / name}")
    if args.contact_sheet is not None:
        contact_sheet(list(images.values())).save(args.contact_sheet, format="PNG")
        print(f"wrote {args.contact_sheet}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
