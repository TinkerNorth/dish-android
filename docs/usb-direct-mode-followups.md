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

## 2. Switch Pro: the USB start sequence has not run on Android, and the IMU signs are unverified

**Context, start sequence:** A Switch Pro Controller on a cable stays silent after the sequence Dish
used to send (handshake `80 02`, timeout disable `80 04`, full report mode): it acknowledges the
handshake and streams nothing. It streams only once the timeout disable follows a handshake sent at
3 Mbit, which is the order Linux's hid-nintendo and SDL send every USB Switch pad: `80 02`, `80 03`,
`80 02`, `80 04`. Dish now sends that order, then the report-mode and vibration-enable subcommands,
to every model on `InitKind::SWITCH_PRO_HANDSHAKE` (the Pro, the 8BitDo adapters, the N64 Online
pad). The pad's need for the 3 Mbit step was shown on a Pro Controller over macOS (dish-mac PR 101
carries the probe table); the new sequence has not yet been run on an Android device. On a phone
whose kernel driver (hid-nintendo) ran the full sequence before Dish claimed the interface, the pad
streamed either way, which is the likely reason the old sequence passed its Android hardware checks.

Dish sends no IMU enable (subcommand `0x40`). Over macOS, with the same subcommands, the Pro's IMU
frames read zero (dish-mac PR 101 records it as nothing turning the IMU on); whether the Android
pitch and yaw confirmation below relied on the kernel driver having turned the IMU on first is not
known.

**Context, IMU:** The Switch Pro IMU scaling is correct (`32767/28568` gyro, `32767/16384` accel),
and the axis order is now rotated onto the DS4 wire convention with pitch and yaw
hardware-confirmed. Roll's sign and the three accel signs are still unverified (the code notes
exactly this).

**Where:** `app/src/main/cpp/usb_parsers.cpp` (`kSwitchInitSequence`, `runSwitchProHandshake`;
`decodeSwitchProUsb`, IMU block).

**Task:** With a real Switch Pro on a phone whose kernel has no hid-nintendo, confirm the pad streams
from its plugged-in state through Dish's own sequence. Then, in Direct mode, confirm gyro/accel axis
directions against the satellite's motion expectation and flip signs / reorder axes as needed; if
the IMU reads zero, add the IMU enable subcommand.

**Acceptance:** A Pro Controller plugged in cold reaches Direct mode and streams on a phone whose
kernel never woke it. Tilting/rotating the controller moves the in-game motion in the matching
direction on all three axes.

---

## 3. Generic HID parser was unreachable (resolved)

**Resolved.** A per-controller path toggle (`PathCard` / `ControllerAdapter`) routes an unrecognised
gamepad-shaped device to the generic parser behind a "layout is guessed, may read wrong" confirm.
Auto-claim still requires a known model (`resolvePath` defaults unknown models to Standard), so the
generic parser is reached only through that deliberate, reversible action. No silent generic claims.

---

## 4. Output mutex held across encrypt + sendto (resolved, measurement open)

**Resolved.** `publishIfChanged` stages the report and draws its counter under `g_devicesMtx` and
`g_slotsMtx`; the caller encrypts and sends it after letting both go (`stageDatagram`,
`transmitDatagram`). The session's send turn is held from the counter to `sendto`, because the
satellite drops a counter that is not above the last one it accepted from the session, whichever slot
it carries: the turn keeps the wire in counter order, and staging under the device lock keeps a
slot's counters in the order its latch consumed them. The global locks now cover the latch, the
binding and a memcpy; two sends to one session still serialize on its turn.

**Still open:** measure under a 3 to 4 controller load. **Acceptance:** no measurable added latency
with four controllers streaming at their native rate.

---

## 5. Framework device state leaked on lane switch (resolved)

**Resolved.** `PhysicalSlotNative.forgetPhysicalDevice` (calling `dispatch::forgetDevice`) is invoked by
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

**Where:** `app/src/main/cpp/usb_parsers.cpp` (`runMergedRumble`, `switchEncodeMotor`, `runInit`),
`app/src/main/cpp/usb_host.cpp` (`sendRumble`, `DeviceCtx`), `app/src/main/cpp/satellite_jni.cpp`
(`sendUsbRumble` JNI), `UsbDirectNative.kt` / `PhysicalInputNative.kt`,
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
defaults and would stream without motion while its lizard keyboard leaked into the phone. The
re-init runs on the pad's output worker (`OutputWorker::postReconnected`), so the poller never
waits on its control transfers.

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

---

## 9. Steam Controller (2026, "Triton") (implemented from SDL, needs hardware verification)

**Status:** Ported from SDL's `src/joystick/hidapi/SDL_hidapi_steam_triton.c` and the Valve headers
beside it (see `THIRD_PARTY.md`), never run against the hardware. It covers the wired pad
(28DE:1302) and both wireless dongles, the Proteus "Puck" (1304) and the Nereid (1305), on
`Parser::STEAM_TRITON` and `InitKind::STEAM_TRITON_QUIET`. Listed in `kImported`, so Direct is opt-in
and never auto-claimed.

- **Init:** two settings feature reports (report id 1, `ID_SET_SETTINGS_VALUES`), lizard mode off
  and then the IMU mode raw accel plus raw gyro, each exactly as SDL builds it
  (`buildTritonSettingsReport`). SDL sends the second when an app enables sensors; here both go out
  before the probe.
