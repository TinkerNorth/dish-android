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

---

## 8. Steam Controller (implemented, needs hardware verification)

**Status:** Implemented from SDL and `hid-steam` (see `THIRD_PARTY.md`), never run against the
hardware. `Parser::STEAM_CONTROLLER` decodes the state packet; `InitKind::STEAM_QUIET` stops the
firmware's stand-alone keyboard/mouse emulation at attach and `runTeardown` restores it on every
exit path that still has the device, physical unplug included (the unplug path detaches the native
device too, so the attempt is made even though an unplugged wired pad has already lost the volatile
settings with its power). The restore loads the firmware's stand-alone defaults, not a snapshot of
the pre-claim state; that is deliberate and mirrors SDL's close sequence, and Steam re-pushes its
own configuration whenever the pad reconnects to it. Listed in `kImported`, so Direct is opt-in and
never auto-claimed.

Because the released pad re-enumerates as a keyboard and mouse, never as a framework gamepad, the
path FSM settles Standard immediately on release (`frameworkExpected` in `UsbPathMachine`) instead
of waiting for a re-enumeration that cannot come and stranding in RestoreStuck. The foreground
service also stays up while any Direct claim is held, so backgrounding the app does not leave the
process (and the pending restore) at the mercy of the low-memory killer.

Dongle connect/disconnect events (`ID_CONTROLLER_WIRELESS`, same endpoint as input) are classified
by `checkWirelessEvent`: a disconnect publishes a neutral state, since a powering-off pad would
otherwise leave its last input latched on the wire, plausibly the held Steam button of the
power-off gesture; a connect re-runs the quiet init, since the pad rebooted into stand-alone
defaults and would stream without motion while its lizard keyboard leaked into the phone.

**Unverified and worth checking first:**
- IMU axis order and signs. Gyro is mapped pitch/yaw/roll from raw X/Z/Y and accel from X/Z/-Y,
  following SDL's sensor block; the same signs are unverified for the Switch Pro (item 2).
- Right pad as right stick. It recentres on lift and carries SDL's 15-degree shell rotation but not
  SDL's extra +1000 offset, which would park the stick off centre. Feel is untested; an outer
  radius or response curve may be needed.
- Whether the settings survive an unplug. They are assumed volatile. A wired pad powers down when
  unplugged, but a controller left paired to the dongle stays powered, so an app kill between attach
  and teardown leaves it mute as a desktop mouse until its own power cycle (hold the Steam button)
  or until the relaunched app re-claims it.
- The wireless event framing and the reconnect re-init, which follow `hid-steam` but have never
  seen a real dongle.

**Known limitation, the dongle reaches one slot:** the wired pad enumerates as mouse, keyboard,
pad; the dongle enumerates as keyboard then four pad interfaces, one per paired controller.
`findInterruptInPair` breaks a rank tie by taking the first candidate, so only the first slot is
ever claimed. A pad paired to another slot fails `probeDecodable` and falls back to routed, which
is safe but looks like "Direct failed". Reaching the others means attaching each candidate in turn
rather than picking one up front.

The interface pick also assumes the emulated keyboard and mouse declare the HID boot subclass.
`hid-steam` deliberately does not rely on that, distinguishing the real pad by its report
descriptor instead. If the assumption is wrong the config packets go to the wrong interface, stall,
and the claim falls back to routed after roughly a second.

**Deliberately absent:**
- Rumble. The pad has no motors, only trackpad voice coils driven by `ID_TRIGGER_HAPTIC_PULSE`
  pulse trains; the simple rumble command is Steam Deck firmware only, and `hid-steam` gates force
  feedback on the Deck quirk. `parserHasRumble` is false, so nothing is advertised.
- The grip buttons, which have no XUSB equivalent.
- Streaming either trackpad over `MSG_TOUCHPAD`, which would double-actuate against the right stick.
- Bluetooth. The pad speaks Valve's own BLE protocol, not HID over GATT, so it never reaches the
  framework as a gamepad.

**Where:** `app/src/main/cpp/usb_parsers.cpp` (`decodeSteamController`, `buildSteamConfigPacket`,
`checkWirelessEvent`, `modelExpectsFrameworkGamepad`, `runInit`, `runTeardown`),
`app/src/main/cpp/usb_host.cpp` (`attachDevice`, `pollLoop`, `shutdownLocked`),
`app/src/main/java/.../source/usb/UsbPathMachine.kt` (`frameworkExpected`),
`app/src/main/java/.../source/usb/UsbGamepadManager.kt` (`gameInterfaceRank`, `releaseAllDirect`),
`app/src/main/java/.../composer/StreamingServiceController.kt` (claim-hold).

**Acceptance:** A wired Steam Controller picked as Direct streams sticks, triggers, buttons and
motion, drives nothing on the phone while claimed, and works as a desktop mouse again after being
unplugged or switched back to Standard.
