#!/usr/bin/env python3
"""Lint play/metadata against Play Console limits before anything ships.

Errors (exit 1): text over Play's length caps, screenshot counts outside
2..8, PNG dimensions outside the allowed range for the form factor,
wrongly sized feature graphics or icons.
Warnings (exit 0): assets Play treats as optional-but-recommended that are
known to be pending (per-locale feature graphic, the 512x512 icon).

Zero dependencies: PNG dimensions come straight from the IHDR chunk.
"""

import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "play" / "metadata" / "android"

TEXT_LIMITS = {
    "title.txt": 30,
    "short_description.txt": 80,
    "full_description.txt": 4000,
}
CHANGELOG_LIMIT = 500

SCREENSHOT_DIRS = {
    "phoneScreenshots": (320, 3840),
    "sevenInchScreenshots": (320, 3840),
    "tenInchScreenshots": (1080, 7680),
}
SHOT_COUNT_RANGE = (2, 8)
FEATURE_GRAPHIC_SIZE = (1024, 500)
ICON_SIZE = (512, 512)

errors = []
warnings = []


def png_size(path):
    with open(path, "rb") as f:
        header = f.read(24)
    if len(header) < 24 or header[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    width, height = struct.unpack(">II", header[16:24])
    return width, height


def check_locale(locale_dir):
    locale = locale_dir.name
    for name, limit in TEXT_LIMITS.items():
        path = locale_dir / name
        if not path.exists():
            errors.append(f"{locale}: missing {name}")
            continue
        length = len(path.read_text(encoding="utf-8").rstrip("\n"))
        if length > limit:
            errors.append(f"{locale}/{name}: {length} chars exceeds Play's {limit}")
    for changelog in sorted((locale_dir / "changelogs").glob("*.txt")):
        length = len(changelog.read_text(encoding="utf-8").rstrip("\n"))
        if length > CHANGELOG_LIMIT:
            errors.append(
                f"{locale}/changelogs/{changelog.name}: {length} chars exceeds Play's {CHANGELOG_LIMIT}"
            )
    images = locale_dir / "images"
    for dirname, (side_min, side_max) in SCREENSHOT_DIRS.items():
        shots = sorted((images / dirname).glob("*.png"))
        low, high = SHOT_COUNT_RANGE
        if not shots:
            errors.append(f"{locale}/images/{dirname}: no screenshots committed")
            continue
        if not low <= len(shots) <= high:
            errors.append(
                f"{locale}/images/{dirname}: {len(shots)} screenshots, Play allows {low} to {high}"
            )
        for shot in shots:
            size = png_size(shot)
            if size is None:
                errors.append(f"{locale}: {shot.name} in {dirname} is not a valid PNG")
                continue
            w, h = size
            ratio_ok = (w * 9 == h * 16) or (w * 16 == h * 9)
            if not ratio_ok:
                errors.append(f"{locale}/images/{dirname}/{shot.name}: {w}x{h} is not 16:9 or 9:16")
            if not (side_min <= w <= side_max and side_min <= h <= side_max):
                errors.append(
                    f"{locale}/images/{dirname}/{shot.name}: {w}x{h} outside {side_min}..{side_max} per side"
                )
    graphics = sorted((images / "featureGraphic").glob("*.png"))
    if not graphics:
        warnings.append(f"{locale}: no feature graphic committed")
    for graphic in graphics:
        if png_size(graphic) != FEATURE_GRAPHIC_SIZE:
            errors.append(
                f"{locale}/images/featureGraphic/{graphic.name}: must be "
                f"{FEATURE_GRAPHIC_SIZE[0]}x{FEATURE_GRAPHIC_SIZE[1]}, got {png_size(graphic)}"
            )
    icons = sorted((images / "icon").glob("*.png"))
    if not icons:
        warnings.append(f"{locale}: no 512x512 store icon committed")
    for icon in icons:
        if png_size(icon) != ICON_SIZE:
            errors.append(f"{locale}/images/icon/{icon.name}: must be 512x512, got {png_size(icon)}")


def main():
    if not ROOT.is_dir():
        print(f"::error::{ROOT} not found")
        return 1
    locales = sorted(d for d in ROOT.iterdir() if d.is_dir())
    for locale_dir in locales:
        check_locale(locale_dir)
    for message in warnings:
        print(f"::warning::{message}")
    for message in errors:
        print(f"::error::{message}")
    print(f"Checked {len(locales)} locales: {len(errors)} errors, {len(warnings)} warnings.")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
