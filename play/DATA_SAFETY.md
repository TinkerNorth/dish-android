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
- **Shared?** NO. It goes to one destination: the PC on the user's own local network that they paired this phone with by entering a PIN shown on that PC. No third party receives it, and no TinkerNorth-operated server receives it.
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
- **Tips through Google Play (Play build only)**: payment details are collected by Google Play's billing system directly from the user and never reach the app, which the Data safety guidance exempts from declaration. The app receives only the owned-product list and purchase tokens, keeps them in memory to acknowledge or consume the purchase, and never stores, uploads, or logs them, so "Financial info" and "Purchase history" stay NOT collected.
- **Controller database check (every build, from 2.7.0)**: the app fetches a manifest, and when it names a newer version the controller list, from `dish.tinkernorth.com`. The request carries no identifier, cookie or query and sends nothing about the user or the device; the host sees the IP address and time as any web server does. It downloads data about controllers, never code, and the user can switch it off in Settings. Nothing is collected or shared by it.
- **Reporting a problem (from 2.7.0)**: user-initiated. The app writes a file and hands it, with the words the user typed, to the user's own mail app; the user sends it, through their own mail provider, so the app transmits nothing itself. The file holds no personal identifiers, addresses or host names; while it is prepared the app asks each connected Satellite, over the paired connection, for its own redacted view of that connection (PRIVACY.md section 2.7).
- **Microphone is the only sensitive permission requested**, it is requested only from the Microphone switch on a controller's binding screen or from the controller inspector's microphone test, and the audio is never recorded or uploaded. The inspector's test opens the microphone for up to eight seconds to draw a level meter; that audio is measured and dropped on the device and never transmitted, so it is not collection in Play's sense and the Audio answers above stand.

## Notes for Play reviewers

The privacy policy hosted at `https://dish.tinkernorth.com/privacy/dish-android/` is the canonical version. The `PRIVACY.md` file at the repo root mirrors it.
