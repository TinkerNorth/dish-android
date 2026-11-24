# Dish ↔ Satellite contract: client notes

The protocol contract (REST surface, UDP streams, crypto, liveness, identity)
lives in ONE place: **`satellite/docs/contract.md`** in the
[TinkerNorth/satellite](https://github.com/TinkerNorth/satellite) repo. This
client implements protocol 4 against it (`DISH_PROTOCOL_CURRENT` in
`core/net/DishProtocol.kt`; it offers 4 and downgrades inside 1 to 4 on a
Satellite's 409 echo); this file only records the Android-side mapping.

What each protocol version added, as this client meets it:

- **3** added HAPTIC_AUDIO 0x0015. It plays into a USB DualSense's own
  4-channel endpoint as a second quad `AudioTrack` beside the speaker one
  (`source/audio/SpeakerPlayoutPlan.kt`, `SpeakerPlayout.kt`); a slot that
  cannot play the waveform advertises no `hapticAudio` cap and takes the
  satellite's rumble reduction instead.
- **4** added the extended-buttons word behind the INPUT report (17 bytes
  instead of 13) and the descriptor cap `extendedButtons`. See "Extended
  buttons and the Steam Controller's extras" below.

## Where the contract lands in this app

| Contract concept | Android home |
|---|---|
| `hmacProof`, HKDF session key | `core/net/SessionCrypto.kt` (pinned interop vectors in `SessionCryptoTest`) |
| ControllerDescriptor JSON | `core/net/ControllerDescriptor.kt` |
| REST routes (PUT/GET/DELETE session + controllers, pair, unpair, catalog) | `core/net/SatelliteHttpClient.kt` via `core/net/DiscoveryGateway.kt` |
| Wire DTOs (SessionResponse, apply results, catalog) | `core/model/Models.kt` |
| Session lifecycle, desired vs applied slots, epoch reference | `source/connection/SatelliteConnection.kt` |
| Connect flow, terminal 401, exponential backoff, reconcile, self-unpair | `source/connection/SatelliteConnectionManager.kt` |
| UDP streams + enriched heartbeat ack + close-notify (socket/crypto only) | `app/src/main/cpp/satellite_jni.cpp` |
| Catalog cache (ETag, per-satellite) | `repository/SatelliteCatalogRepository.kt` |
| Host capabilities (`GET /api/server/capabilities`) pre-bind read | `repository/SatelliteCapabilitiesRepository.kt` → host layer + `source/store/SatelliteHostRuntimeStore.kt` |
| Feedback return paths (RUMBLE 0x0009, LIGHTBAR 0x000D, TRIGGER_EFFECTS 0x0010, PLAYER_LEDS 0x0011) | native decode in `satellite_jni.cpp` → `hotpath/input/RumbleBridge.kt` + `hotpath/input/FeedbackBridge.kt` → `RumbleRouter` / `FeedbackRouter` |
| Controller audio down (SPEAKER_AUDIO 0x0013, HAPTIC_AUDIO 0x0015) | native reorder + Opus decode per lane in `satellite_jni.cpp` → `hotpath/audio/SpeakerAudioBridge.kt` → `source/audio/SpeakerEngine.kt`, one `AudioTrack` per slot and lane at the pad endpoint's own width |
| Extended buttons (INPUT 0x0001, 17 B on a session settled at protocol 4) | the word is sourced per pad in `app/src/main/cpp/gamepad_input.cpp` and `usb_parsers.cpp`, held in `slot_table.cpp`, and framed by `send_path.cpp` from the session's settled version; the cap `extendedButtons` is written by `core/net/ControllerDescriptor.kt` |
| Controller database (`https://dish.tinkernorth.com/controllers/v1/`) | the fleet's contract is `dish-website/docs/controller-db.md`; the bundled copy is `app/src/main/assets/controllers/v1/`, read through `core/controllerdb/`, fetched by `source/controllerdb/HttpsControllerDbGateway.kt` and `ControllerDbUpdater.kt`, and turned into the native tables at build time by `scripts/gen_controller_tables.py` |
| Connection diagnostics (`GET /api/connections/{id}/diagnostics`, Satellite 2.3.0) | `core/net/SatelliteHttpClient.getConnectionDiagnostics`, asked for while a support report is prepared; the answer becomes the host's `snapshot` in `composer/BundleHostJson.kt` |
| Outbound telemetry destinations (satellite UDP vs Moonlight control stream) | `source/connection/TelemetrySink.kt`, resolved per slot by `composer/PhysicalReachabilityComposer.kt` |

## Extended buttons and the Steam Controller's extras

`wButtons` is full, so protocol 4 gives INPUT a second word, `extButtons`
(u32 little-endian), for the buttons an XInput pad does not have: the four
paddles, `misc1` (Capture, Share, the Steam "..." button), the Steam
Controller's trackpad clicks, trigger clicks and stick touches. The bits are
levels, like `wButtons`. The native send path frames 17 bytes on a session
settled at 4 and 13 below that, so a slot that talks to an older satellite
loses only the tail. A slot advertises the `extendedButtons` cap only when its
pad sources the word. The bit table lives in the satellite's contract
("Extended buttons").

The Steam Controller (2015 over USB or Bluetooth, 2026 over Bluetooth LE) is
the pad with the most extras. Each trackpad has a per-slot pick, Right stick,
Touchpad, Mouse or Off (`ui/common/TrackpadPickLabels.kt`); a click rides
`extButtons` as `leftPadClick` or `rightPadClick` only when the pick does not
consume it. The desktop clients never offer the Mouse pick.

## Controller database

Every client and the satellite share one controller database. This client
ships a copy, checks the website's manifest ten seconds after launch and every
six hours (`ControllerDbUpdater`), downloads a newer version only when the
manifest names one, and uses it only when every file matches the sizes and
SHA-256 values the manifest publishes; otherwise it keeps the copy it has. The
schema, the manifest and the rules for what a record may say are
`dish-website/docs/controller-db.md`.

## Diagnostics

`GET /api/connections/{id}/diagnostics` is authenticated like the reconcile
read and returns the satellite's own redacted view of one session
(`satellite-connection-diagnostics.v1`). The client asks for it only when the
person taps Report a problem, with a deadline of its own, and files the answer
as the host's `snapshot` in its `dish-diagnostics.v1` bundle
(`dish-website/docs/diagnostics-bundle.md`). A 404 to a valid proof means an
older satellite, and the client falls back to what it already read from
`/api/server/capabilities`. Nothing on UDP changes.

## Client behaviours required by the contract

- **Declarative topology.** A bind carries the FULL descriptor (type, caps,
  touchpad routing); the satellite plugs the right virtual device on the
  first try. While live, single-slot changes ride
  `PUT /api/connections/{id}/controllers/{idx}` so the session token never
  rotates for a toggle; the full-session `PUT /api/connections` runs only on
  connect and reconcile.
- **Terminal 401.** `code: NOT_PAIRED | BAD_PROOF` drops the stored pairing
  key, marks the satellite row "needs pairing", and STOPS retrying. Only a
  re-pair recovers.
- **Reconcile loop.** Every heartbeat ack carries the session epoch and the
  active-controller bitmap. On mismatch with the last applied state the
  client GETs the session; if applied ≠ desired it re-PUTs (free, the server
  converges).
- **Close-notify (0x000F).** Authenticated and immediate: `unpaired` is
  terminal, `replaced` stays down (the replacement session is already live),
  `shutdown`/`kicked` re-enter the bounded backoff (1 s → 60 s cap).
- **Host-feature grant.** `hostFeatures.mouseControl` is requested in the
  session PUT and the server computes the grant ONLY there. A mid-session
  toggle to `mouse` leaves wants ≠ granted even after a successful
  controller PUT, so `syncSlot` escalates to the reconcile path, whose
  full session re-PUT carries the request. The grant is part of the
  applied view the reconcile GET compares against.
- **Identity.** Satellites are keyed on `machineId` only. The pairing key is
  never used on the UDP path: each session derives
  `HKDF(pairingKey, sessionSalt, token)`.
- **Catalog.** The "Emulate" picker renders from `GET /api/catalog`
  (Accept-Language, ETag-cached). Known slugs (`xbox360`, `ds4`) keep bundled
  labels; an unknown type still renders from server strings; unknown
  feature/hostFeature slugs are silently not offered. The DS4 PAD mode is gated
  on the type's `features.touchpad.modes` containing `ds4` (read, not inferred
  from the type id); an absent `modes` array falls back to the legacy assumption.
- **Feedback caps advertise the actuator.** The descriptor's `lightbar`,
  `triggerEffects` and `playerLeds` caps ride only when the bound input can
  actually land them: a Direct-claimed pad whose family has the surface
  (DS4/DualSense lightbar, DualSense trigger effects + player LEDs, Switch
  Pro player lights), or the virtual pad, whose skin renders the lights the
  phone lacks (`VirtualPadFeedbackStore` → `GamepadTouchView`: lightbar ring
  around the trackpad, player-LED row, trigger-effect accents) and whose
  vibrator folds Moonlight trigger rumble through the rumble path. A
  framework-path pad (no raw output writer, no LED API) never advertises,
  so the satellite never sends it those messages.
- **Host features.** The host layer reads `hostFeatures.mouseControl`,
  `keyboardControl`, and `rumble`. Rumble is opt-OUT (absent slug → assumed, for
  back-compat with satellites predating it); keyboard is opt-IN (absent → not
  offered, and it stays unoffered until a phone-side source exists).
- **Pre-bind host read.** `GET /api/server/capabilities` carries a `host` block
  (capability presence + runtime). The setup flow probes it before binding so the
  host layer reflects the real receiver before the catalog round-trip
  (`setIfAbsent`, so a richer catalog still wins) and the candidate report can show
  a feature present-but-currently-down (motion backend) pre-bind. A server omitting
  the block (`host.catalog.supported == false`) leaves the optimistic default.
