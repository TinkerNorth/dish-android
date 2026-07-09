#!/usr/bin/env bash
# Runs every gate Android CI runs, in the same order, against the local
# tree. Mirrors .github/workflows/android-ci.yml so a green run here means
# a green run there. Instrumented tests need a device: they run when one is
# attached (or a GMD is requested with --gmd), and are skipped with a notice
# otherwise so the fast gates stay usable offline.
#
#   scripts/ci_local.sh              fast gates + instrumented tests if a device is attached
#   scripts/ci_local.sh --gmd        also boot the pixel6Api34 managed device for instrumented tests
#   scripts/ci_local.sh --no-instrumented   fast gates only
set -euo pipefail
cd "$(dirname "$0")/.."

GMD=0
INSTRUMENTED=1
for arg in "$@"; do
  case "$arg" in
    --gmd) GMD=1 ;;
    --no-instrumented) INSTRUMENTED=0 ;;
    *) echo "unknown flag: $arg" >&2; exit 2 ;;
  esac
done

if [ -z "${JAVA_HOME:-}" ]; then
  echo "JAVA_HOME is not set." >&2
  echo "On this machine the Android Studio JBR works, e.g.:" >&2
  echo "  export JAVA_HOME=\"C:/Program Files/Android/Android Studio/jbr\"" >&2
  exit 1
fi

# The POSIX wrapper runs under Git Bash on Windows too, so one path covers
# every OS. Local dependency-verification metadata is gitignored and drifts
# from committed dep bumps; CI regenerates it, so disable it locally to match.
GRADLE="./gradlew"
GRADLE_ARGS="--dependency-verification off --console=plain"
# The instrumented gate is the integration package: the screenshots harness
# has its own per-locale workflow and the ad-hoc launch tests predate the
# guided-setup redirect, so neither belongs in the gate.
INTEGRATION_FILTER="-Pandroid.testInstrumentationRunnerArguments.package=com.tinkernorth.dish.integration"

step() { echo ""; echo "=== $1 ==="; }

step "clang-format (JNI, check only)"
if command -v clang-format >/dev/null 2>&1; then
  FILES=$(find app/src/main/cpp -type f \( -name '*.cpp' -o -name '*.h' -o -name '*.c' \))
  [ -z "$FILES" ] || echo "$FILES" | xargs clang-format --dry-run --Werror
else
  echo "::notice:: clang-format not installed; CI pins 22.1.4. Skipping locally."
fi

step "Play metadata lint"
python scripts/check_play_metadata.py

step "ktlint + detekt (all source sets incl. test + androidTest)"
$GRADLE :app:ktlintCheck :app:detekt $GRADLE_ARGS

step "Android lint"
$GRADLE :app:lint $GRADLE_ARGS

step "JVM unit tests"
$GRADLE :app:testDebugUnitTest $GRADLE_ARGS

step "Native C++ tests"
$GRADLE :app:nativeTest $GRADLE_ARGS

step "Assemble debug APK"
$GRADLE :app:assembleDebug $GRADLE_ARGS

if [ "$INSTRUMENTED" -eq 0 ]; then
  echo ""; echo "=== instrumented tests skipped (--no-instrumented) ==="
  echo "All non-instrumented gates passed."
  exit 0
fi

if [ "$GMD" -eq 1 ]; then
  step "Instrumented tests (pixel6Api34 managed device)"
  $GRADLE :app:pixel6Api34DebugAndroidTest $GRADLE_ARGS $INTEGRATION_FILTER
else
  ADB="adb"
  command -v adb >/dev/null 2>&1 || {
    for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${LOCALAPPDATA:-}/Android/Sdk"; do
      [ -n "$root" ] && [ -x "$root/platform-tools/adb" ] && ADB="$root/platform-tools/adb" && break
      [ -n "$root" ] && [ -x "$root/platform-tools/adb.exe" ] && ADB="$root/platform-tools/adb.exe" && break
    done
  }
  DEVICES=$("$ADB" devices 2>/dev/null | grep -c -w device || true)
  if [ "${DEVICES:-0}" -ge 1 ]; then
    step "Instrumented tests (attached device)"
    $GRADLE :app:connectedDebugAndroidTest $GRADLE_ARGS $INTEGRATION_FILTER
  else
    echo ""; echo "=== instrumented tests skipped (no device) ==="
    echo "Attach a device/emulator, or re-run with --gmd, to run integration tests."
  fi
fi

echo ""; echo "All requested gates passed."
