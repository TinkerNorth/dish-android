# Reviewer notes for Dish (Play Store review)

## What Dish does

Dish turns an Android phone into a wireless gamepad. It has two operating modes:

1. **Wi-Fi mode (recommended)**: pairs over the LAN with a free helper called Satellite running on a Windows/macOS/Linux PC, then streams encrypted controller input via UDP.
2. **Bluetooth HID mode**: pairs to any device that accepts a Bluetooth gamepad (PC, console, set-top box).

## Fastest way to test (no extra software)

**Bluetooth HID mode** needs nothing external. Pair to any nearby Bluetooth-capable device:

1. Launch the app. On first run the guided "Set up Dish" flow opens; either complete it or tap Skip.
2. On the dashboard, tap Manage on the connections card to open the Connections screen.
3. Open the Bluetooth Hosts section, tap Add, then pair to any Bluetooth-capable device (for example, another phone, a Windows laptop, a console).
4. On the dashboard, open a controller card and tap Open Gamepad.
5. Press buttons and sticks. The paired host will see them as a standard Xbox-compatible HID gamepad.

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
- Two things leave the device on their own. Optional Firebase Crashlytics crash reports, with opt-out from Settings (*Share crash reports*). And a check for newer controller definitions: about ten seconds after launch and every six hours, the app fetches a small manifest from `https://dish.tinkernorth.com/controllers/v1/`, and the controller list itself only when the manifest names a newer one. It is data, never code, it carries no identifier, cookie or query, and *Check for controller updates automatically* in Settings turns it off.
- Controller input and optional controller audio go only to the user's own paired PC on their own network, over the encrypted UDP session.
- The Play build offers optional tips through Google Play Billing, one-time or monthly (the support card in Settings and the donate bar; product ids and testing in `play/BILLING.md`). A tip unlocks nothing.
- A support report is built only when the user taps *Report a problem* and then *Continue in mail app*; the app hands the file to the user's own mail app and sends nothing itself.
- The hosted privacy URL is https://dish.tinkernorth.com/privacy/dish-android/.

## Recommended test devices

- **Phone**: any Android 7+ device (min SDK 24).
- **Tablet (sw600dp)**: confirms adaptive layout.
- **Foldable**: confirms WindowInfoTracker-driven fold-aware behavior.
- **For Bluetooth mode testing**: any second Bluetooth-capable device.
