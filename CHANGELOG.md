# Changelog

All notable changes to the Dish Android client are documented in this
file. The format is loosely based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
once it reaches `1.0.0`.

Cross-repo coordination: changes to the wire protocol or pairing flow
that require matching updates in `satellite`, `dish-linux`, or `dish-mac`
are marked `[wire-coordinated]`. Releases tagged in lockstep across the
four repos share a version number.

---

## [Unreleased]

### Added

- Guided Dish Setup flow that takes a first-run user from nothing to a
  bound controller: pick an input (USB, a Bluetooth controller, or the
  on-screen pad), set it up, choose a destination (a satellite over Wi-Fi
  or a Bluetooth host), then configure the controller type and bind. This
  replaces the old welcome carousel and setup wizard.
- Bluetooth host pairing: the phone can present itself as a Bluetooth HID
  gamepad to a paired PC, console, or set-top box. The add flow covers the
  controller-type pick, the discoverability prompt, and a live
  wait-for-host countdown.
- Wider wired-controller support: a curated SDL controller-ID import plus
  native USB and HID decoding, including a generic HID descriptor parser,
  DualShock 4 and DualSense motion, Switch Pro motion, and Xbox 360
  wireless rumble. Imported models are recognized but flagged unverified.
- Tri-zone controller cards: a read-only report card (connection,
  destination, emulated type, functions) plus a dedicated Configure
  bindings screen. Binding changes are staged and applied explicitly
  instead of committing live.
- Diagnostics screen, reachable from Settings: live telemetry for every
  connected controller and host, an input inspector (sticks, buttons,
  motion, touch) with stick drift/range/circularity health and a rumble
  tester, a wire-truth panel showing what is actually sent on the wire,
  network stats with a one-way latency sparkline, a flight recorder,
  and an opt-in hot-path latency benchmark.
- Physical trackpads stream natively in USB Direct mode: a DualShock 4
  or DualSense trackpad drives the satellite touchpad directly, and the
  phone touchpad overlay is offered only for inputs without a trackpad.

### Changed (user-facing)

- The Connections screen scans for satellites automatically when it opens,
  and re-homes a remembered satellite whose box moved to a new IP or port,
  so it reconnects without a manual rescan.

### Fixed

- Phantom held inputs (a button, the d-pad, or an off-center stick or
  trigger) no longer linger on the virtual pad after binding a controller,
  before the first real movement.

### Changed: control-plane rewrite (protocol 1) `[wire-coordinated]`

Clean-break rewrite of the satellite control plane against
`satellite/docs/contract.md` (Android mapping: `docs/contract.md`,
replacing the former `docs/wire-format.md`).

- Connecting is ONE declarative call: `PUT /api/connections` carries
  identity, an `hmacProof` of the pairing key, and the full controller
  topology; the response is the applied state. Re-PUT converges;
  `connectionId` is stable, the token rotates. Single-slot changes (type,
  motion caps, touchpad routing) ride per-controller PUT/DELETE routes:
  no token rotation for a toggle.
- UDP no longer mutates topology: the controller ADD/REMOVE/TYPE/CAPS
  opcodes, the ACK-retry choreography, the registration mutex, and the
  compat type re-send are gone from the JNI layer, which is now socket +
  crypto + streams only. The enriched heartbeat ack (epoch + active
  bitmap) drives a self-healing reconcile; the authenticated session-close
  notify (0x000F) lands as an immediate, reasoned disconnect.
- Per-session UDP keys: `HKDF-SHA256(pairingKey, sessionSalt, token)` with
  a direction byte in the nonce (the pairing key never touches UDP).
  Interop vectors are pinned on both ends.
- A coded 401 (`NOT_PAIRED` / `BAD_PROOF`) is terminal: the key is
  dropped, the row reads "needs pairing", and retries stop. Silent
  reconnects use bounded exponential backoff (1 s to 60 s) instead of a
  fixed 1.5 s loop.
- Binding commits the user's chosen type WITH the bind. The
  default-then-correct Xbox phase is gone end to end (USB-direct claims
  adopt the remembered type too). `bind` refuses slot ids the registry no
  longer knows (the zombie-slot guard) and the apply flow re-resolves the
  slot id after a USB path switch.
