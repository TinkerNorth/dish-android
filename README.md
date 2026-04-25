# Dish Android

[![Android CI](https://github.com/TinkerNorth/dish-android/actions/workflows/android-ci.yml/badge.svg)](https://github.com/TinkerNorth/dish-android/actions/workflows/android-ci.yml)

Dish is an Android app that turns your phone into a wireless gamepad. It discovers Satellite servers on the local network, pairs with them, and streams controller input over UDP with minimal latency.

## Features

- **LAN Discovery** — automatically finds Satellite servers via UDP broadcast beacons
- **PIN Pairing** — secure TCP handshake with server-side PIN verification
- **Low-Latency Input** — controller reports sent via native `sendto()` directly from the input thread (no buffering)
- **Native NDK Backend** — C++ JNI layer handles networking and XUSB report packing
- **GameActivity Integration** — uses AndroidX GameActivity for the native loop while overlaying a Kotlin/View-based UI
- **Live Telemetry** — real-time display of event rate, sample rate, send rate, axis values, and button state
- **Gamepad Hot-Plug** — detects controller connect/disconnect events at runtime

## Architecture

```
Kotlin UI (MainActivity)
  ├── View Binding (scanning, server list, pairing, connected screens)
  ├── InputManager listener (gamepad hot-plug)
  └── dispatchKeyEvent / dispatchGenericMotionEvent
        └── SatelliteNative (JNI)
              └── satellite_jni.cpp
                    ├── UDP socket (sendReport → sendto)
                    ├── LAN discovery (discoverServers)
                    └── TCP pairing (pair)
```

## Requirements

- Android Studio Ladybug or newer
- Android SDK 36 (compile SDK)
- Android NDK with CMake 3.22.1+
- Min SDK: 24 (Android 7.0)
- JDK 11+

## Getting Started

1. **Clone the repository**
   ```bash
   git clone https://github.com/TinkerNorth/dish-android.git
   cd dish-android
   ```

2. **Open in Android Studio**
   Open the project root directory in Android Studio. Gradle sync will download dependencies automatically.

3. **Build & Run**
   ```bash
   ./gradlew assembleDebug
   ```
   Or use **Run ▶** in Android Studio to deploy to a connected device or emulator.

4. **Connect a gamepad** to your Android device (Bluetooth or USB) and launch the app. It will scan for Satellite servers on your LAN.

## Project Structure

```
├── app/
│   ├── src/main/
│   │   ├── java/com/tinkernorth/dish/
│   │   │   ├── MainActivity.kt        # UI, state machine, input handling
│   │   │   └── SatelliteNative.kt      # JNI bridge to native layer
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt          # NDK build config
│   │   │   └── satellite_jni.cpp       # UDP streaming, discovery, pairing
│   │   └── res/                        # Layouts, drawables, themes
│   ├── build.gradle.kts
│   └── detekt.yml                      # detekt config (tuned thresholds)
├── gradle/
│   └── libs.versions.toml              # Version catalog
├── .github/
│   ├── workflows/android-ci.yml        # CI pipeline
│   ├── dependabot.yml                  # Automated dependency updates
│   ├── pull_request_template.md
│   └── ISSUE_TEMPLATE/
├── build.gradle.kts                    # Root build file
└── settings.gradle.kts
```

Shared config files live at the **repo root** (`TinkerNorth/`):
- `.clang-format` — C++ formatting rules (used by both Satellite and Dish JNI)
- `.clang-tidy` — C++ static analysis checks
- `.editorconfig` — whitespace/indentation rules for all file types

## Building

| Task | Command |
|------|---------|
| Debug APK | `./gradlew assembleDebug` |
| Release APK | `./gradlew assembleRelease` |
| Run unit tests | `./gradlew testDebugUnitTest` |

## Code Quality

### Kotlin

| Tool | Check | Auto-fix |
|------|-------|----------|
| **ktlint** (formatting + linting) | `./gradlew ktlintCheck` | `./gradlew ktlintFormat` |
| **detekt** (static analysis) | `./gradlew detekt` | — |
| **Android Lint** (Android-specific) | `./gradlew lint` | — |

### C++ / JNI (`app/src/main/cpp/`)

The JNI layer shares `.clang-format` and `.clang-tidy` with the Satellite server. Run from the **repo root**:

```bash
# Format
clang-format -i Dish/app/src/main/cpp/satellite_jni.cpp

# Compiler warnings (applied automatically via CMake)
# CMakeLists.txt uses: -Wall -Wextra -Wpedantic
```

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes (`git commit -m "Add my feature"`)
4. Push to the branch (`git push origin feature/my-feature`)
5. Open a Pull Request

Please use the provided PR and issue templates.

## License

Distributed under the terms of the **GNU Lesser General Public License v3.0
or later**. See [`LICENSE`](LICENSE) (LGPL) and [`COPYING.GPL3`](COPYING.GPL3)
(the GPL v3 the LGPL incorporates by reference).
