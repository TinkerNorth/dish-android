#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (C) 2026 Dish contributors.
# Runs the gates android-ci.yml runs, against this worktree, in CI's order: a green run here is a
# green "Build, test, lint, format" job there. Each gate is one command or one script under
# scripts/ that the workflow calls too. A missing tool fails the run, so a green run never hides
# a skipped gate; --allow-missing turns the failure into a notice.
#
#   scripts/ci-local.sh                      every gate that needs no device
#   scripts/ci-local.sh --quick              the fast gates: format, metadata, translations, script
#                                            tests, ktlint, detekt, JVM unit tests
#   scripts/ci-local.sh --with-instrumented  also run the integration tests on the attached
#                                            device or emulator (start one with
#                                            `-read-only -dns-server 8.8.8.8,1.1.1.1`)
#   scripts/ci-local.sh --with-gmd           also run them on the pixel6Api34 managed device
#   scripts/ci-local.sh --with-armv7         on a host that is not Linux, also run the 32-bit ARM
#                                            host tests under qemu in the container (slow: qemu's
#                                            pacing misses the bench's 1 ms deadlines on a laptop,
#                                            so run it on a machine you can leave alone)
#   scripts/ci-local.sh --allow-missing      a missing tool is a notice, not a failure
#
# Environment: a JDK to start Gradle (JAVA_HOME, or found with /usr/libexec/java_home), ANDROID_HOME,
# python3.
# The C++ host tests run through Gradle's :app:nativeTest on Linux; on any other host they run
# in an ubuntu:24.04 container (scripts/native-tests-docker.sh), so Docker is needed there. The
# 32-bit ARM lane runs by default on Linux and with --with-armv7 elsewhere.
#
# CI only, because they need a Linux runner with KVM or a store credential:
#   - the API 35 and API 28 (armeabi-v7a through ARM translation) emulator jobs; --with-instrumented
#     is the local counterpart of the first
#   - the pinned-source check of version-consistency.yml (python3 scripts/check_source_urls.py
#     downloads every source the native build fetches; weekly and on a change to the pins)
#   - CodeQL, and dependency review (off until the repository's Dependency graph setting is on)
#   - the release hardening dry run ("Release hardening (dry run)"): Grype, Syft and cosign are
#     pinned for linux-amd64 in _release_harden.yml, so it runs on the runner only
# Not exercised before a tag, because they need a secret: signing (the release keystore), the
# Crashlytics symbol upload (google-services.json), the Play, Amazon and GitHub uploads, and the
# baseline profile, which needs the KVM emulator release.yml starts.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

QUICK=0
INSTRUMENTED=0
GMD=0
ARMV7=0
ALLOW_MISSING=0
for arg in "$@"; do
  case "$arg" in
    --quick) QUICK=1 ;;
    --with-instrumented) INSTRUMENTED=1 ;;
    --with-gmd) GMD=1 ;;
    --with-armv7) ARMV7=1 ;;
    --allow-missing) ALLOW_MISSING=1 ;;
    *) echo "unknown flag: $arg" >&2; exit 2 ;;
  esac
done

step() { printf '\n=== %s ===\n' "$1"; }

# need <command> <what runs without it>: fails, or with --allow-missing says what was skipped.
need() {
  command -v "$1" >/dev/null 2>&1 && return 0
  if [ "$ALLOW_MISSING" -eq 1 ]; then
    echo "::notice:: $1 is not installed; skipping $2."
    return 1
  fi
  echo "$1 is not installed; it is needed for $2 (--allow-missing skips it)." >&2
  exit 1
}

if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
  export JAVA_HOME
fi
if [ -z "${JAVA_HOME:-}" ]; then
  echo "JAVA_HOME is not set and no JDK was found. Gradle needs one to start; the build's own JDK comes from gradle/gradle-daemon-jvm.properties, and CI runs Temurin 17." >&2
  exit 1
fi
need python3 "every script gate" || true
# shellcheck source=scripts/tools.sh
. scripts/tools.sh
export GRADLE_FLAGS="--no-daemon --console=plain"
read -r -a GRADLE <<< "./gradlew $GRADLE_FLAGS"

step "clang-format (JNI, check only)"
scripts/check-format.sh

step "Play metadata lint"
python3 scripts/check_play_metadata.py

step "Translations are complete in every locale"
python3 scripts/check-translations.py

