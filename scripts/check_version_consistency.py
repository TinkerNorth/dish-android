#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (C) 2026 Dish contributors.
"""The version is the tag. This checks what must agree with it before a release can go out.

Usage: check_version_consistency.py [<tag>]

Without a tag (a pull request) the CHANGELOG must be well formed: an optional `## [Unreleased]`
first, then `## [X.Y.Z] - YYYY-MM-DD` headings, newest first. With a tag (X.Y.Z, no prefix) the
newest released heading must be that tag. On success the version code, X*10000 + Y*100 + Z, the
number release.yml and app/build.gradle.kts both derive, is printed on stdout; the workflow hands
it to scripts/check_play_metadata.py as EXPECTED_VERSION_CODE, which then fails a locale missing
play/metadata/android/<locale>/changelogs/<code>.txt.
"""

import re
import sys
from pathlib import Path

TAG = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
HEADING = re.compile(r"^## \[(?P<name>[^\]]+)\](?P<rest>.*)$", re.MULTILINE)
RELEASED = re.compile(r" - \d{4}-\d{2}-\d{2}$")
CHANGELOG = Path(__file__).resolve().parent.parent / "CHANGELOG.md"


def version_code(tag):
    match = TAG.match(tag)
    if not match:
        raise ValueError(f"'{tag}' is not X.Y.Z (three numbers, no prefix, no leading zeros)")
    major, minor, patch = (int(part) for part in match.groups())
    if minor > 99 or patch > 99:
        raise ValueError(f"'{tag}' has a minor or patch above 99; the version code would collide")
    return major * 10000 + minor * 100 + patch


def released(text):
    return [m.group("name") for m in HEADING.finditer(text) if m.group("name") != "Unreleased"]


def newest_released(text):
    names = released(text)
    return names[0] if names else None


def problems(text, tag):
    found = []
    headings = list(HEADING.finditer(text))
    for index, heading in enumerate(headings):
        name, rest = heading.group("name"), heading.group("rest")
        if name == "Unreleased":
            if index != 0:
                found.append("CHANGELOG.md: [Unreleased] must be the first heading")
        elif not TAG.match(name):
            found.append(f"CHANGELOG.md: heading [{name}] is not X.Y.Z")
        elif not RELEASED.match(rest):
            found.append(f"CHANGELOG.md: heading [{name}] needs ' - YYYY-MM-DD' after it")
    names = [name for name in released(text) if TAG.match(name)]
    keys = [tuple(int(part) for part in name.split(".")) for name in names]
    if keys != sorted(keys, reverse=True) or len(set(keys)) != len(keys):
        found.append("CHANGELOG.md: released headings must be newest first, each version once")
    if not names:
        found.append("CHANGELOG.md: no released [X.Y.Z] heading")
    if tag is not None:
        try:
            version_code(tag)
        except ValueError as error:
            found.append(str(error))
        else:
            if names and names[0] != tag:
                found.append(
                    f"tag {tag} but the newest CHANGELOG heading is [{names[0]}]; "
                    f"the release notes for {tag} must be the top released entry"
                )
    return found


def main(argv):
    if len(argv) > 2:
        print(__doc__, file=sys.stderr)
        return 2
    tag = argv[1] if len(argv) == 2 else None
    text = CHANGELOG.read_text(encoding="utf-8")
    found = problems(text, tag)
    for line in found:
        print(f"::error::{line}", file=sys.stderr)
    if found:
        return 1
    print(version_code(tag or newest_released(text)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
