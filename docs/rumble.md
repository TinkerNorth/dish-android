# Rumble

How rumble (force feedback) reaches the right device on dish-android,
what the platform can and cannot drive, and the satellite-side feature
requests needed to round out support. The wire layout is in the satellite
contract (`satellite/docs/contract.md`, `MSG_RUMBLE` 0x0009); the runtime
path is in [`architecture.md`](architecture.md) ("Rumble path").

## Routing model

The satellite sends `MSG_RUMBLE` with a `ctrlIdx`, a strong and a weak
magnitude (u16 each, 0..65535), and a `durationMs` (u16). The client
turns `(sessionHandle, ctrlIdx)` back into a bound slot and actuates
the device that owns it:

| Slot kind | Slot id | Actuator |
|---|---|---|
| On-screen virtual pad | `"virtual"` | phone vibrator (`VibratorManager` / `Vibrator`) |
| Framework gamepad (Bluetooth, routed USB) | non-negative `deviceId` | that pad's `InputDevice.getVibratorManager()` / `getVibrator()` |
| USB-direct (claimed) pad | negative synthetic id | device-specific report written to the pad's USB OUT endpoint |

Resolution: find the `SatelliteConnection` whose `handle` matches, then
the slot whose `controllerIndex` equals `ctrlIdx`
(`RumbleRouter.resolveSlotId`), then classify by slot id
(`RumbleRouter.classifyTarget`). Both are pure and unit-tested in
`RumbleRouterTest.kt`.

Routing is **strict**: only the targeted device buzzes. A physical pad
that exposes no usable actuator (no `InputDevice` vibrator, no USB OUT
endpoint, or a parser with no rumble builder) stays silent rather than
buzzing the phone as a stand-in. This is the deliberate answer to "only
the right device should rumble". If a phone-fallback is ever wanted for
feedback parity, it would be a single branch in `RumbleRouter.actuate`,
ideally behind a user setting.

## What Android can drive

Per target, the dimensions the platform actually exposes:

| Dimension | Phone (`VibratorManager`) | Framework pad (`InputDevice`) | USB-direct (claimed) |
|---|---|---|---|
| Independent strong + weak | only if the body has two actuators (rare) | only if the pad exposes two vibrator ids (rare) | yes, in the device report |
| Amplitude (magnitude) | yes, 1..255 (`hasAmplitudeControl`) | varies by pad (`hasAmplitudeControl`) | yes |
| Duration / oneshot | yes | yes | yes |
| Trigger (impulse) motors | not applicable | no public API | yes (Xbox One / Series GIP) |
| Lightbar / LED | not applicable | no public API | yes (DualShock / DualSense) |

Mapping rules the client applies:

- **Magnitude:** wire 0..65535 maps to amplitude 1..255, rounding up so
  any non-zero request is felt (`rumbleMagnitudeTo255`).
- **Strong / weak:** sent to vibrator id 0 / id 1 when the target
  exposes two actuators; single-actuator targets fold to one magnitude
  (`max(strong, weak)`) on both the legacy and combined paths, so a
  weak-dominant effect is still felt. Real dual-motor separation is
  only reachable on USB-direct pads.
- **Duration:** clamped to `[1, 1500]` ms (`rumbleSafeDurationMs`) to
  bound a stranded buzz from a hung satellite.

## Feature requests for the satellite / wire API

These need a change on the satellite (and a mirror in `dish-linux`,
`dish-mac`, `satellite`) before the client can do more.

### FR-1: Define sustained-rumble semantics (highest value)

XInput / ViGEm rumble has no duration: the game sets motor speeds and
they run until changed. So `durationMs` is something the satellite
synthesizes, and the contract is currently undocumented. If the
satellite emits one packet per motor-state change with a short duration
and does not re-send, any effect longer than that window stops early on
the client, which clamps to a oneshot.

Request: document the satellite behavior, and adopt the re-send model.
While motors are non-zero, re-send `MSG_RUMBLE` on a fixed cadence
(about every 1000 ms) carrying the current magnitudes, and send
`strong = weak = 0` once on stop. The client already treats 0,0 as a
stop and clamps duration to 1500 ms, so a ~1000 ms re-send under the
1500 ms cap sustains cleanly with no wire change. If instead the
satellite can know the true effect length, send that as `durationMs`.

