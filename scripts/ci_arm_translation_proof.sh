#!/usr/bin/env bash
# Proves on the connected emulator that an APK runs its armeabi-v7a library through the system
# image's ARM translation, and fails unless every one of these holds:
#   - ro.product.cpu.abilist names armeabi-v7a and no 64-bit ABI,
#   - ro.dalvik.vm.native.bridge names a translator (set, and not 0),
#   - the package manager installed the APK with primaryCpuAbi=armeabi-v7a,
#   - the running app maps a translator (ndk_translation or houdini) and the ARM guest
#     /system/lib/arm/liblog.so and /system/lib/arm/libandroid.so that libsatellite.so links,
#   - the app's log carries no native-load failure.
# Leaves the APK installed. Sourcing the file defines the checks without running them.
#
#   scripts/ci_arm_translation_proof.sh <apk>
set -euo pipefail

PACKAGE="com.tinkernorth.dish"
LOAD_FAILED="Satellite native library failed to load"

fail() {
  echo "::error::$1"
  return 1
}

require_translating_abilist() {
  local abilist="$1"
  echo "ro.product.cpu.abilist=$abilist"
  if ! grep -qE '(^|,)armeabi-v7a(,|$)' <<<"$abilist"; then
    fail "the device ABI list has no armeabi-v7a"
    return 1
  fi
  if grep -qE '(^|,)(arm64-v8a|x86_64)(,|$)' <<<"$abilist"; then
    fail "the device ABI list has a 64-bit ABI, which would run the APK's 64-bit library instead"
    return 1
  fi
}

require_native_bridge() {
  local bridge="$1"
  echo "ro.dalvik.vm.native.bridge=$bridge"
  if [ -z "$bridge" ] || [ "$bridge" = "0" ]; then
    fail "the image names no native bridge, so nothing translates ARM code"
    return 1
  fi
}

require_primary_abi() {
  local dumpsys="$1"
  grep -E 'primaryCpuAbi|secondaryCpuAbi' <<<"$dumpsys" | sort -u || true
  if ! grep -q 'primaryCpuAbi=armeabi-v7a' <<<"$dumpsys"; then
    fail "the package manager did not install $PACKAGE as armeabi-v7a"
    return 1
  fi
}

require_translator_mapped() {
  local maps="$1"
  local translator
  translator="$(grep -oE '[^ ]*(ndk_translation|houdini)[^ ]*\.so' <<<"$maps" | sort -u || true)"
  echo "translator mapped into the app: ${translator:-<none>}"
  if [ -z "$translator" ]; then
    fail "no ARM translator is mapped into $PACKAGE"
    return 1
  fi
}

require_arm_guest_libs() {
  local maps="$1"
  local lib
  for lib in /system/lib/arm/liblog.so /system/lib/arm/libandroid.so; do
    if ! grep -qF " $lib" <<<"$maps"; then
      fail "$lib, which libsatellite.so links, is not mapped into $PACKAGE"
      return 1
    fi
    echo "ARM guest library mapped: $lib"
  done
}

require_no_load_failure() {
  local log="$1"
  if grep -F "$LOAD_FAILED" <<<"$log"; then
    fail "$PACKAGE could not load its native library through the translator"
    return 1
  fi
  echo "no native-load failure in the app's log"
}

app_pid() {
  local pid=""
  for _ in $(seq 1 30); do
    pid="$(adb shell pidof "$PACKAGE" | tr -d '\r' || true)"
    [ -n "$pid" ] && break
    sleep 1
  done
  echo "$pid"
}

main() {
  local apk="$1"
  require_translating_abilist "$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
  require_native_bridge "$(adb shell getprop ro.dalvik.vm.native.bridge | tr -d '\r')"

  adb install -r "$apk"
  require_primary_abi "$(adb shell dumpsys package "$PACKAGE" | tr -d '\r')"

  adb logcat -c
  adb shell am start -W -n "$PACKAGE/.ui.main.MainActivity"
  local pid
  pid="$(app_pid)"
  if [ -z "$pid" ]; then
    fail "$PACKAGE is not running"
    return 1
  fi
  sleep 5

  # Debuggable build: run-as reads the app's own /proc entry under the app's uid.
  local maps
  maps="$(adb shell run-as "$PACKAGE" cat "/proc/$pid/maps" | tr -d '\r')"
  require_translator_mapped "$maps"
  require_arm_guest_libs "$maps"
  require_no_load_failure "$(adb logcat -d)"
  echo "$PACKAGE ran its armeabi-v7a libsatellite.so through ARM translation in pid $pid"
  adb shell am force-stop "$PACKAGE"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
