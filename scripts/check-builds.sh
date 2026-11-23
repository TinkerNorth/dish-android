#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (C) 2026 Dish contributors.
# Builds the APKs and the bundle and inspects what came out, so a build that would break at tag
# time breaks on the pull request instead.
#
#   scripts/check-builds.sh debug     the three debug APKs; each carries its flavor's ABIs
#   scripts/check-builds.sh release   the minified release builds of every distribution, unsigned
#                                     (the GitHub APK, the Play APK and bundle, the Amazon APK);
#                                     each carries its ABIs and keeps every member native code
#                                     resolves by name (R8 strips what no Kotlin code calls)
#   scripts/check-builds.sh stage     after `release`: copies the unsigned GitHub APK, the Play
#                                     bundle and the Play R8 mapping into dist/, the files the
#                                     hardening dry run (android-ci.yml) scans and signs, with the
#                                     components SBOM scripts/sbom.py writes beside them
#
# GRADLE_FLAGS carries extra Gradle arguments (ci-local.sh passes --no-daemon). What a pull
# request cannot exercise is in the header of ci-local.sh: signing, Crashlytics upload, the
# baseline profile and the store uploads.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

ABIS=(armeabi-v7a arm64-v8a x86_64)
read -r -a flags <<< "${GRADLE_FLAGS:-}"

case "${1:-}" in
  debug)
    ./gradlew "${flags[@]}" :app:assembleGithubDebug :app:assemblePlayDebug :app:assembleAmazonDebug
    for flavor in github play amazon; do
      python3 scripts/check_apk_abis.py "app/build/outputs/apk/$flavor/debug/app-$flavor-debug.apk" "${ABIS[@]}"
    done
    ;;
  release)
    ./gradlew "${flags[@]}" :app:assembleGithubRelease :app:assemblePlayRelease :app:bundlePlayRelease :app:assembleAmazonRelease
    outputs=(
      app/build/outputs/apk/github/release/app-github-release-unsigned.apk
      app/build/outputs/apk/play/release/app-play-release-unsigned.apk
      app/build/outputs/bundle/playRelease/app-play-release.aab
      app/build/outputs/apk/amazon/release/app-amazon-release-unsigned.apk
    )
    for artifact in "${outputs[@]}"; do
      python3 scripts/check_apk_abis.py "$artifact" "${ABIS[@]}"
      python3 scripts/check_jni_keeps.py "$artifact"
    done
    ;;
  stage)
    rm -rf dist
    mkdir -p dist
    cp app/build/outputs/apk/github/release/app-github-release-unsigned.apk dist/dish-unsigned.apk
    cp app/build/outputs/bundle/playRelease/app-play-release.aab dist/dish-unsigned.aab
    cp app/build/outputs/mapping/playRelease/mapping.txt dist/dish-unsigned-play-mapping.txt
    # What the artifacts hold, written from the lockfile and CMake (scripts/sbom.py), so the hardening's
    # product SBOM names the libraries inside an APK and a bundle that Syft cannot look into.
    python3 scripts/sbom.py write dist/dish-android-components.spdx.json --version "${DISH_VERSION_NAME:-0.0.0-unreleased}"
    python3 scripts/sbom.py check dist/dish-android-components.spdx.json
    ls -l dist
    ;;
  *)
    echo "usage: check-builds.sh debug|release|stage" >&2
    exit 2
    ;;
esac
