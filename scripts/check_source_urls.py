#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (C) 2026 Dish contributors.
"""Fails when a source the CMake files fetch is not what its pin says.

Usage: check_source_urls.py [CMakeLists.txt ...]

Reads every FetchContent_Declare in app/src/main/cpp/CMakeLists.txt and app/src/test/cpp/CMakeLists.txt
(or the files named), downloads each URL it lists and compares the SHA-256 with its URL_HASH. A URL
that does not answer, or answers with other bytes, is a fallback that would fail the build the day
the first one is down. version-consistency.yml runs it weekly and on a change to either file; it is
not part of the per-pull-request gates because it downloads the sources.
"""

import hashlib
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_FILES = ["app/src/main/cpp/CMakeLists.txt", "app/src/test/cpp/CMakeLists.txt"]
DECLARE = re.compile(r"FetchContent_Declare\(\s*(\w+)(.*?)\n\)|FetchContent_Declare\(\s*(\w+)([^)]*)\)", re.DOTALL)
URL_LINE = re.compile(r"https?://\S+")
HASH = re.compile(r"URL_HASH\s+SHA256=([0-9a-f]{64})")


def pinned_sources(text):
    """[(name, [url, ...], sha256)] for each FetchContent_Declare in text."""
    found = []
    for match in DECLARE.finditer(text):
        name = match.group(1) or match.group(3)
        body = match.group(2) if match.group(1) else match.group(4)
        digest = HASH.search(body)
        if not digest:
            raise ValueError(f"FetchContent_Declare({name}) has no URL_HASH SHA256=")
        urls = URL_LINE.findall(body.split("URL_HASH")[0])
        found.append((name, urls, digest.group(1)))
    return found


def download(url):
    request = urllib.request.Request(url, headers={"User-Agent": "dish-source-check"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def problem_with(url, expected, fetch=download):
    try:
        data = fetch(url)
    except OSError as error:
        return f"{url}: {error}"
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected:
        return f"{url}: sha256 {actual}, the pin says {expected}"
    return None


def main(argv):
    files = argv[1:] or DEFAULT_FILES
    problems = []
    for name in files:
        path = Path(name) if Path(name).is_absolute() else ROOT / name
        for source, urls, digest in pinned_sources(path.read_text()):
            for url in urls:
                problem = problem_with(url, digest)
                print(f"{'FAIL' if problem else 'ok  '} {source} {url}")
                if problem:
                    problems.append(problem)
    for line in problems:
        print(f"::error::{line}", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
