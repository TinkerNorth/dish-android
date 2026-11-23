#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (C) 2026 Dish contributors.
# The pinned tools the gates run with, installed once into build/tools and sourced by
# check-format.sh, so ci-local.sh and android-ci.yml run the same formatter. A bump is a
# deliberate commit that reformats the tree with it, here and in CONTRIBUTING.md together; the
# C++ repos of the fleet (satellite, dish-linux, dish-windows, dish-android) move together.

CLANG_FORMAT_VERSION="23.1.5"

TOOLS="${TOOLS:-build/tools}"

tools_log() { printf '\n== %s ==\n' "$*"; }

# ensure_clang_format: leaves the pinned clang-format on CLANG_FORMAT. A pip wheel, installed into
# a virtual environment under $TOOLS so the host's own Python and formatter are never touched.
ensure_clang_format() {
    local venv="$TOOLS/clang-format-venv"
    CLANG_FORMAT="$venv/bin/clang-format"
    if [ -x "$CLANG_FORMAT" ] && [ "$("$CLANG_FORMAT" --version | sed -n 's/.*version \([0-9.]*\).*/\1/p')" = "$CLANG_FORMAT_VERSION" ]; then
        return
    fi
    command -v python3 >/dev/null 2>&1 || { echo "python3 is required to install clang-format $CLANG_FORMAT_VERSION" >&2; return 1; }
    tools_log "installing clang-format $CLANG_FORMAT_VERSION"
    python3 -m venv "$venv"
    "$venv/bin/python" -m pip install --quiet "clang-format==$CLANG_FORMAT_VERSION"
}
