#!/usr/bin/env python3
"""Fails unless libsodium was compiled with its 128-bit field arithmetic (HAVE_TI_MODE) on every
64-bit ABI and on no 32-bit one, read from the compile_commands.json AGP writes for each ABI's
native build. libsodium's own configure refuses that mode for clang on 32-bit targets.

    scripts/check_sodium_ti_mode.py [<.cxx dir>]     (default: app/.cxx)
"""

import glob
import json
import os
import sys

WIDE_ABIS = {"arm64-v8a", "x86_64"}
NARROW_ABIS = {"armeabi-v7a", "x86"}
TI_MODE = "HAVE_TI_MODE=1"


def sodium_commands(path):
    with open(path, encoding="utf-8") as f:
        entries = json.load(f)
    return [
        entry.get("command") or " ".join(entry.get("arguments", []))
        for entry in entries
        if "libsodium" in entry["file"].replace("\\", "/")
    ]


def check_build(path, abi):
    commands = sodium_commands(path)
    with_ti = sum(TI_MODE in command for command in commands)
    print(f"{path}: {with_ti} of {len(commands)} libsodium objects use 128-bit arithmetic")
    if not commands:
        print(f"::error::{path} compiles no libsodium source")
        return False
    if abi in WIDE_ABIS and with_ti != len(commands):
        print(f"::error::{abi} must use libsodium's 128-bit arithmetic for every object")
        return False
    if abi in NARROW_ABIS and with_ti:
        print(f"::error::{abi} must not use libsodium's 128-bit arithmetic, which its configure refuses there")
        return False
    return True


def main():
    cxx = sys.argv[1] if len(sys.argv) > 1 else os.path.join("app", ".cxx")
    builds = sorted(glob.glob(os.path.join(cxx, "*", "*", "*", "compile_commands.json")))
    abis = [os.path.basename(os.path.dirname(path)) for path in builds]
    if "armeabi-v7a" not in abis:
        sys.exit(f"::error::no armeabi-v7a native build under {cxx}; build an APK first")
    results = [check_build(path, abi) for path, abi in zip(builds, abis)]
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
