#!/usr/bin/env bash
# Drives the DishScreenshots harness on a booted emulator: forces the
# form factor via wm overrides, loops the requested locales, and pulls
# each capture set into captures/. Invoked as a single command by
# store-screenshots.yml because android-emulator-runner executes every
# script line as its own shell.
# Env: CAPTURE_SIZE (WxH), CAPTURE_DENSITY, CAPTURE_LOCALES (space-separated).
set -euo pipefail

adb shell wm size "${CAPTURE_SIZE}"
adb shell wm density "${CAPTURE_DENSITY}"
adb shell cmd uimode night yes
adb shell settings put secure immersive_mode_confirmations confirmed
# A CI emulator's launcher can ANR while the harness runs, and Android draws
# its "isn't responding" dialog over whatever is in front, which on the 2.2.0
# release was four whole legs of captures. Hide ANR and crash dialogs
# system-wide; the crash-log check below still fails the leg on any crash in
# the app's own processes, so nothing is papered over.
adb shell settings put global hide_error_dialogs 1

# The window titles Android gives its system error dialogs. A launcher that
# stops responding during boot has its dialog up before the setting above can
# take effect (the 2.2.0 re-run still lost a leg to it), so anything already
# showing is dismissed by stopping its process (the home app restarts on its
# own), and a dialog still up after a locale's capture fails the leg: the
# crash check below only knows the app's own processes.
error_dialogs() {
  adb shell dumpsys window windows | grep -oE "Application (Not Responding|Error): [A-Za-z0-9_.]+" | sort -u || true
}
dismiss_error_dialogs() {
  local found line pkg
  found="$(error_dialogs)"
  [ -n "$found" ] || return 0
  while IFS= read -r line; do
    pkg="${line##*: }"
    echo "dismissing '${line}' by stopping ${pkg}"
    adb shell am force-stop "$pkg"
  done <<< "$found"
  sleep 5
}
dismiss_error_dialogs

adb install -r -g app/build/outputs/apk/play/debug/app-play-debug.apk
adb install -r app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk
mkdir -p captures

for LOC in ${CAPTURE_LOCALES}; do
  echo "=== capturing ${LOC} ==="
  dismiss_error_dialogs
  adb shell cmd locale set-app-locales com.tinkernorth.dish --locales "$LOC"
  adb shell am force-stop com.tinkernorth.dish
  adb shell run-as com.tinkernorth.dish rm -rf files/screengrab || true
  # The crash log buffer is separate from main and small, so it cannot wrap
  # over a long locale; cleared here so only this locale's crashes count.
  adb logcat -c -b crash
  adb shell am instrument -w -e testLocale "$LOC" \
    -e class com.tinkernorth.dish.screenshots.DishScreenshots \
    com.tinkernorth.dish.test/androidx.test.runner.AndroidJUnitRunner | tee /tmp/instrument.log
  # am instrument exits 0 even on test failures; gate on the runner summary.
  grep -q "OK (" /tmp/instrument.log
  # A crash in ANY process of the app or the test package puts Android's
  # "keeps stopping" dialog over the next capture, while the runner still
  # reports OK because the instrumented process itself survived. Those
  # screenshots must never leave this job.
  if adb logcat -d -b crash | grep -qE "AndroidRuntime: Process: com\.tinkernorth\.dish(\.test)?, PID:"; then
    adb logcat -d -b crash | head -60
    echo "::error::A process crashed during the ${LOC} capture; its dialog would be in the screenshots. See the crash log above."
    exit 1
  fi
  if found="$(error_dialogs)" && [ -n "$found" ]; then
    echo "::error::A system error dialog was up during the ${LOC} capture (${found}); it would be in the screenshots."
    exit 1
  fi
  adb exec-out run-as com.tinkernorth.dish tar cf - -C files screengrab > captures/grab.tar
  tar xf captures/grab.tar -C captures
  rm captures/grab.tar
done

find captures -name '*.png' | sort
