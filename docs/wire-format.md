# Wire format

The Android, macOS, and Linux clients all talk to the same `satellite`
server and must produce byte-identical traffic. This document is the
reference; any client that wants to interop has to match it exactly.

The wire format is shared across all four TinkerNorth repos; if you
change anything here, mirror the change in `dish-linux`, `dish-mac`,
and `satellite` in the same release cycle.

## Transport

All traffic is LAN-only. There is no relay, no cloud, no outbound
connection to anything off the subnet.

| Channel | Transport | Default port | Encrypted |
|---|---|---|---|
| Discovery (broadcast) | UDP | 9879 | no |
| Discovery (mDNS) | UDP multicast | `224.0.0.251:5353` | no |
| Pairing + REST | HTTPS | 9443 | TLS |
| Gamepad stream | UDP | 9876 | ChaCha20-Poly1305 |

Discovery and the stream socket are owned by the JNI layer
(`satellite_jni.cpp`). Pairing and the REST surface live in
`SatelliteHttpClient.kt` — pure Kotlin so the TLS stack stays on the
JVM and the NDK build doesn't have to ship OpenSSL.

## Discovery

The client browses for `_satellite._udp` over mDNS and also listens
for legacy UDP broadcast beacons on port 9879. Each beacon is a JSON
object including at minimum `name`, `ip`, `udpPort`, and `pairPort`.
`discoverServers(discPort, timeoutMs)` is blocking — callers schedule
it on `Dispatchers.IO`.

## Pairing

```
Client                                 Satellite (9443/HTTPS)
  │                                              │
  ├─ generate X25519 keypair                     │
  ├─ POST /api/pair                              │
  │   { pubKey, pin, deviceId, label }   ───────►│
  │                                              ├─ verify PIN (one-shot)
  │                                              ├─ crypto_scalarmult → shared
  │                                              ├─ destroy PIN
  │                                              ◄── { token, … }
  ├─ derive 256-bit ChaCha20-Poly1305 key       │
  │  from shared secret                          │
  └─ store (token, key) in connection_store.xml │
```

The PIN never traverses the wire encrypted, but the request runs over
TLS and is consumed once. Neither side ever transmits the derived key.

## Session crypto

Every UDP gamepad packet has the same outer shape:

```
+--------+----------+-------------------------+
| token  | counter  | ChaCha20-Poly1305 inner |
| u32 BE | u32 BE   | (ciphertext + 16B tag)  |
+--------+----------+-------------------------+
   4 B       4 B               N + 16 B
```

- **Cipher:** ChaCha20-Poly1305 IETF.
- **Key:** 256 bits, derived from the X25519 shared secret at pairing
  time.
- **Nonce:** 12 bytes, big-endian. First 8 bytes zero, last 4 bytes
  = `counter`. The counter is a per-session monotonic `uint32_t`
  starting at 0 and incremented for every send.
- **AAD:** the 4-byte `token`.
- **Inner layout:** `msgType (u16 LE) | payloadLen (u16 LE) | payload`.

Counter rollover is treated as a session-fatal event — the client
opens a new socket rather than wrapping.

The send path uses `MSG_NOSIGNAL` and sets `IP_TOS = 0xB8` (DSCP EF)
on the socket. Linux UDP `sendto` is thread-safe per socket, so the
hot path takes no userspace lock.

## Opcodes

### Outbound (client → satellite)

| Opcode | Name | Inner size | Purpose |
|---|---|---|---|
| `0x0001` | `MSG_GAMEPAD_DATA` | 13 B | per-event XUSB report |
| `0x0002` | `MSG_HEARTBEAT_PING` | 0 B | keepalive (250 ms cadence) |
| `0x0004` | `MSG_CONTROLLER_ADD` | 3–4 B | register slot |
| `0x0005` | `MSG_CONTROLLER_REMOVE` | 1 B | unregister slot |
| `0x0008` | `MSG_CONTROLLER_TYPE` | 2 B | Xbox vs PlayStation hint |
| `0x000A` | `MSG_MOTION` | 17 B | IMU sample (rate-limited) |
| `0x000B` | `MSG_BATTERY` | 3 B | level + status (de-duped) |
| `0x000C` | `MSG_TOUCHPAD` | 16 B | two-finger touchpad frame |
| `0x000E` | `MSG_CONTROLLER_CAPS_UPDATE` | 3 B | live cap-bit change |

### Inbound (satellite → client)

