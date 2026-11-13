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
`80 02`, `80 04`. Dish now sends that order, then the report-mode, vibration-enable and IMU-enable
subcommands, to every model on `InitKind::SWITCH_PRO_HANDSHAKE` (the Pro, the 8BitDo adapters, the
N64 Online pad). The pad's need for the 3 Mbit step was shown on a Pro Controller over macOS
(dish-mac PR 101 carries the probe table); the new sequence has not yet been run on an Android
device. On a phone whose kernel driver (hid-nintendo) ran the full sequence before Dish claimed the
interface, the pad streamed either way, which is the likely reason the old sequence passed its
Android hardware checks.

**Context, IMU enable:** Until subcommand `0x40` with argument `0x01` is sent, every IMU word of the
Pro's 0x30 reports reads zero. Shown on a Pro over macOS through dish-mac's own reader: with the
report-mode subcommand alone, 282 motion samples at rest read six zeros; with the IMU enable sent
after it, the accelerometer reads about 1 g at rest (dish-mac PR 101 saw the zeros, and dish-mac
PR 109 sends the enable and carries the run). Dish now sends it as the last step of
`kSwitchInitSequence`, after the vibration enable, non-fatal as in SDL. It has not yet been run on
an Android device, and whether the Android pitch and yaw confirmation below relied on the kernel
driver having turned the IMU on first is not known.

**Context, IMU:** The Switch Pro IMU scaling is correct (`32767/28568` gyro, `32767/16384` accel),
and the axis order is now rotated onto the DS4 wire convention with pitch and yaw
hardware-confirmed. Roll's sign and the three accel signs are still unverified (the code notes
exactly this).

**Where:** `app/src/main/cpp/usb_parsers.cpp` (`kSwitchInitSequence`, `runSwitchProHandshake`;
`decodeSwitchProUsb`, IMU block).

**Task:** With a real Switch Pro on a phone whose kernel has no hid-nintendo, confirm the pad streams
from its plugged-in state through Dish's own sequence, with non-zero IMU words. Then, in Direct mode,
confirm gyro/accel axis directions against the satellite's motion expectation and flip signs /
reorder axes as needed.

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
exit path whose handle is live: a release to Standard, a Standard pick from a card, the app's exit.
A detach that follows a physical unplug writes nothing to the dead handle (`DeviceCtx::unplugged`,
set when the poller reaps `ENODEV`); an unplugged wired pad lost the volatile settings with its
power anyway. The restore loads the firmware's stand-alone defaults, not a snapshot of
the pre-claim state; that is deliberate and mirrors SDL's close sequence, and Steam re-pushes its
own configuration whenever the pad reconnects to it. Listed in `kImported`, so Direct is opt-in and
never auto-claimed.

Because the released pad re-enumerates as a keyboard and mouse, never as a framework gamepad, the
path FSM settles Standard immediately on release (`frameworkExpected` in `UsbPathMachine`) instead
of waiting for a re-enumeration that cannot come and stranding in RestoreStuck. The foreground
service also stays up while any Direct claim is held, so backgrounding the app does not leave the
process (and the pending restore) at the mercy of the low-memory killer.

Dongle connect/disconnect events (`ID_CONTROLLER_WIRELESS`, same endpoint as input) are classified
by `checkWirelessEvent`, read as SDL reads them: any payload of one byte or more, 0x01 disconnected,
0x02 connected, 0x03 newly paired (a connect). A disconnect publishes a neutral state, since a powering-off pad would
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
  seen a real dongle, and with them everything about the dongle's slots below.

