#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (C) 2026 Dish contributors.
# Fails when app/gradle.lockfile is stale: regenerates it from the version catalog (resolving the
# three flavors' release runtime classpaths with --write-locks) and fails if that changed the file.
# After a dependency change, commit the regenerated file:
#   ./gradlew :app:resolveShippedDependencies --write-locks
# GRADLE_FLAGS carries extra Gradle arguments (ci-local.sh passes --no-daemon).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

read -r -a flags <<< "${GRADLE_FLAGS:-}"
./gradlew "${flags[@]}" :app:resolveShippedDependencies --write-locks
if ! git diff --exit-code -- app/gradle.lockfile; then
  echo "::error::app/gradle.lockfile is stale: run ./gradlew :app:resolveShippedDependencies --write-locks and commit it" >&2
  exit 1
fi