| Opcode | Name | Notes |
|---|---|---|
| `0x0003` | `MSG_HEARTBEAT_ACK` | confirms session liveness |
| `0x0006` | `MSG_CONTROLLER_ACK` | result of a controller-add request |
| `0x0007` | `MSG_SERVER_STATUS` | server-side health |
| `0x0009` | `MSG_RUMBLE` | drive phone vibrator (7 B payload) |
| `0x000D` | `MSG_LIGHTBAR` | decoded and dropped on Android |

## Outbound payload layouts

### `MSG_GAMEPAD_DATA` (0x0001) — 13 B

```
+----------+---------------------------+
| ctrlIdx  |       XUSB_REPORT         |
|  u8      |        12 bytes           |
+----------+---------------------------+
```

XUSB_REPORT is 12 bytes, little-endian, `#pragma pack(1)`:

```
wButtons    u16  XInput-compatible bitmap (DPAD, A/B/X/Y, …)
bLeftTrig   u8
bRightTrig  u8
sThumbLX    i16
sThumbLY    i16
sThumbRX    i16
sThumbRY    i16
```

Sticks follow the XInput convention: pushing up is **positive Y**.
Android `AXIS_Y` is inverted (up is `-1.0f`), so the producer negates
before scaling to `i16`.

### `MSG_CONTROLLER_ADD` (0x0004) — 3–4 B

```
ctrlIdx        u8
capabilities   u16 LE   (see "Capability bits" below)
controllerType u8       optional: 0 = Xbox, 1 = PlayStation
```

The optional trailing `controllerType` byte lets the receiver plug the correct
virtual device on the first add (no follow-up `MSG_CONTROLLER_TYPE` / replug).
Omitting it leaves the slot's existing type untouched on the receiver.

### `MSG_CONTROLLER_REMOVE` (0x0005) — 1 B

```
ctrlIdx       u8
```

### `MSG_CONTROLLER_TYPE` (0x0008) — 2 B

```
ctrlIdx       u8
controllerType u8       0 = Xbox, 1 = PlayStation
```

### `MSG_CONTROLLER_CAPS_UPDATE` (0x000E) — 3 B

Same layout as `MSG_CONTROLLER_ADD`. Lets the client revise the
capability word for an already-registered slot without making the
host unplug and re-add it — used when motion is enabled or disabled
after a slot is bound.

### `MSG_MOTION` (0x000A) — 17 B

```
ctrlIdx               u8
gyroX gyroY gyroZ     i16 LE × 3      (Cemuhook DSU axes)
accelX accelY accelZ  i16 LE × 3
timestampDeltaUs      u32 LE
```

Scaling:
- Gyro LSB = 2000 / 32767 deg/s.
- Accel LSB = 4 / 32767 g.
- `timestampDeltaUs` is microseconds since the previous emission.
  The first packet sends 0 — the receiver uses the zero to detect
  session start, so don't substitute a sentinel.

Rate-limited by `MotionRateLimiter` (≤250 Hz).

### `MSG_BATTERY` (0x000B) — 3 B

```
ctrlIdx  u8
level    u8   0..100, or 0xFF for unknown
status   u8   BatteryValidator.BATTERY_STATUS_*
```

De-duped by the caller — identical samples are dropped.

### `MSG_TOUCHPAD` (0x000C) — 16 B

```
ctrlIdx        u8
flags          u8   bit 0 = finger 0 active
                    bit 1 = finger 1 active
                    bit 2 = touchpad button pressed
f0TrackingId   u8
f0X f0Y        i16 LE × 2
f1TrackingId   u8
f1X f1Y        i16 LE × 2
eventTimeMs    u32 LE
```

Coordinates are normalized `i16` — receiver maps to the active
touchpad mode's coordinate space. The all-zero-coords-with-zero-flags
shape signals a clean lift-off; don't substitute a smear.

`fNTrackingId` is monotonic per finger contact; bumped when a finger
lifts and a new one lands. The receiver uses this to distinguish a
re-down from a continued drag.

Paced at ≤250 Hz by the caller; the JNI call is one encode plus one
encrypted `sendto`.

## Inbound payload layouts

### `MSG_CONTROLLER_ACK` (0x0006)

Surfaced through `getLastControllerAck(handle)` as a packed `int32`:

```
[31:16]  requestType   (the opcode this acks)
[15: 8]  ctrlIdx
[ 7: 0]  resultCode    0x00 = OK
                       0x01 = backend unavailable
                       0x02 = no slots
                       0x03 = already exists
                       0x05 = plugin failure
```

