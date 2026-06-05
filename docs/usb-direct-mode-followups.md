# USB Direct-mode follow-ups (low priority)

Deferred items from the `explore/usb-direct-mode` review. The high and medium issues
(resting-stick deadzone, off-main claim/detach, multi-identical-controller dedup, caps refresh
after lane switch, migration attach fallback) are already fixed on the branch. Each item below is
written so it can be picked up on its own. Verify file:line against current code first.

---

## 1. Xbox One (GIP) coverage is partial

**Context:** `decodeXboxOneGip` only handles input report `0x20`. The Guide (Xbox) button arrives in
a separate report and is never decoded, so it does nothing in Direct mode. The init is a single
5-byte power-on (`runInit` / `InitKind::XBOX_ONE_POWERON`), which starts many controllers but not
every Series/Elite model that expects the fuller GIP announce/identify handshake. `probeDecodable`
gives up after ~320ms of no decodable report, so a slow-to-start GIP pad silently falls back to
Routed.

**Where:** `app/src/main/cpp/usb_parsers.cpp` (`decodeXboxOneGip`, `runInit`),
`app/src/main/cpp/usb_host.cpp` (`probeDecodable` timeouts).

**Task:** Decode the GIP guide-button report and map it (if a target XUSB bit is wanted). Consider a
fuller GIP handshake for the models that stay silent. Re-evaluate the probe timeout/attempt counts so
slow starters are not dropped.

**Acceptance:** Guide button registers; a Series X|S and an Elite Series 2 both reach Direct mode
reliably from cold plug-in.

---

## 2. Switch Pro IMU sign/orientation is unverified

**Context:** The Switch Pro IMU scaling is correct (`32767/28568` gyro, `32767/16384` accel), but the
per-axis signs and axis order were a straight map and were never checked on hardware (the code itself
notes "signs may need an on-device flip").

**Where:** `app/src/main/cpp/usb_parsers.cpp` (`decodeSwitchProUsb`, IMU block).

**Task:** With a real Switch Pro in Direct mode, confirm gyro/accel axis directions against the
satellite's motion expectation and flip signs / reorder axes as needed.

**Acceptance:** Tilting/rotating the controller moves the in-game motion in the matching direction on
all three axes.

---

## 3. Generic HID parser is unreachable

**Context:** `decodeGenericHidGamepad` exists in C, but the Kotlin claim gate (`isCandidate` ->
`isKnownFastLaneModel`) only allows devices whose VID/PID is in the parser table, and no table entry
uses `GENERIC_HID_GAMEPAD`. So the generic path never runs through the normal flow.

**Where:** `app/src/main/java/.../source/usb/UsbGamepadManager.kt` (`isCandidate`),
`app/src/main/cpp/usb_parsers.cpp` (`decodeGenericHidGamepad`).

**Task:** Decide the intent. Either (a) wire up an explicit, clearly-labelled "try an unknown
gamepad-shaped HID device" opt-in that routes to the generic parser, or (b) remove the unreachable
generic parser to cut dead code.

**Acceptance:** Either an unknown gamepad can be attempted via a deliberate user action, or the dead
path is gone. No silent generic claims.

---

## 4. Output mutex held across encrypt + sendto

**Context:** `publishIfChanged` holds `g_slotsMtx` (and the caller `applyUsbReport` holds
`g_devicesMtx`) across the ChaCha20-Poly1305 encrypt and the `sendto` syscall. Both mutexes are
global and shared by the framework input path and every Direct-mode poll thread. Fine for one or two
controllers; with four pads near 1kHz this serializes ~4k syscalls/sec through one lock. Pre-existing
design, now exercised harder by the dedicated poll thread.

**Where:** `app/src/main/cpp/satellite_jni.cpp` (`publishIfChanged`, `applyUsbReport`).

**Task:** Measure under a 3 to 4 controller load. If the lock is a bottleneck, shrink the critical
section (snapshot the report under the lock, encrypt+send outside it) without breaking the
`devices < slots < sessions` lock order.

**Acceptance:** No measurable added latency with four controllers streaming at their native rate.

---

## 5. Framework device state leaks on lane switch

**Context:** When a controller is claimed into Direct mode, its old framework `g_devices[fwId]` entry
is never erased (the binding observer only `unbindPhysicalSlot`s it). It is a small per-claim leak
that lives until process death. This is the existing behaviour for framework devices generally, just
more frequent now.

**Where:** `app/src/main/cpp/satellite_jni.cpp` (`g_devices`),
`app/src/main/java/.../hotpath/input/PhysicalSlotBindingObserver.kt` (disappeared handling).

**Task:** When a framework device is removed (or superseded by a synthetic twin), call
`dispatch::forgetDevice(fwId)` so the entry is reclaimed.

**Acceptance:** `g_devices` size returns to baseline after a plug -> claim -> unplug cycle.

---

## 6. Move USB claim/detach off the main thread (safely)

**Context:** The claim path (`attemptClaim`: USB init handshake + decode probe, up to ~400ms) and
detach (`detachUsbDevice` joins the native poll thread) run synchronously on the broadcast/main
thread, so a plug-in or unplug briefly hitches the UI. A first attempt to move them onto
`Dispatchers.IO` REGRESSED streaming and was reverted: the claim path also mutates
`PhysicalGamepadRegistry._devices` (via non-atomic `_devices.value = _devices.value + ...`) and the
`directFailed` HashMap, both of which Android's `InputManager` callbacks write on the main thread.
Off-main, `addUsbSynthetic` raced `onInputDeviceRemoved/Changed` for the just-stolen device, dropping
the synthetic device so nothing registered on the host (symptoms: no virtual controller, multi-second
"connecting", no timeout screen).

**Prerequisite before retrying:** make `PhysicalGamepadRegistry` writes thread-safe first. Convert
every `_devices.value = _devices.value <op>` to `_devices.update { <op> }`, and guard or replace the
`directFailed` HashMap (ConcurrentHashMap, or confine to one dispatcher). `claimedDevices` /
`promptedDevices` also need guarding plus an in-flight guard so a unplug-during-claim does not leave a
stale entry. Only then move `attemptClaim` / `detachUsbDevice` to IO.

**Where:** `UsbGamepadManager.kt` (`claimAndReport`, `attemptClaim`, `handleDetached`),
`PhysicalGamepadRegistry.kt` (all `_devices` mutations + `directFailed`).

**Acceptance:** Plug a recognised controller at app start with no UI hitch; repeated plug/unplug
stress never drops the synthetic device or its host registration.

---

## 7. Cosmetic: two strings on one line

**Context:** `path_reason_onscreen` and `path_reason_unknown_model` share a single physical line.
Valid XML, just messy.

**Where:** `app/src/main/res/values/strings.xml`.

**Task:** Put each `<string>` on its own line.

**Acceptance:** One string per line.
