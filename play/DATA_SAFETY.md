# Data Safety form — answers for Google Play

These map to the fields on the Data Safety form in Play Console. Cross-referenced against `PRIVACY.md` and the actual code.

## Top-level summary

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **YES** (Crash logs only) |
| Is all of the user data collected by your app encrypted in transit? | **YES** — TLS to Firebase Crashlytics |
| Do you provide a way for users to request that their data be deleted? | **YES** — uninstalling the app removes all stored data; crash reports are auto-deleted by Firebase after 90 days |
| Does your app comply with Google Play Families Policy? | **YES** (general-audience; no data collection from children) |

## Data types — what is collected

### App activity > Crashes

- **Collected?** YES
- **Shared?** NO
- **Purpose**: App functionality (crash diagnostics)
- **Optional?** YES — user can opt out from Settings → "Share crash reports"
- **Notes**: Sent to Firebase Crashlytics. Includes stack trace, device model and Android version, app version, Firebase Installation ID. Does NOT include controller input, satellite/host names, Wi-Fi SSID, IP, or any personally identifying information.

### Everything else

**NOT collected and NOT shared** — for all of the following categories:

- Personal info (name, email, address, phone, age, etc.)
- Financial info
- Health and fitness
- Messages
- Photos and videos
- Audio
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
- **No contact / SMS / call log / camera / microphone permissions**: not requested.

## Notes for Play reviewers

The privacy policy hosted at `https://dish.tinkernorth.com/privacy/dish-android/` is the canonical version. The `PRIVACY.md` file at the repo root mirrors it.