**The dongle's four slots (implemented, needs hardware verification):** the wired pad enumerates
as mouse, keyboard, pad; the dongle enumerates as keyboard then four pad interfaces, one per paired
controller. A Direct claim of the dongle opens all four (`interfacesToClaim` and `receiverSlotsOf`
in `ReceiverSlots.kt`, `claimTargetsOf` in `UsbGamepadManager.kt`), each attached as a receiver
slot under a synthetic id of its own: no init and no probe at attach, one
`ID_DONGLE_GET_WIRELESS_STATE` feature report asking the slot what it holds
(`runReceiverStateRequest`, as SDL's `HIDAPI_DriverSteam_InitDevice` and `hid-steam`'s
`steam_get_conn_status` do), and from then on its poller follows the slot's link (`slotLinkStep`):
a connect event, or a state report from a slot that held nothing, marks the controller there and
posts the quiet init to the output worker; a disconnect marks it away, publishes neutral, stops the
worker's refreshes and drops the battery reading, which was that controller's (`controllerLeft`).
The JVM reads each slot's link every 500 ms (`isUsbPadLinked`,
`followSlotLinks` in `ReceiverSlotLinks.kt`): a slot whose controller is there is a pad of its own
on the list, with its own card and binding; an empty slot is no pad; a controller that comes back is
the same pad on the same slot, bound as it left. The model's Direct opt-in is one choice for all
four slots, switching any slot's card to Standard releases the whole dongle, and the teardown
restores stand-alone mode only on a slot whose controller is there (`controllerIsThere`). A dongle
takes four of the eight native USB entries (`MAX_USB_DEVICES`).

Nothing receiver-side has met a dongle, there is none here: the slot layout, the state request and
its answer, the event framing and the newly-paired value, the arrival on a missed connect, the
per-slot init on arrival, the restore on release and the 500 ms cadence are all unverified.

**Two dongles of one model:** each physical device runs its own path FSM, keyed by
`UsbUnitKey` (the model and a unit; `UsbUnits.kt`), and is told from its twin by its
`UsbDevice.deviceName`. A second dongle beside the first gets a claim, slots and pads of its own,
and unplugging one leaves the other's (`UsbSameModelDevicesTest`). The path preference stays the
model's, as on the desktops. Two limits remain, both because Android does not say which framework
`InputDevice` belongs to which `UsbDevice`: every device of a model follows the model's first live
framework card (`liveFrameworkFor`), so two pads of one model switched back to Standard together
settle on the same card, and the registry's card marks (the transition hold, needs-replug,
restore-stuck and the Direct failure shown on a card) are the model's. Unverified with two
identical devices.

The wired pad's interface pick also assumes the emulated keyboard and mouse declare the HID boot
subclass. `hid-steam` deliberately does not rely on that, distinguishing the real pad by its report
descriptor instead. If the assumption is wrong the config packets go to the wrong interface, stall,
and the claim falls back to routed after roughly a second. The dongle's slots are opened by
interface number, 1 to 4, whatever they declare.

**Extended buttons (protocol 4, `steamExtendedButtons`):** the two grips ride the INPUT word as
`leftPaddle1` and `rightPaddle1` (SDL's `STEAM_BUTTON_BACK_LEFT/RIGHT_MASK` are its two paddles),
the full-pull trigger bits as `leftTriggerClick` and `rightTriggerClick`, and a click on the left
pad while a finger rests on it as `leftPadClick`; with no finger that click stays the stick's L3.
The right pad's click stays R3 while the right pad is the right stick (SDL maps it to RIGHT_STICK
too), so no position or click lands in two wire fields. Which type renders which bit is the
satellite's (contract.md "Extended buttons"); unverified on hardware like the rest of the decoder.

**Trackpads (the per-slot pick, `gamepad::routeSteamTrackpads`):** the configure screen's
Trackpads row picks `stick` (the right pad as the right stick with its click as R3, the default),
`pad` (both pads on the type's touch surface, left half and right half, either click the surface
click), `mouse` (the right pad as the cursor, the left pad as the wheel and right button) or `off`
(clicks in the extended-buttons word). One pick covers both pads, and the router test pins that a
position or a click lands in exactly one wire field, so the double actuation the earlier text
warned about cannot happen. Unverified on hardware like the rest.

**Deliberately absent:**
- Rumble. The pad has no motors, only trackpad voice coils driven by `ID_TRIGGER_HAPTIC_PULSE`
  pulse trains; the simple rumble command is Steam Deck firmware only, and `hid-steam` gates force
  feedback on the Deck quirk. `parserHasRumble` is false, so nothing is advertised.
- Bluetooth. The pad speaks Valve's own BLE protocol, not HID over GATT, so it never reaches the
  framework as a gamepad.

