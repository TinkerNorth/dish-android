# Data Safety form: answers for Google Play

These map to the fields on the Data Safety form in Play Console. Cross-referenced against `PRIVACY.md` and the actual code.

## Top-level summary

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **YES** (crash logs, and microphone audio when the user turns the controller microphone on) |
| Is all of the user data collected by your app encrypted in transit? | **YES**. TLS to Firebase Crashlytics; ChaCha20-Poly1305 over UDP for controller audio, which never leaves the user's LAN |
| Do you provide a way for users to request that their data be deleted? | **YES**. Uninstalling the app removes all stored data; crash reports are auto-deleted by Firebase after 90 days; microphone audio is never stored anywhere, so there is nothing to delete |
| Does your app comply with Google Play Families Policy? | **YES** (general-audience; no data collection from children) |

## Data types collected

### App activity > Crashes

- **Collected?** YES
- **Shared?** NO
- **Purpose**: App functionality (crash diagnostics)
- **Optional?** YES. User can opt out from Settings, under "Share crash reports"
- **Notes**: Sent to Firebase Crashlytics. Includes stack trace, device model and Android version, app version, Firebase Installation ID. Does NOT include controller input, satellite/host names, Wi-Fi SSID, IP, or any personally identifying information.

### Audio > Voice or sound recordings

- **Collected?** YES, but only when the user switches Microphone on for a controller. Play counts transmission off the device as collection, and this audio is transmitted off the device.
- **Shared?** NO. It goes to one destination: the PC on the user's own local network that they paired this phone with by entering a PIN shown on that PC. No third party receives it, and no TinkerNorth-operated server exists to receive it.
- **Processed ephemerally?** YES. Audio is captured in 20 ms windows, encoded, sent, and discarded. Nothing is written to storage, nothing is buffered beyond the window in flight, and there is no recording feature.
- **Purpose**: App functionality. A DualShock 4 v2 or DualSense plugged into a PC presents its own microphone endpoint; when Dish emulates one of those pads, the phone microphone stands in for it so voice chat on the PC hears the user through the pad.
- **Required or optional?** OPTIONAL, and off by default. Every one of these must hold before a single audio packet is sent: the controller is bound and streaming to a Satellite, the per-controller Microphone switch is on, `RECORD_AUDIO` is granted, and the controller is not muted. Muting stops the capture rather than sending silence, so a muted controller transmits nothing at all.
- **Notes**: never sent to TinkerNorth, never uploaded to the internet, never included in crash reports. Bluetooth and Moonlight destinations carry no controller audio, so the switch is not offered for them.

### Everything else

**NOT collected and NOT shared** for all of the following categories:

- Personal info (name, email, address, phone, age, etc.)
- Financial info
- Health and fitness
- Messages
- Photos and videos
- Audio > Music files, Other audio files (only voice/sound is in scope, and only as described above)
- Files and docs
- Calendar
- Contacts
- App activity > Page views, app interactions, searches, installed apps
- Web browsing history
- App info and performance > other diagnostics (RAM/disk telemetry, network usage telemetry, etc.)
- Device or other IDs (advertising ID, Android device ID, customer-set IDs, etc.)

## Notable absences worth flagging in justification text

- **No advertising ID (AD_ID)**: Firebase Analytics is deliberately omitted from the build (see `app/build.gradle.kts`). The `com.google.android.gms.permission.AD_ID` permission is NOT in the manifest.
- **No location**: the app does not request or use location permissions at any API level.
- **No contact / SMS / call log / camera permissions**: not requested.
- **Microphone is the only sensitive permission requested**, it is requested only from the Microphone switch on a controller's binding screen, and the audio is never recorded or uploaded.

## Notes for Play reviewers

The privacy policy hosted at `https://dish.tinkernorth.com/privacy/dish-android/` is the canonical version. The `PRIVACY.md` file at the repo root mirrors it.