step "The CHANGELOG and the Play release notes agree on the version"
code="$(python3 scripts/check_version_consistency.py)"
EXPECTED_VERSION_CODE="$code" python3 scripts/check_play_metadata.py

step "Release script tests"
python3 -m unittest discover -s scripts -p '*_test.py' -t scripts

step "Action pins (40-character SHAs)"
sh scripts/check-action-pins.sh

step "Vulnerability allowlist"
ensure_pyyaml
PATH="$(dirname "$SECURITY_PYTHON"):$PATH" python3 scripts/security-allowlist.py check

step "Dependency locks are current"
scripts/check-dependency-locks.sh

step "ktlint"
"${GRADLE[@]}" ktlintCheck

step "detekt"
"${GRADLE[@]}" detekt

if [ "$QUICK" -eq 1 ]; then
  step "JVM unit tests"
  "${GRADLE[@]}" testGithubDebugUnitTest testPlayDebugUnitTest testAmazonDebugUnitTest
  echo ""; echo "Quick gates passed. Run without --quick before pushing a build input."
  exit 0
fi

step "OSV-Scanner (the committed Gradle lockfiles)"
ensure_osv_scanner
PATH="$(dirname "$SECURITY_PYTHON"):$PATH" OSV_SCANNER="$OSV_SCANNER" bash scripts/osv-scan.sh -- .

step "gitleaks (full history)"
ensure_gitleaks
"$GITLEAKS" git --config=.gitleaks.toml --redact --exit-code=1 .

step "Android lint"
"${GRADLE[@]}" lintGithubDebug lintPlayDebug lintAmazonDebug

step "JVM unit tests"
"${GRADLE[@]}" testGithubDebugUnitTest testPlayDebugUnitTest testAmazonDebugUnitTest

if [ "$(uname -s)" = Linux ]; then
  step "Native C++ tests (:app:nativeTest)"
  "${GRADLE[@]}" :app:nativeTest
  step "Native C++ tests and bench smoke (host, with the lock-statistics bench)"
  scripts/native-tests.sh host
  step "Native C++ tests (32-bit ARM, qemu)"
  if need arm-linux-gnueabihf-g++ "the 32-bit ARM host tests" && need qemu-arm "the 32-bit ARM host tests"; then
    scripts/native-tests.sh armv7
  fi
else
  step "Native C++ tests and bench smoke (ubuntu:24.04 container)"
  if need docker "the C++ host tests, which build on Linux only"; then
    scripts/native-tests-docker.sh host
    if [ "$ARMV7" -eq 1 ]; then
      step "Native C++ tests (32-bit ARM, qemu, ubuntu:24.04 container)"
      scripts/native-tests-docker.sh armv7
    fi
  fi
fi

step "Debug APKs (github, play, amazon): each carries its flavor's ABIs"
scripts/check-builds.sh debug

step "clang-tidy (JNI, against the debug build's compile database)"
scripts/check-tidy.sh

step "Release builds, unsigned: ABIs, and every member native code resolves by name is kept"
scripts/check-builds.sh release

step "libsodium uses 128-bit arithmetic on 64-bit ABIs only"
python3 scripts/check_sodium_ti_mode.py app/.cxx

# The integration package is the gate: the screenshots harness has its own per-locale workflow and
# the ad-hoc launch tests predate the guided-setup redirect.
INTEGRATION_FILTER="-Pandroid.testInstrumentationRunnerArguments.package=com.tinkernorth.dish.integration"

if [ "$GMD" -eq 1 ]; then
  step "Instrumented tests (pixel6Api34 managed device)"
  "${GRADLE[@]}" :app:pixel6Api34GithubDebugAndroidTest "$INTEGRATION_FILTER"
fi

if [ "$INSTRUMENTED" -eq 1 ]; then
  step "Instrumented tests (attached device or emulator)"
  if [ -z "${ANDROID_HOME:-}" ]; then echo "ANDROID_HOME is not set." >&2; exit 1; fi
  if [ "$("$ANDROID_HOME/platform-tools/adb" devices | grep -c -w device || true)" -lt 1 ]; then
    echo "--with-instrumented needs a device or emulator attached (adb devices lists none)." >&2
    exit 1
  fi
  "${GRADLE[@]}" :app:connectedGithubDebugAndroidTest "$INTEGRATION_FILTER"
fi

echo ""; echo "All requested gates passed."
