# Changelog

What's new in each version of Dish, in plain language. Newest first.

Some updates need the matching version of the free Satellite app on your
computer. Those lines say "update Satellite too".

---

## [Unreleased]

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

[Unreleased]: https://github.com/TinkerNorth/dish-android/compare/1.0.1...HEAD
[1.0.1]: https://github.com/TinkerNorth/dish-android/releases/tag/1.0.1
