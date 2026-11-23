#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (C) 2026 Dish contributors.
# The clang-tidy gate: the pinned clang-tidy (scripts/tools.sh) over every C++ source of the JNI layer
# under app/src/main/cpp, with the fleet's .clang-tidy and every finding an error
# (--warnings-as-errors='*' on the command line, so the shared .clang-tidy stays unforked).
#
# It reads the compile database the Android debug build leaves for arm64-v8a, so what it checks is
# what the NDK compiles, JNI included, with the controller tables the build generated. Run
# scripts/check-builds.sh debug first. Headers are covered through the .clang-tidy header filter;
# passing a .h directly trips clang-diagnostic-pragma-once-outside-header.
#
#   scripts/check-tidy.sh [file.cpp ...]     the whole sweep, or just the named sources
# JOBS sets the number of concurrent clang-tidy processes (default 4).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=scripts/tools.sh
. scripts/tools.sh

ensure_clang_tidy

database=""
for candidate in app/.cxx/Debug/*/arm64-v8a; do
  [ -f "$candidate/compile_commands.json" ] && database="$candidate"
done
if [ -z "$database" ]; then
  echo "no compile_commands.json under app/.cxx/Debug/*/arm64-v8a: run scripts/check-builds.sh debug first" >&2
  exit 1
fi
if [ ! -f "$database/generated/known_devices.generated.h" ]; then
  echo "$database has no generated/known_devices.generated.h: the debug build did not finish" >&2
  exit 1
fi

if [ "$#" -gt 0 ]; then
  files=("$@")
else
  files=()
  while IFS= read -r file; do files+=("$file"); done < <(find app/src/main/cpp -maxdepth 1 -name '*.cpp' | LC_ALL=C sort)
fi

echo "clang-tidy $CLANG_TIDY_VERSION over ${#files[@]} files, database $database"
printf '%s\0' "${files[@]}" \
  | xargs -0 -n1 -P"${JOBS:-4}" "$CLANG_TIDY" -p "$database" --quiet --warnings-as-errors='*'