### FR-2: Trigger (impulse) rumble fields

Xbox One / Series controllers have left and right trigger haptic
motors. `MSG_RUMBLE` carries only strong + weak, so trigger haptics
cannot be expressed. Request: either extend `MSG_RUMBLE` to 11 B with
`leftTrigger u16 BE` and `rightTrigger u16 BE`, or add a new
`MSG_RUMBLE_TRIGGERS` opcode, plus a `CAP_TRIGGER_RUMBLE` capability bit
the client sets only for a bound device that can actuate triggers. This
is only actuatable on USB-direct Xbox pads via GIP output reports, so it
should land after the USB-direct rumble follow-up below.

### FR-3: Per-slot rumble capability (nice to have)

The client advertises `CAP_RUMBLE` for every slot. With routing, a slot
backed by a motor-less pad cannot actuate. The client could revise
`CAP_RUMBLE` per slot through `MSG_CONTROLLER_CAPS_UPDATE` so the host
can skip rumble that would be dropped. Harmless if omitted (the host
simply sends rumble that no-ops), so this is a refinement, not a
blocker.

### FR-4 (adjacent, not rumble): Lightbar over USB-direct

`MSG_LIGHTBAR` (0x000D) already exists but Android drops it (no
framework LED API). A claimed USB-direct DualShock / DualSense could
drive its lightbar through the same OUT endpoint used for rumble. If
wanted, the client would advertise `CAP_LIGHTBAR` only for those slots.
Out of scope here; noted so the OUT-endpoint work is costed once.

## USB-direct rumble output reports

A claimed pad is driven by `usbparsers::runRumble`, which writes a
device-specific report to the interrupt OUT endpoint. Magnitudes are
the wire strong (large/low-frequency, left) and weak (small/high-
frequency, right), 0..65535. Layouts follow the Linux kernel drivers:

- **Xbox 360** (`XINPUT_360`, xpad): 8 bytes
  `00 08 00 <strong>>8> <weak>>8> 00 00 00`.
- **Xbox 360 wireless** (`XINPUT_360_WIRELESS`, xpad `xpad360w`): 12 bytes
  `00 01 0F C0 00 <strong>>8> <weak>>8> 00 00 00 00 00`, for the wireless
  receiver PIDs (0x0291, 0x0719, 0x02A1).
- **Xbox One / Series** (`XBOX_ONE_GIP`, xpad): 13 bytes
  `09 00 <seq> 09 00 0F 00 00 <strong/512> <weak/512> FF 00 FF`, where
  `seq` is a per-device output counter and `0F` is `GIP_MOTOR_ALL`.
- **DualShock 4** (`DUALSHOCK4`, hid-playstation): 32-byte report `0x05`,
  `valid_flag0 = 0x01`, `motor_right` at byte 4, `motor_left` at byte 5.
  No CRC over USB.
- **DualSense** (`DUALSENSE`, hid-playstation): 63-byte report `0x02`,
  `valid_flag0 = 0x01` (compatible vibration), `motor_right` at byte 3,
  `motor_left` at byte 4. No CRC over USB.
- **Switch Pro** (`SWITCH_PRO_USB`, hid-nintendo): 10-byte rumble-only
  report `0x10 <counter&0x0F> <left 4B> <right 4B>`. Each side is HD-
  rumble encoded at the neutral frequency from a coarse amplitude table.
  Vibration is enabled in `runInit` via subcommand `0x48`.

Counters that the protocol carries (Xbox One serial, Switch Pro packet
number) live in `DeviceCtx.outSeq`. The write is serialized against
detach by `DeviceCtx.outMtx` so it never races the fd close.

**Sources:** `drivers/input/joystick/xpad.c` (`xpad_play_effect`),
`drivers/hid/hid-playstation.c`, `drivers/hid/hid-nintendo.c`
(`joycon_encode_rumble`, `joycon_rumble_amplitudes`).

**Not yet covered:** Stadia and the generic-HID parser have no rumble
builder (Stadia rumble uses a SET_REPORT control transfer, not the
interrupt OUT path, so it is left out rather than guessed).
Trigger-motor haptics need FR-2. None of these report builders has
been verified on hardware yet; see
[`usb-direct-mode-followups.md`](usb-direct-mode-followups.md) item 7.
