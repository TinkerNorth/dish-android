# Dish for Android — Privacy Policy

**Effective date:** 2026-05-21.
**Hosted copy:** [`https://dish.tinkernorth.com/privacy/dish-android/`](https://dish.tinkernorth.com/privacy/dish-android/).
The hosted copy at that URL is the canonical version; this file mirrors it
in-repo so the code and the policy ship together. Google Play points at the
hosted URL.

This document describes what data the Dish Android app collects, why, how
long it is retained, and the choices you have over it. The product as a
whole spans four repositories (`satellite`, `dish-android`, `dish-linux`,
`dish-mac`); this policy is specific to the Android client. The server
(`satellite`) runs on your own PC and does not transmit data off your
local network.

---

## 1. Short version

- Dish is a **LAN-only** wireless gamepad. Controller input goes from your
  phone, over your Wi-Fi (encrypted) or Bluetooth, to your own PC. It does
  not stream to any TinkerNorth-operated server.
- We do not sell, share, or rent your data. We do not show ads. We do not
  profile you for marketing.
- We collect **crash reports** via Google Firebase Crashlytics so we can
  diagnose bugs. Crash reports contain stack traces, device model,
  Android version, and an auto-generated install ID — they do not contain
  the names of the satellites you pair with, your IP address, or your
  controller input.
- You can opt out of crash reporting at any time from the in-app settings
  (TODO: implement the toggle; until then, uninstalling the app is the
  only way to stop collection).

---

## 2. What data is processed

### 2.1 Stays on your device

The following data never leaves your phone except to your own
`satellite` server on your LAN:

| Data | Stored where | Used for |
|---|---|---|
| Remembered satellite servers (name, IP, port) | App-private SharedPreferences (`connection_store.xml`) | Reconnecting to known hosts. Excluded from cloud backup and device transfer. |
| Libsodium-derived shared keys (one per paired satellite) | Same SharedPreferences | Encrypting the gamepad wire protocol (ChaCha20-Poly1305). Excluded from cloud backup and device transfer. |
| Remembered Bluetooth HID host MACs and labels | Same SharedPreferences | Reconnecting to known BT hosts. |
| Last per-slot controller binding (slot → satellite/BT host) | Same SharedPreferences | Restoring your last setup on launch. |
| Per-slot battery readings (transient) | In-memory only | Showing the battery indicator on the controller card. |
| Gamepad input events (button presses, sticks, gyroscope) | In-memory only | Forwarded over encrypted UDP to the satellite you paired with, or over Bluetooth HID. Not logged or stored. |

### 2.2 Sent to your own LAN (not to TinkerNorth)

- **Discovery.** When you tap *Scan*, the app browses for the
  `_satellite._udp` mDNS / Bonjour service on the multicast address
  `224.0.0.251:5353` and also listens for legacy UDP broadcast beacons
  on port 9879. All of this stays on your local network.
- **Pairing handshake.** When you enter the 4-digit PIN displayed on
  your satellite, the app makes one HTTPS POST to the satellite on
  port 9443. The request body contains a 32-byte X25519 public key,
  the PIN, the device identifier, and the device label. The satellite
  verifies the PIN, computes the shared secret with `crypto_scalarmult`,
  and both ends derive a 256-bit ChaCha20-Poly1305 session key without
  ever sending the key on the wire. The PIN is consumed once and
  destroyed.
- **Gamepad stream.** Once paired, the app sends ChaCha20-Poly1305-
  authenticated UDP packets to the satellite on port 9876. The payload
  is your controller state (12-byte XUSB report, plus optional
  encrypted MSG_MOTION, MSG_BATTERY, and MSG_TOUCHPAD frames). The app
  also listens on the same socket for MSG_RUMBLE and MSG_LIGHTBAR
  return-path packets from the satellite.
- **Bluetooth HID.** As an alternative to Wi-Fi, the app can present
  itself to a paired host as a Bluetooth HID gamepad. In that mode no
  data crosses Wi-Fi; the host receives a standard HID report.

### 2.3 Sent to Google (Crashlytics)

When the app crashes (an unhandled exception in Kotlin, a fatal signal in
the native JNI layer, or an ANR), it uploads a crash report to Firebase
Crashlytics. The report contains:

- Stack trace (Kotlin and/or native)
- Device model, manufacturer, Android version, locale, free RAM, free disk
- App version code and version name
- An auto-generated Firebase Installation ID (a UUID that lets us count
  unique affected installs without identifying you personally)
- Custom debug keys we set (e.g. "did the native library load?", "is
  Bluetooth permission granted?")
- The last few log lines we explicitly flagged as relevant (we do not
  upload general `logcat` content)

Crashlytics **does not** receive:

- The names, IPs, or MAC addresses of satellites or Bluetooth hosts you
  pair with.
- Your gamepad input events.
- Your Wi-Fi SSID or IP address.
- Any contact information.

Crashlytics retains crash data for 90 days, then deletes it. See Google's
[Firebase Privacy and Security policy](https://firebase.google.com/support/privacy).

---

## 3. Permissions and why we ask for them

| Permission | Why | When asked |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | Sending the encrypted UDP stream and discovery beacons over your local network. | Install-time (normal permissions). |
| `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` (API 31+) | Pairing with a Bluetooth host PC and presenting as a HID gamepad. | Runtime, when you tap *Add Bluetooth host*. |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (API ≤ 30 only) | Legacy Bluetooth equivalent for Android ≤ 11. | Install-time. |
| `POST_NOTIFICATIONS` (API 33+) | Showing the ongoing-session notification while a controller is streaming, plus actionable error banners. | Runtime, on first launch on Android 13+. Declining still lets the app run; the notification just isn't visible. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Keeping the streaming session alive when you background the app. | Install-time. |
| `WAKE_LOCK` | Keeping the CPU awake while a controller is bound, so input latency stays low. | Install-time. |
| `VIBRATE` | Routing in-game rumble from the satellite to the phone's vibration motor. | Install-time. |

We do **not** request: location, contacts, microphone, camera, calendar,
SMS, call log, photos, files, or device admin.

---

## 4. Data sharing and processors

- **Google LLC**, via Firebase Crashlytics, processes crash data on our
  behalf in the US/EU. We are the data controller; Google is the
  processor. See Google's [Data Processing and Security Terms](https://firebase.google.com/terms/data-processing-terms).
- We do **not** share data with advertising networks, analytics vendors
  beyond Crashlytics (we have not enabled Firebase Analytics), or any
  TinkerNorth-operated server. Dish has no TinkerNorth-operated server.

We do not sell personal information as defined under California's CCPA
or comparable laws in other jurisdictions.

---

## 5. Your choices

- **Crash reporting opt-out:** TODO — add a settings toggle that calls
  `FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(false)`.
- **Forget a satellite or host:** *Connections → Forget* deletes the
  stored shared key and the entry from `connection_store.xml`. There is
  no server-side record to delete because there is no TinkerNorth server.
- **Wipe everything:** Uninstall the app. All app-private storage,
  including paired keys, is removed by Android.

---

## 6. Children's privacy

Dish is rated for general audiences. We do not knowingly collect any
data from children under 13 (or the equivalent minimum age in your
jurisdiction). If you believe we have collected such data, please
contact `privacy@tinkernorth.com` and we will delete it.

---

## 7. International transfers

Crash data may be processed in Google data centres outside your country
of residence. Google publishes the relevant transfer-mechanism
documentation (Standard Contractual Clauses, etc.) on its terms page
linked above.

---

## 8. Changes to this policy

We will update the *Effective date* at the top whenever this policy
changes. Material changes (new data collection, new processors) will
also be flagged in the app's next release notes. The previous version
of this policy will remain accessible in the git history of this file.

---

## 9. Contact

- Privacy questions: `privacy@tinkernorth.com`
- Security disclosures: see [`SECURITY.md`](SECURITY.md)
- General contact / bug reports: open an issue in this repository

---

## Open items (post-publication)

These are the only items in this policy that point at unfinished work
in the app. They're tracked in `HANDOFF.md` and will move to "closed"
here once the corresponding code lands.

- [ ] In-app Crashlytics opt-out toggle (`HANDOFF.md` item 2). Until
      shipped, declining crash reporting requires uninstalling the
      app — that caveat lives in §5.
- [ ] If Firebase Analytics is ever added (it isn't today), this
      policy and the Google Play Data Safety form both need a new
      entry covering it.
- [ ] Re-run the [Google Play Data Safety](https://play.google.com/console/about/data-safety/)
      wizard at first Play Console submission and confirm every "Data
      type" declared in the form is reflected here.
