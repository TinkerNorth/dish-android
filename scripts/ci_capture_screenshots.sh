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
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p captures

for LOC in ${CAPTURE_LOCALES}; do
  echo "=== capturing ${LOC} ==="
  adb shell cmd locale set-app-locales com.tinkernorth.dish --locales "$LOC"
  adb shell am force-stop com.tinkernorth.dish
  adb shell run-as com.tinkernorth.dish rm -rf files/screengrab || true
  adb shell am instrument -w -e testLocale "$LOC" \
    -e class com.tinkernorth.dish.screenshots.DishScreenshots \
    com.tinkernorth.dish.test/androidx.test.runner.AndroidJUnitRunner | tee /tmp/instrument.log
  # am instrument exits 0 even on test failures; gate on the runner summary.
  grep -q "OK (" /tmp/instrument.log
  adb exec-out run-as com.tinkernorth.dish tar cf - -C files screengrab > captures/grab.tar
  tar xf captures/grab.tar -C captures
  rm captures/grab.tar
done

find captures -name '*.png' | sort