- The binding "Apply" overlay shows one spinner per real async action: the
  USB-direct switch (which can wait on the system permission prompt) and
  the single satellite round-trip.
- The "Emulate" picker renders from the satellite's localized
  `GET /api/catalog` (ETag-cached); unknown controller types render from
  server-provided strings instead of being impossible to select.
- Identity is machineId-only: legacy `satellite:<ip>:<udpPort>` keys, the
  ghost-row reconciliation, and the single-slot legacy key migration are
  deleted. Forgetting a satellite now also self-unpairs on it
  (`DELETE /api/pair`), closing any live session server-side.
- The per-connection touchpad-mode REST endpoint is gone; routing rides
  each controller descriptor (client-owned, single writer).
- JNI `openSocket` refuses non-IPv4 literals instead of silently streaming
  to 0.0.0.0 when discovery resolves an IPv6 address.

### Added

- Firebase Crashlytics integration for crash + ANR reporting.
  Crashlytics is the only Firebase SDK on the classpath; Firebase
  Analytics is deliberately NOT included (see the comment in
  `app/build.gradle.kts` for the rationale). The `google-services` and
  `firebase-crashlytics` Gradle plugins remain conditional on
  `app/google-services.json` so local builds without a Firebase project
  still compile and run (Crashlytics no-ops at runtime via a
  `FirebaseApp.getApps` check).
- In-app crash-reporting opt-out: `SettingsActivity` reachable from
  the gear icon on the main screen → *Share crash reports*. Backed by
  `CrashReportingStore` (separate `user_preferences.xml`
  SharedPreferences, included in cloud backup) and
  `CrashReportingController` (process-lifecycle observer that bridges
  the store to `FirebaseCrashlytics.setCrashlyticsCollectionEnabled`).
- `PRIVACY.md` describing data collection, processors, and user choices.
- `network_security_config.xml` denying cleartext traffic explicitly
  (the previous `usesCleartextTraffic` removal was implicit).
- `app/src/androidTest/` smoke test that launches `MainActivity` and
  asserts the process survives `onResume`.
- `StrictMode` thread + VM policy in debug builds, gated on
  `FLAG_DEBUGGABLE`.
- Mapping file (`mapping.txt`) is now shipped with every signed release,
  cosign-signed alongside the APK/AAB, so external de-obfuscation of
  prod stack traces is possible without Firebase access.

### Removed

- `firebase-analytics` dependency. Pulling it in would have
  auto-collected events (`first_open`, `session_start`, `screen_view`,
  `app_remove`, ...) and caused the manifest merger to inject
  `com.google.android.gms.permission.AD_ID` into the production APK,
  contradicting the policy's zero-analytics / no-advertising-ID
  posture. With Analytics out, the only Firebase product running is
  Crashlytics, and the merged manifest no longer declares `AD_ID`.

### Changed

- Overlay resends are edge-burst + keepalive: real input stays event-driven;
  a state change is re-sent 3 scheduler ticks in a row (50 ms apart) to heal
  a lost edge frame, then the stream idles at a 1 Hz keepalive, replacing
  the previous constant 250 Hz re-send of the last state.
- `versionCode` and `versionName` are now derived from CI environment
  variables (`DISH_VERSION_CODE` / `DISH_VERSION_NAME`), with a
  `git describe` fallback for local dev. The hardcoded `1` / `"0.0.0"`
  defaults remain only when neither signal is present.
- `release.yml` stages `mapping.txt` into `dist/` alongside the AAB and
  APK so it gets the same SHA256SUMS + cosign signature treatment.

### Notes

This is the first tracked release, so it covers the public 1.0.0 feature
set plus the build, release, and observability work that a downstream
consumer of the binary cares about. Earlier development history is in
`git log`.

---

## [1.0.0] - TBD

Initial public release. Tag will be created when the Play Store internal
testing track is opened.

[Unreleased]: https://github.com/TinkerNorth/dish-android/compare/1.0.0...HEAD
[1.0.0]: https://github.com/TinkerNorth/dish-android/releases/tag/1.0.0
