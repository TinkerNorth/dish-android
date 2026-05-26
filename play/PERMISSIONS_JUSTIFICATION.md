# Permission justifications for Play Console

These are the user-facing explanations for the Play Console "Sensitive Permissions" form. Permissions that Play does not flag as sensitive are included for completeness so the full posture is in one place.

## FOREGROUND_SERVICE / FOREGROUND_SERVICE_CONNECTED_DEVICE

**Why we declare it**: Dish keeps a streaming controller session alive while the user has the app backgrounded — so input keeps flowing to the host PC without latency spikes from being killed by the OS. The `connectedDevice` service type is the correct match per Play policy: this is a user-initiated session to a paired physical device (the host PC or console).

## BLUETOOTH_CONNECT (API 31+) / BLUETOOTH / BLUETOOTH_ADMIN (API ≤30 fallback)

**Why we declare it**: For Bluetooth-host mode, Dish pairs with the user's PC, console, or set-top box and presents itself as a standard Bluetooth HID gamepad. The permission is requested at runtime, only when the user taps "Add Bluetooth host" in the Connections screen. Declining still lets the app run — Wi-Fi mode does not need Bluetooth.

## POST_NOTIFICATIONS (API 33+)

**Why we declare it**: We surface a sticky session notification while a controller is actively streaming, plus actionable error banners (satellite disconnected mid-game, discoverability expired, etc.). Declining is graceful — the app runs normally, just without the visible notification.

## WAKE_LOCK

**Why we declare it**: Prevents the CPU from sleeping while a controller is bound to a host. Input latency on a sleeping CPU spikes from ~10ms to multi-100ms during sleep-wake transitions, which is unplayable. The wake lock is held only while a session is active.

## VIBRATE

**Why we declare it**: In-game rumble events from the host (PlayStation-style force feedback) are routed to the phone's vibration motor via `MSG_RUMBLE` packets.

## CHANGE_WIFI_MULTICAST_STATE

**Why we declare it**: Required to receive mDNS / Bonjour service-discovery beacons from the Satellite running on the LAN. Without it, the phone cannot find Satellites announcing themselves on `_satellite._udp` over `224.0.0.251:5353`.

## INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE

**Why we declare it**: Standard "we send and receive UDP packets on the local network" permissions. Dish makes no off-LAN internet connections except for crash diagnostics (opt-out). The HTTPS pairing handshake and the UDP gamepad stream both target the user's own LAN Satellite.

## Permissions Dish does NOT request — deliberately

Worth calling out in the listing copy / reviewer notes:

- No location (any API level).
- No advertising ID (`com.google.android.gms.permission.AD_ID`) — Firebase Analytics deliberately omitted from the build to avoid auto-injection.
- No microphone, camera, contacts, calendar, SMS, call log, photos, or files.
