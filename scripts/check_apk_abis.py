#!/usr/bin/env python3
"""Fails unless a build ships libsatellite.so for exactly the ABIs given, so a dropped or an extra
ABI fails the build that made it. An APK is read twice: its lib/ entries, and the native-code line
of aapt2's badging, which is what a store reads. An AAB is read from its base module's lib/; aapt2
has no badging for a bundle.

    scripts/check_apk_abis.py <apk-or-aab> <abi> [<abi> ...]

aapt2 is $AAPT2, or else the newest one under $ANDROID_HOME (or $ANDROID_SDK_ROOT)/build-tools.
"""

import glob
import os
import re
import subprocess
import sys
import zipfile

LIBRARY = "libsatellite.so"


def packaged_abis(archive, prefix):
    with zipfile.ZipFile(archive) as z:
        names = z.namelist()
    pattern = re.compile("^" + re.escape(prefix) + r"lib/([^/]+)/" + re.escape(LIBRARY) + "$")
    return {m.group(1) for m in map(pattern.match, names) if m}


def build_tools_version(path):
    version = os.path.basename(os.path.dirname(path))
    return tuple(int(part) if part.isdigit() else 0 for part in re.split(r"[.-]", version))


def aapt2_path():
    explicit = os.environ.get("AAPT2")
    if explicit:
        return explicit
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        sys.exit("check_apk_abis: set ANDROID_HOME, ANDROID_SDK_ROOT or AAPT2 to find aapt2")
    tools = os.path.join(sdk, "build-tools", "*")
    found = glob.glob(os.path.join(tools, "aapt2")) + glob.glob(os.path.join(tools, "aapt2.exe"))
    if not found:
        sys.exit(f"check_apk_abis: no aapt2 under {sdk}/build-tools")
    return max(found, key=build_tools_version)


def badging_abis(apk):
    badging = subprocess.run(
        [aapt2_path(), "dump", "badging", apk], check=True, capture_output=True, text=True
    ).stdout
    native_code = next((line for line in badging.splitlines() if line.startswith("native-code:")), "")
    return set(re.findall(r"'([^']+)'", native_code))


def matches(label, found, expected):
    if found == expected:
        print(f"{label}: {' '.join(sorted(found))}")
        return True
    print(f"::error::{label} is {sorted(found)}, expected {sorted(expected)}")
    return False


def check_apk(apk, expected):
    entries = matches(f"{apk} lib/*/{LIBRARY}", packaged_abis(apk, ""), expected)
    badging = matches(f"{apk} aapt2 native-code", badging_abis(apk), expected)
    return entries and badging


def check_bundle(aab, expected):
    return matches(f"{aab} base/lib/*/{LIBRARY}", packaged_abis(aab, "base/"), expected)


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    archive = sys.argv[1]
    expected = set(sys.argv[2:])
    passed = check_bundle(archive, expected) if archive.endswith(".aab") else check_apk(archive, expected)
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
