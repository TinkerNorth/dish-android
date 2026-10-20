#!/usr/bin/env bash
# Proves on the connected emulator that an APK with no x86 library runs its armeabi-v7a one through
# the system image's ARM translation: the device's ABI list and native bridge, the ABI the package
# manager picked for the install, the translator and the ARM guest libraries mapped into the running
# app, and no native-load failure in the app's log. Leaves the APK installed.
#
#   scripts/ci_arm_translation_proof.sh <apk>
set -euo pipefail

APK="$1"
PACKAGE="com.tinkernorth.dish"
LOAD_FAILED="Satellite native library failed to load"

prop() { adb shell getprop "$1" | tr -d '\r'; }

echo "ro.product.cpu.abilist=$(prop ro.product.cpu.abilist)"
echo "ro.dalvik.vm.native.bridge=$(prop ro.dalvik.vm.native.bridge)"

adb install -r "$APK"
abis="$(adb shell dumpsys package "$PACKAGE" | tr -d '\r' | grep -E 'primaryCpuAbi|secondaryCpuAbi' | sort -u)"
echo "$abis"
if ! grep -q 'primaryCpuAbi=armeabi-v7a' <<<"$abis"; then
  echo "::error::the package manager did not install $PACKAGE as armeabi-v7a"
  exit 1
fi

adb logcat -c
adb shell am start -W -n "$PACKAGE/.ui.main.MainActivity"
pid=""
for _ in $(seq 1 30); do
  pid="$(adb shell pidof "$PACKAGE" | tr -d '\r')"
  [ -n "$pid" ] && break
  sleep 1
done
if [ -z "$pid" ]; then
  echo "::error::$PACKAGE is not running"
  exit 1
fi
sleep 5

# Debuggable build: run-as reads the app's own /proc entry under the app's uid.
maps="$(adb shell run-as "$PACKAGE" cat "/proc/$pid/maps" | tr -d '\r')"
translator="$(grep -oE '[^ ]*(ndk_translation|houdini)[^ ]*\.so' <<<"$maps" | sort -u || true)"
guest_libs="$(grep -oE '[^ ]*/lib/arm/[^ ]*\.so' <<<"$maps" | sort -u || true)"
echo "translator mapped into pid $pid: ${translator:-<none>}"
echo "ARM guest libraries mapped: $(grep -c . <<<"$guest_libs" || true)"
grep -E 'libc\.so|libandroid\.so|liblog\.so' <<<"$guest_libs" || true
if [ -z "$translator" ]; then
  echo "::error::no ARM translator is mapped into $PACKAGE"
  exit 1
fi
if adb logcat -d | grep -F "$LOAD_FAILED"; then
  echo "::error::$PACKAGE could not load its native library through the translator"
  exit 1
fi
echo "$PACKAGE loaded its armeabi-v7a libsatellite.so through ARM translation in pid $pid"
adb shell am force-stop "$PACKAGE"
