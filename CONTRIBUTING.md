# Contributing to Dish Android

Thanks for your interest in improving the Android client! This document
captures the conventions that aren't obvious from skimming the code.

## Getting set up

```bash
# 1) Install Android Studio Ladybug+, NDK, CMake 3.22.1+, JDK 17+
# 2) Open the project in Android Studio (Gradle sync downloads deps)
# 3) Point git at the in-tree pre-commit hook
scripts/setup-hooks.sh
```

The pre-commit hook runs `clang-format -i` (autofix, re-stages) on staged
JNI C/C++ files and skips Kotlin to keep itself fast. CI runs `clang-format
--dry-run --Werror` on the JNI plus `ktlintCheck`, `detekt`, and `lint` on
the Kotlin tree, so anything that slips locally fails the PR.

## License headers

Every source file (`*.kt`, `*.cpp`, `*.h`) starts with:

```
// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.
```

New files must include both lines. Don't introduce code under a different
license — the project is LGPL-3.0-or-later end-to-end (`LICENSE`,
`COPYING.GPL3`, source headers).

## Style

### Kotlin

- 4-space indent, ~120-column soft limit. `ktlint` and `detekt` are
  authoritative — run `./gradlew ktlintFormat` to autofix and
  `./gradlew detekt` to lint.
- `MainViewModel` exposes a single immutable `MainUiState` via a
  `StateFlow`. Don't introduce competing sources of truth — every UI-bound
  field belongs in `MainUiState`.
- Coroutines for async, `kotlinx.serialization` for JSON, Hilt for DI.

### JNI / C++

- C++17, four-space indent, 100-column soft limit. The same `.clang-format`
  ships with `satellite`, `dish-linux`, and this repo — run
  `clang-format -i app/src/main/cpp/*.{cpp,h}` if you're unsure.
- The JNI is the **hot path**. No allocations per packet, no JNI calls
  from the input thread other than `sendReport`, no logging on the
  per-event path.

## Branching & PRs

- All changes land on `main` via pull request — no direct pushes.
- Use the PR template (`.github/pull_request_template.md`) to describe
  the change, the manual test matrix you ran (real device + emulator),
  and call out anything that touches the wire protocol.
- Keep commits focused; squash noisy fixup commits before review.

## What CI runs

`.github/workflows/android-ci.yml` runs on every PR:

1. `clang-format --dry-run --Werror` over `app/src/main/cpp/`.
2. `./gradlew ktlintCheck`.
3. `./gradlew detekt`.
4. `./gradlew lint` (Android Lint).
5. `./gradlew testDebugUnitTest` (JUnit + MockK + Turbine).
6. `./gradlew assembleDebug`, uploads the APK as a CI artifact.

If any step fails, the PR is blocked.

## Touching the hot path

The Kotlin → JNI → `sendto()` chain runs at gamepad polling rate and
must never block. If you're modifying `MainActivity.dispatchGenericMotionEvent`,
`GamepadInputProcessor`, `SatelliteNative`, or `satellite_jni.cpp::sendReport`:

- No `withContext`, no `runBlocking`, no `Dispatchers.IO` on the send path.
- No allocations per event — use the preallocated `XUSB_REPORT`.
- The session map's lookup is the only lock allowed; hold it briefly.
- Preserve `IP_TOS = 0xB8` (DSCP EF) and `MSG_NOSIGNAL` on every send.

## Touching the wire protocol

The Android, macOS, and Linux clients all talk to the same `satellite`
server and must produce byte-identical traffic:

- AEAD: ChaCha20-Poly1305 IETF, 12-byte big-endian nonce derived from a
  monotonic counter.
- Packet layout: `token(4) | counter(4) | ciphertext+tag`, with the
  4-byte token as AAD.
- XUSB report: 12 bytes, little-endian.
- Ports: discovery UDP 9879, pairing TCP 9878, HTTP TCP 9877,
  streaming UDP 9876.

Any change here must be coordinated with `dish-linux`, `dish-mac`, and
`satellite` in the same PR / release cycle.

## Reporting bugs

Use the issue templates under `.github/ISSUE_TEMPLATE/`. Include the
device model, Android version, and `adb logcat -s SatelliteJNI:*` output
covering the misbehavior.
