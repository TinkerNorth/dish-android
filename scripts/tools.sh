#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (C) 2026 Dish contributors.
# The pinned tools the gates run with, installed once into build/tools and sourced by
# check-format.sh, so ci-local.sh and android-ci.yml run the same formatter. A bump is a
# deliberate commit that reformats the tree with it, here and in CONTRIBUTING.md together; the
# C++ repos of the fleet (satellite, dish-linux, dish-windows, dish-android) move together.

CLANG_FORMAT_VERSION="23.1.5"

# The security gates' tools. _security.yml pins the same versions with the linux-amd64 SHA-256s,
# and scripts/tools_pins_test.py holds the two together; the rows below add the darwin-arm64
# ones for a Mac, each checked against the upstream checksum file.
OSV_SCANNER_VERSION="2.7.1"
GITLEAKS_VERSION="8.30.1"
PYYAML_VERSION="6.0.4"

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

# fetch_binary <name> <url> <sha256>: downloads one file into $TOOLS/bin, verified, executable.
fetch_binary() {
    local name="$1" url="$2" sha="$3" dest="$TOOLS/bin/$1"
    if [ -x "$dest" ] && [ "$(cat "$dest.sha256" 2>/dev/null)" = "$sha" ]; then
        return
    fi
    tools_log "fetching $name"
    mkdir -p "$TOOLS/bin"
    curl -fsSL --retry 3 -o "$dest" "$url"
    echo "$sha  $dest" | shasum -a 256 -c -
    chmod +x "$dest"
    echo "$sha" > "$dest.sha256"
}

# tools_platform: darwin-arm64 or linux-amd64, the two hosts the pins cover.
tools_platform() {
    case "$(uname -s)-$(uname -m)" in
        Darwin-arm64) echo darwin-arm64 ;;
        Linux-x86_64) echo linux-amd64 ;;
        *) echo "no pinned security tools for $(uname -s) $(uname -m)" >&2; return 1 ;;
    esac
}

# ensure_osv_scanner: leaves the pinned OSV-Scanner on OSV_SCANNER.
ensure_osv_scanner() {
    local platform sha
    platform="$(tools_platform)" || return 1
    case "$platform" in
        darwin-arm64) sha="b22c02877a811c8f78956e8643efa554932b7378f590d13f8e97d4e9448a29f3" ;;
        linux-amd64) sha="fb7dab19a8bd9bbe5c3189f09d4b7ee1820c9553a23bac202648a56efd2f6ee8" ;;
    esac
    fetch_binary osv-scanner \
        "https://github.com/google/osv-scanner/releases/download/v${OSV_SCANNER_VERSION}/osv-scanner_${platform//-/_}" \
        "$sha"
    OSV_SCANNER="$TOOLS/bin/osv-scanner"
}

# ensure_gitleaks: leaves the pinned gitleaks on GITLEAKS.
ensure_gitleaks() {
    local platform sha asset archive
    platform="$(tools_platform)" || return 1
    case "$platform" in
        darwin-arm64) sha="b40ab0ae55c505963e365f271a8d3846efbc170aa17f2607f13df610a9aeb6a5"; asset="darwin_arm64" ;;
        linux-amd64) sha="551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb"; asset="linux_x64" ;;
    esac
    GITLEAKS="$TOOLS/bin/gitleaks"
    if [ -x "$GITLEAKS" ] && [ "$(cat "$GITLEAKS.sha256" 2>/dev/null)" = "$sha" ]; then
        return
    fi
    tools_log "fetching gitleaks"
    mkdir -p "$TOOLS/bin"
    archive="$TOOLS/gitleaks.tar.gz"
    curl -fsSL --retry 3 -o "$archive" \
        "https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_${asset}.tar.gz"
    echo "$sha  $archive" | shasum -a 256 -c -
    tar -xzf "$archive" -C "$TOOLS/bin" gitleaks
    echo "$sha" > "$GITLEAKS.sha256"
}

# ensure_pyyaml: leaves a Python with the pinned PyYAML on SECURITY_PYTHON, for security-allowlist.py
# (which wants Python 3.11 or newer).
ensure_pyyaml() {
    local venv="$TOOLS/pyyaml-venv"
    SECURITY_PYTHON="$venv/bin/python"
    if [ -x "$SECURITY_PYTHON" ] && "$SECURITY_PYTHON" -c "import yaml, sys; sys.exit(0 if yaml.__version__ == '$PYYAML_VERSION' else 1)" 2>/dev/null; then
        return
    fi
    command -v python3 >/dev/null 2>&1 || { echo "python3 is required to install PyYAML $PYYAML_VERSION" >&2; return 1; }
    tools_log "installing PyYAML $PYYAML_VERSION"
    python3 -m venv "$venv"
    "$venv/bin/python" -m pip install --quiet --disable-pip-version-check --only-binary :all: "pyyaml==$PYYAML_VERSION"
}
