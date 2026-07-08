# Dish Android

[![Android CI](https://github.com/TinkerNorth/dish-android/actions/workflows/android-ci.yml/badge.svg)](https://github.com/TinkerNorth/dish-android/actions/workflows/android-ci.yml)

Turns an Android phone into a wireless gamepad for a PC running the
[Satellite](https://github.com/TinkerNorth/satellite) server. The
phone discovers servers on the local network, pairs over HTTPS, and
streams encrypted controller input over UDP. A virtual on-screen
gamepad and touchpad are available when no physical controller is
attached.

## Features

- LAN discovery: mDNS plus a UDP broadcast fallback
- HTTPS pairing with a four-digit PIN displayed by the satellite
- ChaCha20-Poly1305 over UDP, sent from the input thread via native
  `sendto()` with no buffering
- Bluetooth HID alternative: present as an Xbox-compatible gamepad
  to a paired host
- USB Direct mode: claim a wired controller and decode it natively
  for the lowest-latency path, falling back to Standard routing when a
  device cannot be claimed
- Wide wired-controller support: Xbox, DualShock and DualSense, and
  Switch Pro pads decoded natively from a curated subset of SDL's
  controller database
- Motion (gyroscope + accelerometer), battery, and touchpad return
  paths
- In-hand rumble driven by the host (`MSG_RUMBLE` → phone vibrator)
- Live input-rate readout on the overlay while streaming
- Guided setup that walks you through picking an input source,
  finding a server, and configuring a controller
- Per-slot binding so multiple satellites can run side-by-side

## Architecture

```
Kotlin UI
  ├── SatelliteHttpClient    HTTPS pairing + REST (port 9443)
  ├── InputManager listener  gamepad hot-plug
  └── dispatchKeyEvent / dispatchGenericMotionEvent
        └── SatelliteNative (JNI)
              └── satellite_jni.cpp
                    ├── UDP discovery       (port 9879 broadcast + mDNS)
                    ├── UDP gamepad stream  (port 9876, encrypted)
                    └── receiveAck → RumbleBridge / motion / battery
```

The protocol contract (REST surface, UDP streams, crypto, liveness,
identity) lives in `satellite/docs/contract.md`; the Android-side mapping
is [`docs/contract.md`](docs/contract.md). Internal patterns and
the hot-path rules are in [`docs/architecture.md`](docs/architecture.md).

## Requirements

- Android Studio that supports AGP 9.2 (Otter 2025.2 or newer)
- Android SDK 37, NDK with CMake 3.22.1
- JDK 17+
- Min SDK 24 (Android 7.0)

## Build and run

```bash
git clone https://github.com/TinkerNorth/dish-android.git
cd dish-android
./gradlew assembleDebug
```

Open the project in Android Studio and use the Run action to deploy to a
device or emulator. Connect a gamepad over Bluetooth or USB before
launching, or use the on-screen virtual pad.

| Task | Command |
|------|---------|
| Debug APK | `./gradlew assembleDebug` |
| Release AAB | `./gradlew bundleRelease` |
| Unit tests | `./gradlew testDebugUnitTest` |
| Kotlin lint | `./gradlew ktlintCheck detekt lint` |
| C++ format | `clang-format -i app/src/main/cpp/*.{cpp,h}` |

Shared style configs (`.clang-format`, `.clang-tidy`, `.editorconfig`)
live at the TinkerNorth repo root, one level up.

## Project layout

```
app/
  src/main/
    java/com/tinkernorth/dish/   Kotlin (UI, sources, composers, JNI bridge)
    cpp/                         JNI (UDP send + discovery, encoders)
    res/                         Layouts, drawables, themes
  src/test/                      JVM unit tests (incl. host-build C++ tests)
  src/androidTest/               Instrumented smoke tests
docs/
  architecture.md                Module map, hot-path rules, patterns
  contract.md                    Protocol-contract pointer + Android mapping
  design-system.md               Tokens, styles, layout composites
gradle/libs.versions.toml        Version catalog
.github/workflows/               CI, security, release pipelines
```

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md) and the
[`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md). CI runs build + style
(`android-ci.yml`) and security gates (`security.yml`, `codeql.yml`)
on every PR: OWASP Dependency-Check (fails on CVSS >= 7.0),
OSV-Scanner, gitleaks, action-pin lint, and CodeQL for `java-kotlin`
and `cpp`.

> Branch protection is unavailable on this repo's current org plan,
> so direct pushes to `main` are blocked by convention only. The CI
> workflows are the de-facto gate.

## Privacy and security

LAN-only. No TinkerNorth-operated server. Firebase Crashlytics is the
only telemetry, opt-out from the Settings screen. Full policy:
[`PRIVACY.md`](PRIVACY.md).

Vulnerability disclosure: [`SECURITY.md`](SECURITY.md). Every release
is cosign-signed with SHA256SUMS, SBOMs (SPDX + CycloneDX), and SLSA
L3 provenance; the verification recipe is in `SECURITY.md`.

## Releases

Notable changes in [`CHANGELOG.md`](CHANGELOG.md). Releases are
tagged in lockstep across `satellite`, `dish-android`, `dish-linux`,
and `dish-mac` when the wire protocol changes.

## License

LGPL-3.0-or-later. See [`LICENSE`](LICENSE) and
[`COPYING.GPL3`](COPYING.GPL3).
