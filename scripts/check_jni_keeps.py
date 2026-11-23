#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (C) 2026 Dish contributors.
"""Fail a minified APK whose dex lacks a member the JNI resolves by name.

Usage: check_jni_keeps.py <app.apk | app.aab> [<app/src/main/cpp>]

Reads every lookup app/src/main/cpp makes by name (a bridge's resolveStaticMethod, the attach's
UsbInterfaceClaim fields), the same ones NativeLookupKeepRulesTest ties to proguard-rules.pro,
and checks with the SDK's dexdump (DEXDUMP, or the newest build-tools under ANDROID_HOME) that
each is in the APK under its own class, name and descriptor. A release build that fails here
would run with a silent bridge, so it must not ship.
"""

import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

INSTALL_EXPORT = re.compile(r"Java_(com_tinkernorth_dish(?:_[a-z0-9]+)*)_([A-Z][A-Za-z0-9]*)_nativeInstall\b")
STATIC_METHOD = re.compile(r'resolveStaticMethod\(\s*env,\s*\w+,\s*"(\w+)",\s*"(\w+)",\s*"([^"]+)"\s*\)')
CLAIM_INT = re.compile(r'readClaimField\(\s*env,\s*cls,\s*claim,\s*"(\w+)"')
CLAIM_FLAG = re.compile(r'readClaimFlag\(\s*env,\s*cls,\s*claim,\s*"(\w+)"')
CLAIM_CLASS = "com.tinkernorth.dish.core.jni.UsbInterfaceClaim"
FEWEST_LOOKUPS = 20

DEX_ENTRY = re.compile(r"^(?:base/dex/)?classes\d*\.dex$")
CLASS_LINE = re.compile(r"^\s*Class descriptor\s*:\s*'L([^;]+);'")
NAME_LINE = re.compile(r"^\s*name\s*:\s*'([^']+)'")
TYPE_LINE = re.compile(r"^\s*type\s*:\s*'([^']+)'")


def native_lookups(cpp_dir: Path) -> list:
    text = "\n".join(p.read_text() for p in sorted(cpp_dir.rglob("*")) if p.suffix in (".cpp", ".h"))
    classes = {m.group(2): m.group(1).replace("_", ".") + "." + m.group(2) for m in INSTALL_EXPORT.finditer(text)}
    lookups = [(classes[m.group(1)], m.group(2), m.group(3)) for m in STATIC_METHOD.finditer(text)]
    lookups += [(CLAIM_CLASS, m.group(1), "I") for m in CLAIM_INT.finditer(text)]
    lookups += [(CLAIM_CLASS, m.group(1), "Z") for m in CLAIM_FLAG.finditer(text)]
    return lookups


def dexdump_path() -> str:
    given = os.environ.get("DEXDUMP")
    if given:
        return given
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        sys.exit("check_jni_keeps: set DEXDUMP or ANDROID_HOME")
    tools = sorted((Path(sdk) / "build-tools").glob("*/dexdump"), key=lambda p: [int(x) if x.isdigit() else x for x in re.split(r"[.-]", p.parent.name)])
    if not tools:
        sys.exit("check_jni_keeps: no dexdump under " + sdk + "/build-tools")
    return str(tools[-1])


# An APK's dex sits at its root, a bundle's under base/dex; dexdump reads each one extracted.
def dex_dumps(package: Path) -> str:
    dexdump = dexdump_path()
    with zipfile.ZipFile(package) as archive, tempfile.TemporaryDirectory() as scratch:
        entries = [name for name in archive.namelist() if DEX_ENTRY.match(name)]
        if not entries:
            sys.exit(f"check_jni_keeps: no dex in {package}")
        dumps = []
        for name in entries:
            dex = Path(archive.extract(name, scratch))
            dumps.append(subprocess.run([dexdump, str(dex)], check=True, capture_output=True, text=True).stdout)
        return "\n".join(dumps)


def dex_members(package: Path) -> set:
    out = dex_dumps(package)
    members = set()
    current = None
    name = None
    for line in out.splitlines():
        cls = CLASS_LINE.match(line)
        if cls:
            current = cls.group(1).replace("/", ".")
            continue
        named = NAME_LINE.match(line)
        if named:
            name = named.group(1)
            continue
        typed = TYPE_LINE.match(line)
        if typed and current and name:
            members.add((current, name, typed.group(1)))
            name = None
    return members


def main() -> int:
    if len(sys.argv) not in (2, 3):
        print(__doc__)
        return 2
    apk = Path(sys.argv[1])
    cpp_dir = Path(sys.argv[2]) if len(sys.argv) == 3 else Path(__file__).resolve().parent.parent / "app/src/main/cpp"
    lookups = native_lookups(cpp_dir)
    if len(lookups) < FEWEST_LOOKUPS:
        print(f"check_jni_keeps: found only {len(lookups)} native lookups under {cpp_dir}; the scan is broken")
        return 1
    members = dex_members(apk)
    missing = [lookup for lookup in lookups if lookup not in members]
    for cls, member, descriptor in missing:
        print(f"MISSING {cls}.{member} {descriptor}")
    if missing:
        print(f"check_jni_keeps: {len(missing)} of {len(lookups)} members native code resolves by name are not in {apk.name}")
        return 1
    print(f"check_jni_keeps: all {len(lookups)} members native code resolves by name are in {apk.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
