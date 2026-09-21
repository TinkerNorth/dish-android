#!/usr/bin/env python3
"""Refuse a capture set that carries a foreign light surface.

Usage: check_capture_clean.py <dir>
The harness captures in night mode, so every Dish screen is dark: across the
200 clean captures of the 2.2.0 release runs the brightest was 3.5 percent
near-white pixels, while Android's "isn't responding" dialog over the app
measured 17 percent and more. A PNG above LIMIT is a dialog, a crash sheet or
some other window that must never reach the store listing, and the leg fails
here instead of shipping it (release.yml's Play upload replaces the committed
set with these captures). Requires Pillow.
"""

import sys
from pathlib import Path

from PIL import Image

LIMIT = 0.10
WHITE = 225
STRIDE = 8


def light_fraction(path):
    with Image.open(path) as img:
        small = img.convert("RGB").resize((max(1, img.width // STRIDE), max(1, img.height // STRIDE)))
    data = small.tobytes()
    light = sum(1 for i in range(0, len(data), 3) if data[i] > WHITE and data[i + 1] > WHITE and data[i + 2] > WHITE)
    return light / (len(data) // 3)


def main():
    root = Path(sys.argv[1])
    paths = sorted(root.rglob("*.png"))
    if not paths:
        print(f"::error::no PNG captures under {root}")
        return 1
    offenders = [(path, fraction) for path in paths if (fraction := light_fraction(path)) > LIMIT]
    for path, fraction in offenders:
        print(f"::error::{path}: {fraction:.0%} near-white pixels; a system dialog or another foreign window is over the app")
    print(f"Checked {len(paths)} captures: {len(offenders)} with a foreign light surface (limit {LIMIT:.0%}).")
    return 1 if offenders else 0


if __name__ == "__main__":
    sys.exit(main())
