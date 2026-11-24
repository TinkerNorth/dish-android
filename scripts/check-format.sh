#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (C) 2026 Dish contributors.
# Fails when a C or C++ file of the JNI layer is not formatted by the pinned clang-format.
#   scripts/check-format.sh          check only
#   scripts/check-format.sh --fix    rewrite the files in place
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=scripts/tools.sh
. scripts/tools.sh

mode=(--dry-run --Werror)
case "${1:-}" in
  "") ;;
  --fix) mode=(-i) ;;
  *) echo "usage: check-format.sh [--fix]" >&2; exit 2 ;;
esac

ensure_clang_format
find app/src/main/cpp -type f \( -name '*.cpp' -o -name '*.h' -o -name '*.c' \) -print0 \
  | xargs -0 "$CLANG_FORMAT" "${mode[@]}"
