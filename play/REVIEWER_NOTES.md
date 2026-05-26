# Reviewer notes for Dish (Play Store review)

## What Dish does

Dish turns an Android phone into a wireless gamepad. It has two operating modes:

1. **Wi-Fi mode (recommended)**: pairs over the LAN with a free helper called Satellite running on a Windows/macOS/Linux PC, then streams encrypted controller input via UDP.
2. **Bluetooth HID mode**: pairs to any device that accepts a Bluetooth gamepad (PC, console, set-top box).

## Fastest way to test (no extra software)

**Bluetooth HID mode** needs nothing external. Pair to any nearby Bluetooth-capable device:

1. Launch the app, then complete the welcome flow or tap Skip.
2. From the dashboard, tap "Manage" under Connections.
3. Open "Bluetooth Hosts" → "Add" → pair to any Bluetooth-capable device (for example, another phone, a Windows laptop, a console).
4. From the dashboard, tap any controller slot → open the on-screen gamepad.
5. Press buttons / sticks. The paired host will see them as a standard Xbox-compatible HID gamepad.

## Testing Wi-Fi mode

A demo Satellite is available from https://github.com/TinkerNorth/satellite/releases (installers for Windows, macOS, and Linux). The 4-digit pairing PIN is shown by Satellite when it starts.

## Foreground service rationale

The streaming session runs as a foreground service of type `connectedDevice` so a backgrounded session keeps inputs flowing to the host without latency spikes. This matches Play's guidance for user-initiated controller-pairing apps: a persistent connection to a user-attached or paired physical device is the exact use case `FOREGROUND_SERVICE_CONNECTED_DEVICE` exists for.

## No login required

The app has no user accounts. Pairing uses local-network PINs only. No reviewer credentials needed.

## Privacy / data collection summary

Detailed in `PRIVACY.md` (https://github.com/TinkerNorth/dish-android/blob/main/PRIVACY.md). Short version:

- No advertising.
- No analytics. Firebase Analytics is deliberately omitted from the build to avoid AD_ID auto-injection.
- The only data leaving the device is optional Firebase Crashlytics crash reports, with opt-out from Settings.
- The hosted privacy URL is https://dish.tinkernorth.com/privacy/dish-android/.

## Recommended test devices

- **Phone**: any Android 7+ device (min SDK 24).
- **Tablet (sw600dp)**: confirms adaptive layout.
- **Foldable**: confirms WindowInfoTracker-driven fold-aware behavior.
- **For Bluetooth mode testing**: any second Bluetooth-capable device.
