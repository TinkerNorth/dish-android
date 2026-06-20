# USB Direct-mode follow-ups (low priority)

Deferred items from the `explore/usb-direct-mode` review. The high and medium issues
(resting-stick deadzone, off-main claim/detach, multi-identical-controller dedup, caps refresh
after lane switch, migration attach fallback) are already fixed on the branch. Each item below is
written so it can be picked up on its own. Verify file:line against current code first.

---

## 1. Xbox One (GIP) coverage is partial

**Context:** `decodeXboxOneGip` handles input report `0x20` and the Guide button (decoded from the
virtual-key `0x07` report and merged as a sticky `XUSB_GUIDE` bit via `ParserState`, covered by
`usb_parsers_test.cpp`). Two gaps remain: the init is now a 3-packet GIP sequence (power-on, LED-on,
auth-done) built by `buildGipInitPacket` for `InitKind::XBOX_ONE_POWERON`, with an extra set-mode
packet for the Xbox One S and Elite Series 2 (`InitKind::XBOX_ONE_S`). This starts many controllers
but still omits the full GIP announce/identify handshake some Series models expect, and
`probeDecodable` gives up after ~320ms, so a slow-to-start GIP pad falls back to Routed.

**Where:** `app/src/main/cpp/usb_parsers.cpp` (`buildGipInitPacket`), `app/src/main/cpp/usb_host.cpp`
(`probeDecodable` timeouts).

**Task:** Add the fuller GIP announce/identify handshake for the silent models, and re-evaluate the
probe timeout/attempt counts so slow starters are not dropped. Both need that hardware to verify.

**Acceptance:** A Series X|S and an Elite Series 2 reach Direct mode reliably from cold plug-in.
End-to-end Guide also needs the satellite to forward `wButtons` bit `0x0400` to ViGEm.

---

## 2. Switch Pro IMU sign/orientation is unverified

**Context:** The Switch Pro IMU scaling is correct (`32767/28568` gyro, `32767/16384` accel), and the
axis order is now rotated onto the DS4 wire convention with pitch and yaw hardware-confirmed. Roll's
sign and the three accel signs are still unverified (the code notes exactly this).

**Where:** `app/src/main/cpp/usb_parsers.cpp` (`decodeSwitchProUsb`, IMU block).

**Task:** With a real Switch Pro in Direct mode, confirm gyro/accel axis directions against the
satellite's motion expectation and flip signs / reorder axes as needed.

**Acceptance:** Tilting/rotating the controller moves the in-game motion in the matching direction on
all three axes.

---

## 3. Generic HID parser was unreachable (resolved)

**Resolved.** A per-controller path toggle (`PathCard` / `ControllerAdapter`) routes an unrecognised
gamepad-shaped device to the generic parser behind a "layout is guessed, may read wrong" confirm.
Auto-claim still requires a known model (`resolvePath` defaults unknown models to Standard), so the
generic parser is reached only through that deliberate, reversible action. No silent generic claims.

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

## 5. Framework device state leaked on lane switch (resolved)

**Resolved.** `SatelliteNative.forgetPhysicalDevice` (calling `dispatch::forgetDevice`) is invoked by
`PhysicalSlotBindingObserver` for every departed framework device id (claimed synthetics are still
freed by `detachUsbDevice`), so `g_devices` returns to baseline a few seconds after a plug, claim,
unplug cycle.

---

## 6. Move USB claim/detach off the main thread (safely)

**Context:** `doClaim` (USB init handshake + decode probe, up to ~400ms) and `detachUsbDevice`
(joins the native poll thread) still run synchronously on the broadcast/main thread, so a plug-in or
unplug briefly hitches the UI. A first attempt to move them to `Dispatchers.IO` regressed streaming
and was reverted: the claim path mutates `PhysicalGamepadRegistry` state that Android's
`InputManager` callbacks write on the main thread, so off-main `addUsbSynthetic` raced
`onInputDeviceRemoved/Changed` for the just-stolen device and dropped the synthetic (no virtual
controller, multi-second "connecting", no timeout screen).

**Already done:** `reconcile` is idempotent (no-ops in the resolved state, skips a vid/pid with a
recorded `directFailed`), so the repeated full-claim stall on every `onResume` is gone. The
`PhysicalGamepadRegistry` writes are `_devices.update {}`; `directFailed` is a `ConcurrentHashMap`,
and `claimedConns` is a plain `HashMap` mutated on the main thread.

**Task:** Move `doClaim` / `detachUsbDevice` to `Dispatchers.IO`, with an in-flight guard so an
unplug-during-claim cannot leave a stale synthetic. That is the actual fix for the plug-in hitch.

**Where:** `UsbGamepadManager.kt` (`runClaim`, `doClaim`, `releaseToFramework`).

**Acceptance:** A recognised controller plugged at app start causes no UI hitch; repeated plug/unplug
stress never drops the synthetic device or its host registration.

---

## 7. USB-direct rumble output (implemented, needs hardware verification)

**Status:** Implemented from the Linux kernel drivers, not yet verified on hardware. Builders exist
for Xbox 360, Xbox One GIP, DualShock 4, DualSense, and Switch Pro. The write path, report layouts,
counter handling, and sources are in `docs/rumble.md`.

**Remaining work:**
- Verify each builder on real hardware (motor mapping, magnitude feel, and that no controller NAKs
  the report). The Xbox One GIP `/512` divisor caps at half scale, matching xpad; revisit if weak.
- Stadia uses a SET_REPORT control transfer (not interrupt OUT), so it has no builder yet; the
  generic-HID parser has none either. Both stay silent rather than guess.
- Trigger-motor haptics on GIP pads need the wire-format change in `docs/rumble.md` FR-2.

**Where:** `app/src/main/cpp/usb_parsers.cpp` (`runRumble`, `switchEncodeMotor`, `runInit`),
`app/src/main/cpp/usb_host.cpp` (`sendRumble`, `DeviceCtx`), `app/src/main/cpp/satellite_jni.cpp`
(`sendUsbRumble` JNI), `SatelliteNative.kt` / `PhysicalInputNative.kt`,
`app/src/main/java/.../hotpath/input/RumbleRouter.kt`.

**Acceptance:** A claimed USB-direct pad of each verified family rumbles on `MSG_RUMBLE` and stops on
the 0,0 packet (or the safety auto-stop), with no effect on input latency. Unverified families either
work or stay silent; none receives a malformed report.