**Where:** `app/src/main/cpp/usb_parsers.cpp` (`decodeSteamController`, `buildSteamConfigPacket`,
`checkWirelessEvent`, `modelExpectsFrameworkGamepad`, `runInit`, `runTeardown`, `slotLinkStep`,
`controllerIsThere`, `buildReceiverStateRequest`, `runReceiverStateRequest`),
`app/src/main/cpp/usb_host.cpp` (`attachDevice`, `attachReceiverSlot`, `followSlotLink`,
`pollLoop`, `shutdownLocked`, `isPadLinked`),
`app/src/main/java/.../source/usb/UsbPathMachine.kt` (`frameworkExpected`, `receiver`, `slotPads`),
`app/src/main/java/.../source/usb/ReceiverSlots.kt`, `ReceiverSlotLinks.kt`,
`app/src/main/java/.../source/usb/UsbGamepadManager.kt` (`claimTargetsOf`, `listenOnSlots`,
`followSlotLinksOf`, `releaseAllDirect`),
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
- The wired pad's interface pick. It assumes the lizard keyboard and mouse declare the HID boot
  subclass, as item 8 does for the 2015 pad; SDL accepts any interface of the wired pad and names
  nothing else about the layout. A dongle is opened on interfaces 2 to 5 by number, as SDL does,
  whatever they declare.
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
- The dongle event framing, the reconnect re-init and the slot following below.

**The dongles' four slots (implemented, needs hardware verification):** each paired controller is
its own HID interface, numbered 2..5, and a Direct claim of a Proteus or a Nereid opens all four as
receiver slots, as item 8 describes for the 2015 dongle: each slot is a pad of its own while a
controller is paired to it, listed within 500 ms of its arrival and unlisted on its disconnect. The
2026 dongles are asked nothing at attach (SDL asks nothing either): a slot's controller is there
once its 0x79/0x46 connect event or its first state report arrives, and away on its disconnect
event. While a slot's controller is away its worker owes no refresh (`FeedbackState.controllerAway`,
`refreshDue`, `nextRefreshInMs`), so the lizard switch no longer goes to an empty slot every 3 s,
and the rumble that was playing and the battery reading go with the controller that left. Two
Pucks, or two Nereids, plugged in together are two entries, as item 8 notes. Everything
receiver-side is unverified on hardware; there is no dongle here.

**Extended buttons (protocol 4, `tritonExtendedButtons`):** QAM rides the INPUT word as `misc1`,
R4/L4/R5/L5 as `rightPaddle1`/`leftPaddle1`/`rightPaddle2`/`leftPaddle2`, the two pad clicks and
the two trigger clicks as their own bits, and the stick and grip touches as the four capsense
bits, all in SDL's names from `SDL_hidapi_steam_triton.c`. The pad touch bits have no slot in the
word. Unverified on hardware like the rest of the decoder.

**Trackpads:** as item 8's pick, without `stick` (this pad has a real right stick): `pad` when the
emulated type has a touch surface, else `off`, and `mouse` by choice. The pads' pressure has no
field on the wire.

**Deliberately absent:**
- Bluetooth LE (28DE:1303). The pad speaks Valve's own protocol over GATT, not HID over GATT, so it
  never reaches the framework as a gamepad, and the Direct path is USB only.
- The other haptic reports (pulse, tone, sweep, script).

**Where:** `app/src/main/cpp/usb_parsers.cpp` (`decodeSteamTriton`, `decodePadBatteryReport`,
`checkWirelessEvent`, `buildTritonSettingsReport`, `refreshDue`, `nextRefreshInMs`,
`outputRouteFor`, `runTritonQuietInit`, `runSettingsRefresh`, `runMergedRumble`),
`app/src/main/cpp/usb_output.{h,cpp}` (`OutputDevice::refresh`, `OutputDevice::left`,
`postReconnected`, `postLeft`), `app/src/main/cpp/worker_wake.h` (`sleepUnlessFor`),
`app/src/main/cpp/usb_host.cpp` (`UsbOutput`, `publishCompletedUrb`, `publishWirelessEvent`,
`followSlotLink`), and the slot files item 8 lists.

**Acceptance:** A wired Steam Controller (2026) picked as Direct streams sticks, triggers, buttons
and motion for minutes with no lizard keyboard or mouse input reaching the phone, rumbles for as long
as the game asks, shows its own battery on the card, and is a desktop mouse again a few seconds after
being switched back to Standard or unplugged.
