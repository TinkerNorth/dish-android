#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (C) 2026 Dish contributors.
# Fails when app/gradle.lockfile or the in-app licence list is stale: regenerates both from the
# version catalog (resolving the three flavors' release runtime classpaths with --write-locks, then
# writing the licence list from the same classpaths) and fails if that changed either file.
# After a dependency change, commit the regenerated files:
#   ./gradlew :app:resolveShippedDependencies --write-locks :app:generateLicenseManifest
# GRADLE_FLAGS carries extra Gradle arguments (ci-local.sh passes --no-daemon).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

read -r -a flags <<< "${GRADLE_FLAGS:-}"
files=(app/gradle.lockfile app/src/main/assets/licenses/licenses.json)
./gradlew "${flags[@]}" --no-configuration-cache :app:resolveShippedDependencies --write-locks :app:generateLicenseManifest
if ! git diff --exit-code -- "${files[@]}"; then
  echo "::error::a dependency lock or the licence list is stale: run ./gradlew :app:resolveShippedDependencies --write-locks :app:generateLicenseManifest and commit both" >&2
  exit 1
fi
