# Changelog

What's new in each version of Dish, in plain language. Newest first.

Some updates need the matching version of the free Satellite app on your
computer. Those lines say "update Satellite too".

---

## Unreleased

### Added

- Controller lights follow the game. On a USB-connected (Direct mode)
  DualShock 4 or DualSense, the light bar now shows the color the game picks;
  a DualSense's player lights and a Switch Pro's player LEDs light up too.
  Works over Satellite (update Satellite too) and over Moonlight hosts.
- The on-screen controller joins in: its skin draws the light bar color
  around the trackpad, shows the player lights, marks the triggers while a
  game shapes them (adaptive triggers), and buzzes the phone for trigger
  rumble. The setup and binding screens list every one of these per
  controller and destination, so you can see what will work before you bind.
- DualSense adaptive triggers, end to end: a game driving the virtual
  DualSense's trigger effects on the PC now shapes the real triggers of a
  USB-connected DualSense (update Satellite too).
- Trigger rumble on Xbox One / Series pads over Moonlight: the impulse
  motors in the triggers now fire when the host asks.
- The on-screen triggers are analog now, like the real thing: slide your
  finger along the trigger rail for a partial pull (the rail fills to show
  how far in you are), or tap the marked top zone for an instant full press.
  A divider line shows where the full zone starts. Pads emulating a type
  without analog triggers (Switch Pro) keep the plain press.
- Moonlight hosts now receive controller motion (when the game asks for it),
  the DualShock/DualSense touchpad — from the pad itself in Direct mode or
  from the phone screen — and battery levels, the same telemetry the
  Satellite path already carried.

## [1.1.4] - 2026-08-24

### Changed

- The APK on GitHub Releases is now also published under the stable name
  `dish.apk`, so
  `github.com/TinkerNorth/dish-android/releases/latest/download/dish.apk`
  always delivers the newest release. dish.tinkernorth.com links it, which
  makes the name a public API: do not rename or drop it. Nothing changes
  inside the app.

## [1.1.3] - 2026-08-18

### Changed

- The Google Play version of Dish no longer has the "Support Dish"
  screen, the heart button in the toolbar, or the donation banner.
  Google Play's Payments policy only permits in-app donations through
  Google Play Billing, which takes a cut of every donation and would
  mean building a payment system into an app that has never charged for
  anything. Removing the links was the better trade.
- The version you download from GitHub or tinkernorth.com keeps all of
  it, and nothing about it changed. If you would like to support Dish,
  GitHub Sponsors, Ko-fi, and Buy Me a Coffee are all still there, and
  dish.tinkernorth.com/donate lists every option.
- Everything else is identical in both versions: same controllers, same
  latency, same features, still free, ad-free, and analytics-free with
  nothing held back. Satellite does not need updating for this release.

---

## [1.1.2] - 2026-08-18

### Fixed

- The Xbox/Guide button on XInput-style wired USB controllers (Xbox 360
  and its many licensed clones, plus the Amazon Luna Controller) now
  works. Before this fix, pressing it did nothing.
- Plugging in a controller that is already connected over Bluetooth no
  longer leaves the cable doing nothing. The controller card now shows
  "USB available" with a "Use wired" button that walks you through
  switching to the cable; Dish never switches on its own, so charging
  while you keep playing over Bluetooth works exactly as before.

---

## [1.1.1] - 2026-08-17

### Fixed

- PDP wired Switch controllers (Faceoff, Faceoff Deluxe, Faceoff
  Deluxe+ Audio, Wired Fight Pad Pro, Rock Candy) now work the way the
  pad is labeled. Before this fix, A did nothing, the right bumper and
  Home were dead, and most other buttons landed on the wrong action.
  Now every button does what it says, ZL and ZR act as the triggers,
  and Home is the guide button, in both Standard and Direct mode.
- Switching a controller to Direct mode no longer fails while the pad
  sits untouched. Pads that stay silent until you press something
  (like the Amazon Luna Controller) used to switch over only if you
  were moving a stick at the same time.

### Added

- Steam Controller support over USB, wired or through its dongle
  (opt-in). Sticks, triggers, buttons, motion, and the right trackpad
  as a right stick. While Dish is using it, it stops doubling as a
  mouse and keyboard for the phone, and it is handed back exactly as
  it was. If it drops off the dongle, no input stays stuck, and it
  sets itself up again when it reconnects.
- The Amazon Luna Controller is fully supported over USB, verified on
  real hardware: every button, both sticks, both triggers, and rumble.

---

## [1.0.1] - 2026-07-25

The first public release.

- Turn a controller or your phone into a wireless gamepad for a PC,
  console, or set-top box.
- Two ways to connect: Wi-Fi with the free Satellite app, or Bluetooth
  with no extra software.
- Play with the on-screen pad, a USB controller, or a Bluetooth
  controller.
- Wide controller support, including Xbox, PlayStation, Switch Pro,
  and many third-party pads. Wired pads can run in Direct mode, where
  Dish reads them directly so extras like motion, the touchpad, and
  rumble work.
- Pick what the game should see: Xbox, DualShock 4, DualSense, or
  Switch Pro.
- Guided setup walks you through your first connection, and remembered
  setups reconnect on their own.
- Motion aim, touchpad, and rumble on the Wi-Fi path.
- Diagnostics screen to check sticks, buttons, motion, rumble, and
  connection quality.
- No ads and no analytics. Optional crash reporting, and you can turn
  it off in Settings.
- Free and open source (LGPL-3.0).

Earlier test builds (0.0.x) are only documented in the git history.

[1.1.4]: https://github.com/TinkerNorth/dish-android/releases/tag/1.1.4
[1.1.1]: https://github.com/TinkerNorth/dish-android/releases/tag/1.1.1
[1.0.1]: https://github.com/TinkerNorth/dish-android/releases/tag/1.0.1
