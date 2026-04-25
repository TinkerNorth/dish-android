#!/bin/bash
# Point this repo's git hooks at the tracked .githooks/ directory so the
# pre-commit lint/format checks run for every contributor after a single
# one-time setup. Idempotent — safe to re-run.
set -euo pipefail

cd "$(dirname "$0")/.."

git config core.hooksPath .githooks
chmod +x .githooks/*

echo "✓ core.hooksPath → .githooks"
echo
echo "Recommended tooling (install once):"
echo "  macOS:    brew install clang-format"
echo "  Linux:    sudo apt install clang-format    # or dnf install clang-tools-extra"
echo "  Windows:  choco install llvm   (or install via LLVM/MSYS2)"
echo
echo "Kotlin formatting (ktlint, detekt) runs in CI via './gradlew' tasks; the"
echo "pre-commit hook only handles JNI C/C++ to keep it fast."