`-1` means no ACK has been observed yet (pre-extension satellite, or
the message hasn't returned). Reset between sessions with
`resetControllerAck` — divergence between paired sessions would let a
fresh slot read its predecessor's flags.

If the satellite is new enough to send the extended ACK, the motion
flags byte is also surfaced via `getLastControllerMotionFlags`:

```
bit 0 (0x01)  controller type supports IMU on the host
bit 1 (0x02)  the host actually created a motion sink
```

`-1` here is **not** "neither bit set" — it's "no extended ACK
observed", so the UI must show *unknown*, not *off*.

### `MSG_RUMBLE` (0x0009) — 7 B payload

```
ctrlIdx        u8
strong         u16 BE
weak           u16 BE
durationMs     u16 BE
```

Magnitudes are wire-format 0..65535. On the target actuator they map
to `VibrationEffect` amplitude 1..255 with rounding-up: any non-zero
request produces a perceptible buzz. `durationMs` is clamped to
`[1, 1500]` to bound the worst-case stranded buzz from a hung
satellite.

`ctrlIdx` selects which controller buzzes; the client reverse-maps it
(via the session handle and `SatelliteConnection.slots`) to the bound
slot and actuates that device. Per-slot-kind routing (phone vibrator,
framework `InputDevice` vibrator, or a USB-direct OUT-endpoint report)
and the strong/weak mapping are in [`rumble.md`](rumble.md).

### `MSG_LIGHTBAR` (0x000D) — 4 B payload

```
ctrlIdx   u8
R G B     u8 × 3
```

Android has no controller-LED API, so the client decodes the message
and drops it. The capability-aware satellite skips sending this
because dish-android does not advertise `CAP_LIGHTBAR`.

## Capability bits

`MSG_CONTROLLER_ADD` and `MSG_CONTROLLER_CAPS_UPDATE` carry a 16-bit
capability word:

| Bit | Name | Meaning |
|---|---|---|
| `0x0001` | `CAP_ANALOG_TRIGGERS` | analog `bLeftTrigger` / `bRightTrigger` |
| `0x0002` | `CAP_RUMBLE` | client can actuate `MSG_RUMBLE` |
| `0x0004` | `CAP_MOTION` | client will send `MSG_MOTION` |
| `0x0008` | `CAP_LIGHTBAR` | client can drive `MSG_LIGHTBAR` — **not set** by Android |

Bit definitions mirror `satellite/src/core/types.h::CAP_*`. The base
word sent by the Android client is `CAP_ANALOG_TRIGGERS | CAP_RUMBLE`,
ORed with `CAP_MOTION` when the slot has a usable gyroscope and the
user has motion enabled.

## Bluetooth HID

The Bluetooth path is an alternative transport — same controller
state, different framing. The phone presents as a standard HID
gamepad over `BluetoothHidDevice`; the host sees an Xbox-compatible
controller regardless of the cosmetic profile name.

HID descriptor (`buildHidDescriptor` in `BluetoothGamepad.kt`):

```
2 sticks  16-bit X / Y / Z / Rz
2 trigs    8-bit Rx / Ry
14 buttons 1 bit each
1 hat      4-bit (1..8, 0 = neutral)
report ID  1
```

The wire report (`buildHidReport`) is 14 bytes:

```
[0]      report id (1)
[1..2]   buttons u16 LE     low 16 bits only — bit 16+ is masked off
[3]      hat low nibble     0=neutral, 1=N, 2=NE, 3=E, 4=SE, 5=S,
                            6=SW, 7=W, 8=NW
[4..5]   left X   i16 LE
[6..7]   left Y   i16 LE
[8..9]   right X  i16 LE
[10..11] right Y  i16 LE
[12]     left trigger  u8
[13]     right trigger u8
```

Axis convention is XInput — stick up = positive Y. Producers (both
the physical path and the on-screen virtual pad) emit `+Short.MAX_VALUE`
when the stick is pushed up.

`BluetoothHidDevice.sendReport()` takes the report-id byte separately
from the payload, so the framework call strips byte 0 before
dispatch.

## Compatibility notes

- **Pre-extension satellites** send `MSG_CONTROLLER_ACK` with
  `payloadLen == 4` and no motion-flags byte. The client must keep
  the unknown-vs-known distinction (`-1` sentinel) rather than
  collapsing to `false`.
- **`MSG_MOTION` first packet** carries `timestampDeltaUs = 0`. The
  receiver uses this to detect the start of an IMU stream — don't
  substitute the previous packet's delta.
- **`MSG_TOUCHPAD` lift-off** is an all-zero coordinates frame with
  all flag bits cleared. Don't synthesise a held finger at the last
  known position when a lift happens.
- **Decoded buffer must size for 128 B**, not the message-set maximum.
  The bound holds structurally; sizing it to a constant keeps a future
  message addition from silently underflowing on the receive path.