- **Lizard watchdog:** the firmware turns lizard mode back on unless it hears the switch again, so
  SDL re-sends it every 3 s while the pad is open. Here the pad's output worker does it
  (`refreshDue` and `nextRefreshInMs`, `OutputDevice::refresh`, `WorkerWake::sleepUnlessFor`): the
  worker sleeps no longer than the next refresh it owes, the poller never runs one, and a pad of any
  other family still sleeps until something is posted. A refresh is one attempt, stamped when tried,
  so a refused write waits for the next turn instead of spinning.
- **Input** (`decodeSteamTriton`): the three state reports (0x42, 0x45 and the timestamped 0x47)
  share the buttons, triggers and sticks, and the six IMU words sit at the same offsets in all three.
  Buttons follow SDL's mapping onto XUSB positions, triggers scale SDL's 0..32767 onto a byte, the
  sticks pass through (both Y axes are already up-positive), and gyro (x, z, -y) and accel
  (x, z, -y) follow SDL's sensor block onto the wire's +-2000 deg/s and +-4 g. Motion goes out
  only for a report whose IMU tick (the u32 at 30 of 0x42 and 0x45, the u16 at 32 of 0x47) differs
  from the last one published, SDL's own gate, so a report that repeats a sample is input and not
  motion; the last tick is per pad, in `ParserState`, and starts at 0 as SDL's does.
- **Battery** (`decodePadBatteryReport`): report 0x43 carries the charge state and the percent. It
  never publishes as input (that would flash a neutral pad on the wire) and feeds the Direct battery
  reading, so `BatteryRouting.route` weighs it against the phone's like any other pad's.
- **Dongle events:** reports 0x79 and 0x46 carry connect and disconnect (`checkWirelessEvent`); a
  disconnect publishes neutral and a connect re-runs the quiet init on the output worker, as for the
  2015 pad.
- **Rumble:** host `MSG_RUMBLE` becomes the haptic rumble output report 0x80 (`docs/rumble.md`),
  re-sent every 40 ms while it is non-zero, inside the firmware's ~50 ms motor timeout.
  `parserHasRumble` is true.
- **Release:** mirrors SDL, whose close does nothing: no teardown is written, and the firmware's
  watchdog restores lizard mode within about 3 s of the last refresh. `modelExpectsFrameworkGamepad`
  is false, so the path FSM settles Standard at once (`frameworkExpected`), as for the 2015 pad.

**Unverified and worth checking first:**
- The interface pick. It assumes the lizard keyboard and mouse declare the HID boot subclass, as item
  8 does for the 2015 pad; SDL accepts any interface of the wired pad and interfaces 2..5 of a
  dongle, and names nothing else about the layout.
- Whether the gamepad interface has an interrupt OUT endpoint. Rumble goes there when it does and as
  a SET_REPORT(Output) on the control pipe when it does not (`outputRouteFor`), which is how hidapi
  writes an output report; neither route has met the firmware.
- The BACK and START mapping: SDL maps MENU to BACK and VIEW to START, the opposite of what the names
  suggest on an Xbox pad, and this port follows SDL.
- IMU axis order and signs, SDL's and unverified here, as for items 2 and 8.
- That 3 s stays inside the firmware's watchdog for this pad (it is SDL's cadence, not a measured
  margin), and that a running rumble refreshed every 40 ms feels continuous. The worker's timed
  sleep takes a realtime deadline (`sem_timedwait` has no other clock everywhere this builds), so a
  wall-clock step backwards during a wait can delay one refresh by the size of the step.
- The battery percent's range. SDL passes it through; anything above 100 reads unknown here.
- The dongle event framing and the reconnect re-init.

**Known limitation, the dongle reaches one slot:** each paired controller is its own HID interface,
numbered 2..5. As with the 2015 dongle, `findInterruptInPair` claims one interface up front, the
first candidate, so only the first slot is ever reached (interface 2, if the dongle's other
interfaces rank lower). A pad paired to another slot, or a dongle whose first slot is empty, fails
`probeDecodable` and falls back to routed, which is safe but looks like "Direct failed". Reaching the
others means attaching each candidate in turn.

**Deliberately absent:**
- Bluetooth LE (28DE:1303). The pad speaks Valve's own protocol over GATT, not HID over GATT, so it
  never reaches the framework as a gamepad, and the Direct path is USB only.
- The two trackpads over `MSG_TOUCHPAD` (the wire carries one surface per slot), the four back
  paddles, QAM and the trigger and pad clicks (no XUSB button), the capacitive touches, and the
  other haptic reports (pulse, tone, sweep, script).
- Pausing the refresh while a dongle's pad is away. SDL stops writing until the pad comes back; here
  the lizard switch keeps going to the dongle, one refused transfer every 3 s.

**Where:** `app/src/main/cpp/usb_parsers.cpp` (`decodeSteamTriton`, `decodePadBatteryReport`,
`checkWirelessEvent`, `buildTritonSettingsReport`, `refreshDue`, `nextRefreshInMs`,
`outputRouteFor`, `runTritonQuietInit`, `runSettingsRefresh`, `runMergedRumble`),
`app/src/main/cpp/usb_output.{h,cpp}` (`OutputDevice::refresh`, `postReconnected`),
`app/src/main/cpp/worker_wake.h` (`sleepUnlessFor`), `app/src/main/cpp/usb_host.cpp` (`UsbOutput`,
`publishCompletedUrb`, `publishWirelessEvent`).

**Acceptance:** A wired Steam Controller (2026) picked as Direct streams sticks, triggers, buttons
and motion for minutes with no lizard keyboard or mouse input reaching the phone, rumbles for as long
as the game asks, shows its own battery on the card, and is a desktop mouse again a few seconds after
being switched back to Standard or unplugged.
