# Dish for Android: Privacy Policy

**Effective date:** 2026-09-06.
**Hosted copy:** [`https://dish.tinkernorth.com/privacy/dish-android/`](https://dish.tinkernorth.com/privacy/dish-android/).
The hosted copy at that URL is the canonical version; this file mirrors it
in-repo so the code and the policy ship together. Google Play points at the
hosted URL.

This document describes what data the Dish Android app collects, why, how
long it is retained, and the choices you have over it. The product as a
whole spans several repositories (`satellite`, `dish-android`,
`dish-windows`, `dish-linux`, `dish-mac`); this policy is specific to the
Android client. The server (`satellite`) runs on your own PC and has its own
policy, as do the desktop clients; do not read one as describing another.

---

## 1. Short version

- Dish is a **LAN-only** wireless gamepad. Controller input goes from your
  phone, over your Wi-Fi (encrypted) or Bluetooth, to your own PC: a
  `satellite` server, a Moonlight host (Sunshine, Apollo, Wolf) or a
  Bluetooth host you paired with. It does not stream to any
  TinkerNorth-operated server.
- We do not sell, share, or rent your data. We do not show ads. We do not
  profile you for marketing.
- We collect **crash reports** via Google Firebase Crashlytics so we can
  diagnose bugs. Crash reports contain stack traces, device model,
  Android version, and an auto-generated install ID. They do not contain
  the names of the satellites you pair with, your IP address, or your
  controller input.
- Dish can act as your controller's **microphone**, so voice chat on your
  PC hears you through the emulated pad. That is off unless you switch it
  on for a controller, it needs the microphone permission, and the audio
  goes only to the PC you paired with, only while that controller is
  streaming. It is never recorded, never stored, and never sent to us. Mute
  stops it at the source: while muted, no audio packet leaves your phone at
  all.
- You can opt out of crash reporting at any time from the gear icon on
  the main screen → *Share crash reports*. The choice persists across
  launches and is honoured before any crash can be uploaded on the next
  start. Reports already collected before you opt out are retained by
  Firebase for 90 days, then deleted.

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
| Remembered Moonlight hosts (name, address, ports, host id, the app last launched there, the controller type you chose) | Same SharedPreferences | Reconnecting to Moonlight hosts you paired with. |
| Moonlight client identity: an RSA key pair with a self-signed certificate, plus a random client id | The private key in the Android Keystore, where it cannot be exported; the certificate and id in app-private storage | Proving to a Moonlight host that this is the phone it paired with. Sent only to Moonlight hosts you pair with, never to us. |
| Last per-slot controller binding (slot → satellite/BT host) | Same SharedPreferences | Restoring your last setup on launch. |
| Per-slot battery readings (transient) | In-memory only | Showing the battery indicator on the controller card. |
| Gamepad input events (button presses, sticks, gyroscope) | In-memory only | Forwarded over encrypted UDP to the satellite you paired with, or over Bluetooth HID. Not logged or stored. |
| Per-slot Microphone / Controller sound switches | App-private SharedPreferences (`user_preferences.xml`) | Remembering which controllers you gave a microphone or a speaker. The microphone switch defaults to off. |
| Microphone audio, while the controller microphone is on and unmuted | In-memory only, one 20 ms window at a time | Encoded and forwarded over the same encrypted UDP session to the satellite you paired with. Never written to storage, never logged, never sent to TinkerNorth. |

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
- **Controller audio.** A satellite can give its emulated pad the audio
  endpoints a real DualShock 4 v2 or DualSense has. If you switch the
  Microphone on for a controller, the app captures the phone microphone in
  20 ms windows, encodes each one with Opus, and sends it over the SAME
  encrypted UDP session as your controller input (`MSG_MIC_AUDIO`, port
  9876) so it arrives on the PC as the pad's own microphone. If you switch
  Controller sound on, the pad's audio comes back the same way
  (`MSG_SPEAKER_AUDIO`). Nothing is buffered to disk and nothing is kept
  after the window is sent. Capture runs only while all of the following
  hold: the controller is bound and streaming to a satellite, the
  Microphone switch is on, the microphone permission is granted, and the
  controller is not muted. Muting is enforced by stopping the capture, not
  by sending silence, so a muted controller sends no audio packets at all.
- **Moonlight hosts.** Dish can also be the controller for a Sunshine,
  Apollo or Wolf host, speaking the Moonlight (GameStream) protocol instead
  of the satellite one. Hosts are found over mDNS (`_nvstream._tcp`) or
  added by address. Pairing follows that protocol: a few HTTP requests to
  the host on port 47989, which the protocol fixes as plain HTTP because no
  shared secret exists yet. The PIN never travels over the wire; both sides
  derive a key from it and prove they hold it. From then on every call is
  mutual TLS on port 47984 with the phone's client certificate, and the
  host's certificate is pinned so a swapped host is refused. To open a
  controller session the app launches or resumes an app on the host, then
  sends your controller input, motion, touchpad and battery over the
  protocol's encrypted (AES-GCM) control channel. The host also streams its
  screen and audio at the lowest settings it allows, because the protocol
  needs a stream to hold the session open; Dish discards those packets
  without decoding them and never stores or shows them. The host learns the
  phone's client id, a device name and the client certificate. All of this
  stays on your local network.
- **Bluetooth HID.** As an alternative to Wi-Fi, the app can present
  itself to a paired host as a Bluetooth HID gamepad. In that mode no
  data crosses Wi-Fi; the host receives a standard HID report. Bluetooth
  and Moonlight hosts carry no controller audio at all: neither protocol
  has a microphone channel, so the switches are not offered for them.

### 2.3 Sent to Google (Crashlytics)

When the app crashes (an unhandled exception in Kotlin, a fatal signal in
the native JNI layer, or an ANR), it uploads a crash report to Firebase
Crashlytics. The report contains:

- Stack trace (Kotlin and/or native)
- Device model, manufacturer, Android version, locale, free RAM, free disk
- App version code and version name
- An auto-generated Firebase Installation ID (a UUID that lets us count
  unique affected installs without identifying you personally)
- The last few log lines we explicitly flagged as relevant (we do not
  upload general `logcat` content)

Crashlytics **does not** receive:

- The names, IPs, or MAC addresses of satellites, Moonlight hosts or
  Bluetooth hosts you pair with.
- Your gamepad input events.
- Any microphone audio, or any audio at all.
- Your Wi-Fi SSID or IP address.
- Any contact information.

Crashlytics retains crash data for 90 days, then deletes it. See Google's
[Firebase Privacy and Security policy](https://firebase.google.com/support/privacy).

---

### 2.4 Sent to Google (tips through Google Play, Play build only)

The Google Play build of Dish lets you tip the developer, once or monthly,
through Google Play's billing system. Google, not Dish, takes the payment:
your payment method, billing address and receipt are handled by Google Play
under [Google's Privacy Policy](https://policies.google.com/privacy) and the
Google Play Terms of Service. Dish never sees them.

What Dish receives from Google Play, on your device only, is the list of
products you own and a purchase token per purchase. Dish uses them to mark
a tip as delivered, so Google Play can close it out, and to show you that a
monthly plan is active. Nothing about a purchase is stored by Dish, sent to
TinkerNorth, or included in crash reports.

The build distributed on GitHub and tinkernorth.com contains none of this.
It links to third-party donation pages instead, and their own privacy
policies apply once you leave the app.

---

## 3. Permissions and why we ask for them

| Permission | Why | When asked |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | Sending the encrypted UDP stream and discovery beacons over your local network. | Install-time (normal permissions). |
| `BLUETOOTH_CONNECT` (API 31+) | Pairing with a Bluetooth host (PC, console, set-top box) and presenting as a HID gamepad, or connecting a Bluetooth game controller. | Runtime, when you add a Bluetooth host or controller. |
| `BLUETOOTH_SCAN` (API 31+) | Discovering nearby Bluetooth hosts and controllers so you can pair them. Declared with `neverForLocation`: we do not derive your location from Bluetooth results and collect no scan data. | Runtime, when you scan for a Bluetooth host or controller. |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (API ≤ 30 only) | Legacy Bluetooth equivalent for Android ≤ 11. | Install-time. |
| `POST_NOTIFICATIONS` (API 33+) | Showing the ongoing-session notification while a controller is streaming, plus actionable error banners. | Runtime, on first launch on Android 13+. Declining still lets the app run; the notification just isn't visible. |
| `RECORD_AUDIO` | Acting as the emulated controller's own microphone, so voice chat on your PC hears you through the pad. | Runtime, only when you switch Microphone on for a controller. Declining leaves everything else working; the row says it needs permission and never pretends to capture. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Keeping the streaming session alive when you background the app. | Install-time. |
| `FOREGROUND_SERVICE_MICROPHONE` | Letting that same session keep the controller microphone working while the app is backgrounded. The microphone service type is claimed only while a controller with the Microphone switch on is streaming, never by default. | Install-time. |
| `WAKE_LOCK` | Keeping the CPU awake while a controller is bound, so input latency stays low. | Install-time. |
| `VIBRATE` | Routing in-game rumble from the satellite to the phone's vibration motor. | Install-time. |

We do **not** request: location, contacts, camera, calendar, SMS, call log,
photos, files, or device admin. The microphone is the one sensitive
permission Dish asks for, it is asked for only when you turn the controller
microphone on, and it is never used for anything but that.

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

- **Crash reporting opt-out:** tap the gear icon on the main screen,
  then flip *Share crash reports* off. The switch is on by default;
  flipping it off calls
  `FirebaseCrashlytics.setCrashlyticsCollectionEnabled(false)` and
  persists the choice (in app-private storage, included in cloud
  backup so it survives device transfers). The next app start applies
  the saved preference before any code path that could produce a crash
  report.
- **Controller microphone opt-out:** it is off until you switch it on, per
  controller, on that controller's binding screen. Switching it off stops
  capture and stops the client advertising a microphone to the host at all.
  Muting (the mute button on the on-screen controller, or the one on a
  connected DualSense) stops capture immediately without changing the
  switch. Revoking the microphone permission in system settings stops it
  too, and the binding screen goes back to saying it needs permission.
- **Forget a satellite or host:** *Connections → Forget* deletes the
  stored shared key (for a Moonlight host, its entry and pinned
  certificate) from `connection_store.xml`. There is no server-side record
  to delete because there is no TinkerNorth server. A Moonlight host keeps
  its own list of paired clients, which you clear in that host's settings.
- **Wipe everything:** Uninstall the app. All app-private storage,
  including paired keys, the Moonlight identity in the Keystore and the
  crash-reporting preference, is removed by Android.

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
