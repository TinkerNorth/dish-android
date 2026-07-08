#!/usr/bin/env python3
"""Center-crop captured screenshots to Play's accepted dimensions.

Usage: crop_play_screenshots.py <dir> <target_width> <target_height>
Crops every PNG under <dir> in place. The target orientation follows
each image: a landscape capture against a portrait target (or the
reverse) crops to the swapped dimensions, so one invocation covers a
mixed portrait/landscape set. Requires Pillow.
"""

import sys
from pathlib import Path

from PIL import Image


def main():
    root, width, height = Path(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])
    for path in sorted(root.rglob("*.png")):
        with Image.open(path) as img:
            tw, th = width, height
            if (img.width < img.height) != (tw < th):
                tw, th = th, tw
            if (img.width, img.height) == (tw, th):
                continue
            if img.width < tw or img.height < th:
                print(f"::warning::{path} is {img.width}x{img.height}, smaller than {tw}x{th}; skipped")
                continue
            x = (img.width - tw) // 2
            y = (img.height - th) // 2
            img.crop((x, y, x + tw, y + th)).save(path)
            print(f"cropped {path} to {tw}x{th}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
