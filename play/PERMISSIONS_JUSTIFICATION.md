# Permission justifications for Play Console

These are the user-facing explanations for the Play Console "Sensitive Permissions" form. Permissions that Play does not flag as sensitive are included for completeness so the full posture is in one place.

## FOREGROUND_SERVICE / FOREGROUND_SERVICE_CONNECTED_DEVICE

**Why we declare it**: Dish keeps a streaming controller session alive while the user has the app backgrounded, so input keeps flowing to the host PC without latency spikes from being killed by the OS. The `connectedDevice` service type is the correct match per Play policy: this is a user-initiated session to a paired physical device (the host PC or console).

## RECORD_AUDIO

**Why we declare it**: A DualShock 4 v2 or DualSense plugged into a PC presents its own microphone endpoint, and games and voice chat on the PC use it. When Dish emulates one of those controllers, the phone's microphone stands in for that endpoint, so the user can talk through the emulated pad the same way they would through a real one.

**When it is requested**: at runtime, only when the user turns the per-controller Microphone switch on, on that controller's binding screen. The switch is off by default and is offered only where the whole path can carry a microphone (an audio-capable emulated controller type, on a Satellite host with controller audio enabled). Declining leaves the rest of the app working; the row says it needs permission instead of pretending to capture.

**What happens to the audio**: it is captured in 20 ms windows, Opus-encoded, and sent over the same encrypted UDP session as the controller input to the user's own paired PC on their local network. It is never written to storage, never uploaded to the internet, never included in crash reports, and never sent to TinkerNorth (which operates no server). There is no recording feature.

**How the user stops it**: turning the switch off, muting (the mute button on the on-screen controller, or the mute button on a connected DualSense), unbinding the controller, or revoking the permission. Mute is enforced by stopping the capture rather than by sending silence, so a muted controller sends no audio packets at all.

## FOREGROUND_SERVICE_MICROPHONE

**Why we declare it**: the same streaming session that carries controller input carries the controller's microphone, and a user who backgrounds the app mid-game expects both to keep working. The `microphone` service type is added to the session's types ONLY while a controller with the Microphone switch on is actually streaming, and it is always accompanied by `connectedDevice`. A session with no controller microphone in it never claims the type, so the microphone indicator in the status bar always corresponds to a microphone Dish is really using. The type is claimed from the foreground (the binding screen, its permission prompt, or the controller overlay), which is what the Android 12+ while-in-use rule requires.

## BLUETOOTH_CONNECT (API 31+) / BLUETOOTH / BLUETOOTH_ADMIN (API ≤30 fallback)

**Why we declare it**: For Bluetooth-host mode, Dish pairs with the user's PC, console, or set-top box and presents itself as a standard Bluetooth HID gamepad. Dish can also connect a Bluetooth game controller as an input source. The permission is requested at runtime, only when the user starts a Bluetooth setup step or taps Add under Bluetooth Hosts in the Connections screen. Declining still lets the app run, since Wi-Fi mode does not need Bluetooth.

## BLUETOOTH_SCAN (API 31+)

**Why we declare it**: Dish scans for nearby Bluetooth devices so the user can discover and pair two things: a Bluetooth host to act as a gamepad for, and a Bluetooth game controller to forward input from. It is declared with the `neverForLocation` flag because Dish does not derive location from Bluetooth scan results. The permission is requested at runtime, alongside the Bluetooth setup steps and the Add action under Bluetooth Hosts. Declining still lets the app run, since Wi-Fi mode does not need Bluetooth.

## POST_NOTIFICATIONS (API 33+)

**Why we declare it**: We surface a sticky session notification while a controller is actively streaming, plus actionable error banners (satellite disconnected mid-game, discoverability expired, etc.). Declining is graceful: the app runs normally, just without the visible notification.

## WAKE_LOCK

**Why we declare it**: Prevents the CPU from sleeping while a controller is bound to a host. Input latency on a sleeping CPU spikes from ~10ms to multi-100ms during sleep-wake transitions, which is unplayable. The wake lock is held only while a session is active.

## VIBRATE

**Why we declare it**: In-game rumble events from the host (PlayStation-style force feedback) are routed to the phone's vibration motor via `MSG_RUMBLE` packets.

## CHANGE_WIFI_MULTICAST_STATE

**Why we declare it**: Required to receive mDNS / Bonjour service-discovery beacons from the Satellite running on the LAN. Without it, the phone cannot find Satellites announcing themselves on `_satellite._udp` over `224.0.0.251:5353`.

## INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE

**Why we declare it**: Standard "we send and receive UDP packets on the local network" permissions. Dish makes no off-LAN internet connections except for crash diagnostics (opt-out). The HTTPS pairing handshake and the UDP gamepad stream both target the user's own LAN Satellite.

## Permissions Dish does NOT request (deliberate)

Worth calling out in the listing copy / reviewer notes:

- No location (any API level).
- No advertising ID (`com.google.android.gms.permission.AD_ID`). Firebase Analytics is deliberately omitted from the build to avoid auto-injection.
- No camera, contacts, calendar, SMS, call log, photos, or files.
- The microphone is the only sensitive permission Dish requests, it is opt-in per controller, and the audio it captures is never recorded or uploaded (see RECORD_AUDIO above).
