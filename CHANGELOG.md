# Changelog

What's new in each version of Dish, in plain language. Newest first.

Some updates need the matching version of the free Satellite app on your
computer. Those lines say "update Satellite too".

---

## [Unreleased]

### Added

- **A controller goes back on its Moonlight host after a restart.** A pad bound
  to a Moonlight host is remembered by its own identity, with the host and the
  controller type, and bound again when it appears, after a restart or a
  reconnect, the way the Windows app reattaches. Unbinding it forgets that;
  unplugging it does not, and forgetting the host takes its bindings with it.

### Changed

- **A Moonlight app the host removed is named, not replaced.** Dish used to
  forget the pick and start whatever the host listed first. The pick stays,
  nothing starts, and the binding card names the app with the picker under it.

### Fixed

- **A DualSense or DualShock 4 plugged into an Android 10 to 15 device can
  lend its microphone.** Those releases list a controller's sound card as
  "USB-Audio - Wireless Controller" while the controller calls itself
  "Wireless Controller", and Dish accepted only the second spelling, so the
  Microphone and Speaker rows of the capability table read as off for every
  such pad. Dish now knows both spellings. The speaker still reads off on
  Android 14 and newer: Android hides a PlayStation controller's speaker on
  purpose, so that plugging one in does not take over the device's sound,
  and Dish cannot route to an output Android does not list.
- **Rumble on a DualSense in USB direct mode asks the pad the way its own
  driver does.** Dish set only the "compatible vibration" flag. The Linux
  driver and SDL also switch the pad from audio haptics to classic rumble in
  the same report and, from firmware feature version 2.21, use the revised
  rumble mode. Dish now reads the pad's firmware report once when it is
  claimed and writes the same flags.
- **The satellite pairing key is sealed in the Android Keystore**, as the
  Moonlight key already was. A key an older build stored in the clear is read
  as it is and sealed on that read.
- **Cancelling a Moonlight pairing cancels it.** A PIN typed after Cancel
  still paired the host, saved it and showed "Paired". Cancel now ends the
  pairing at once, even while Dish is still asking the host who it is, and
  records, shows and pairs nothing. A pairing the host never confirms in its
  last step fails and says which step, instead of being saved as paired, and
  that last step trusts only the certificate the earlier steps proved: the
  pinned certificate changes only once the pairing is recorded.
- **Forgetting a Moonlight host while Dish is still asking it about itself,
  or while its session is starting, leaves it forgotten.** A late answer
  used to verify the host again or write its record back. The same holds
  while a pairing runs: a PIN typed after the host was forgotten, or a host
  answering that it already trusts this phone, no longer brings it back.
- **The PIN dialog's Back cancels the pairing**, like its Cancel button,
  instead of leaving the pairing waiting for the PIN behind a closed
  dialog, and the dialog is closed with the screen and reopened by the
  rebuilt one.
- **A host saved by an older build keeps its trusted certificate** when its
  record is moved under its address on the first start, even where a scan
  had left a stray certificate at that address.
- **A Moonlight host is one host, however Dish found it.** A host found by a
  scan and the same host added by its address were filed under different
  ids, with two records and two pins for one machine, and a rebuilt machine
  behind a scanned host read as "trust lost" instead of "replaced". Every
  host is now filed under its address, as the Windows and Linux apps do,
  records an older build saved are folded together on the first start, and
  a rebuilt machine reads as replaced.
- **After "Pair again", the first session no longer writes the old identity
  back**, which made the host read as replaced again until Dish restarted.
- **Pairing from the hosts screen survives turning the phone.** The new,
  faster Cancel also fired when the screen was rebuilt for a rotation.
- **A Moonlight host that refuses a session without giving a reason** no
  longer leaves the card reading "refused the session:" with nothing after
  the colon.
- **An app removed on the Moonlight host stops being the pick.** Every
  launch was refused, and Retry launched the same app again, until you left
  and re-entered the screen. The next launch now starts the host's first
  app and the picker comes back. An app the host renamed under a new id
  counts as removed; a list Dish could not read leaves the pick alone.
- **Forgetting a Moonlight host forgets what it last said about itself**,
  so a new host at the same address cannot show the old one's diagnostics.
- **After you quit the host's app, Dish asks the host again once the quit
  has gone out**, not while it may still be on its way.
- **Light bars on Standard and Bluetooth pads.** Update Satellite too. The
  bar went dark after Dish came back from the background and stayed dark
  until the game changed colour; releasing one pad's bar could paint
  another pad's colour onto it; a pad bound to a different host could show
  the previous host's colour; the input inspector's colour test was kept as
  if the host had sent it; and a released bar could keep its light session
  open after the pad was gone. Each pad's last host colour now comes back
  when the pad is bound again under the same host, a released bar stays
  dark instead of taking a neighbour's colour, and Satellite re-sends the
  current colour when Dish reconnects.
- **A pad whose rumble is switched off gets no trigger rumble from a
  Moonlight host**, and a Direct pad's trigger motors stop when the host
  stops refreshing them, as the main motors already did.
- **The heartbeat and the motion-sensor threads run at the same priority as
  the other input senders**, so a busy phone cannot let one of them hold up
  a pad's input.

## [2.2.0] - 2026-09-21

### Added

- **The GitHub build tells you about new releases.** The APK from our
  releases page had no way to learn that a newer Dish existed. It now asks
  GitHub for the newest release while the app is on screen (about 15 seconds
  after opening, at most once an hour, then every four hours) and, when
  there is one, shows a notice on the main screen and a row in Settings that
  open the release page in your browser. Skip a version you do not want, or
  switch the check off in Settings; off means no request at all. The request
  carries nothing about you, and the Play build never makes it, because Play
  updates it. This is the same update check Dish for Windows and Linux run.
- **DualSense haptics on a USB DualSense.** Games like 007 First Light and
  God of War drive the DualSense's vibration as audio into its actuators and
  never touch the motors, so a controller streamed through Satellite stayed
  still in them. With a DualSense on USB whose sound output the phone opens at
  four channels, the app now plays that vibration straight into the
  controller, exactly as the game intended; for any other controller, or the
  on-screen pad, Satellite turns it into ordinary rumble instead. It rides the
  "Controller sound" switch and the host's own haptics switch, and shows as a
  Haptics row where it applies. Update Satellite too. The app now speaks
  protocol 3; older Satellites keep working as before.
- **The touchpad works without Direct.** A DualShock 4 or DualSense on
  Bluetooth, or on USB in Standard mode, used to give the game nothing from
  its touchpad: Android treats the pad's surface as a mouse. While such a
  controller is streaming and the app is on screen, the app now reads the
  surface itself (Android's pointer capture, Android 8 and up) and forwards
  both fingers and the click, exactly as it does in Direct mode; the pad's
  touchpad stops moving Android's cursor for as long as that lasts, and goes
  back to being a mouse when the app leaves the screen or the controller
  disconnects. It works over Satellite and over Moonlight hosts alike.
- **A Direct controller shows its own battery.** In Direct mode the app
  reads the DualShock 4, DualSense and Switch Pro's charge straight out of
  their input reports (the same bytes Linux's own drivers read), so the
  controller card shows the pad's charge and whether it is charging instead
  of the phone's. What Satellite is told stays the phone battery, the rule
  for any USB controller.
- **Controller sound and microphone without Direct.** A DualSense or
  DualShock 4 left on Android's own USB path now gets the same microphone,
  speaker and haptics routes as a Direct one: the pad's audio function is the
  system's on either path. Bluetooth pads are unchanged (they have none).
- The controller light bar follows the game over Bluetooth now, not just on a
  USB controller in Direct mode. On Android 12 or newer, a DualShock 4 or
  DualSense paired over Bluetooth shows the color the game picks, as long as
  your phone's controller driver exposes the light. It works over Satellite
  (update Satellite too) and over Moonlight hosts, the same as the USB light
  bar. When a controller stops streaming, its light bar turns off. A USB
  controller left in Standard mode still needs Direct mode for its light bar,
  because Android does not let an app write those lights over USB.

## [2.1.3] - 2026-09-15

### Fixed

- Changing your monthly supporter plan in the Google Play build works. Google
  Play refuses a time-prorated switch between the plans of one subscription,
  so 2.1.2 showed an error instead of the plan-change sheet; the switch now
  takes effect immediately and the new amount is charged from your next
  renewal.
- The support screen re-reads your subscription from Google Play every time
  it opens, shows it in its own card with a link to manage or cancel it, and
  marks your plan among the amounts by its plan id rather than its price.
- The donation banner no longer appears while you have an active monthly
  plan.

## [2.1.2] - 2026-09-15

### Fixed

- The support screen in the Google Play build now shows your monthly plan:
  a supporter panel names the amount you pay, your plan's button is marked,
  and picking another amount changes the plan, with Google Play prorating
  the difference. Before this fix the screen looked the same whether or
  not you were subscribed.

## [2.1.1] - 2026-09-14

### Fixed

- The support screen in the Google Play build no longer sits on
  "Contacting Google Play..." forever. 2.1.0 asked Google Play for the
  tips and the monthly plan in one request, which Google Play's billing
  library rejects, so the screen never got its prices; the two are now
  fetched separately.

## [2.1.0] - 2026-09-07

### Added

- The Google Play build can take tips again. Google Play's Payments policy
  requires payments inside a Play-distributed app to go through Google
  Play's billing system, so the donate screen in the Play build now sells
  one-time tips and a monthly supporter plan through Google Play instead of
  linking out. A tip unlocks nothing; Dish stays the same for everyone, and
  the monthly plan can be cancelled any time from Google Play. The GitHub
  build keeps its GitHub Sponsors, Ko-fi, and Buy Me a Coffee links.
- The heart button, the support pill, and the Settings support card are back
  in the Play build; they open the new screen. The billing client only
  connects when that screen opens, so streaming is untouched.

## [2.0.0] - 2026-09-06

Everything below ships as 2.0.0, the release where the whole Dish and
Satellite family moves to one shared version number. The headline since
1.1.4: your controller gains a microphone, a speaker and a working mute
light, and Dish can now stream from Moonlight hosts (Sunshine, Apollo,
Wolf) as well as Satellite.

### Added

- Your controller has a microphone now. A DualShock 4 v2 or DualSense
  plugged into a PC has its own microphone and its own speaker, and games
  and voice chat use them. Dish can give the emulated pad the same thing:
  switch Microphone on for a controller and the phone's microphone becomes
  the pad's microphone, so party chat on the PC hears you through the
  controller (update Satellite too, and turn Controller audio on there).
- It is off until you turn it on, per controller. Turning it on is what
  asks for microphone permission, and the switch only appears where the
  whole path can carry one: an emulated DualSense or DualShock 4 v2, on a
  Satellite with controller audio enabled. Bluetooth and Moonlight hosts do
  not offer it, because those protocols have no microphone channel.
- Mute means muted. The on-screen DualSense now has the mute button the
  real one has, under the PS button, and the mute button on a
  USB-connected DualSense works too; both toggle the same thing. While
  muted, Dish stops capturing rather than sending silence, so nothing
  leaves your phone at all, and the PC sees the pad's mute button held down
  the way it would on the real controller.
- You can always see, and silence, the microphone. While any controller has
  a live microphone, a small chip floats on every screen of the app: red
  means the mic is hot, grey and slashed means it is muted, and one tap
  mutes or unmutes everything at once. The streaming notification shows the
  same state with a Mute mic / Unmute mic button, so it works even from the
  notification shade with the app in the background.
- The on-screen mute button tells the truth. Its face (the slashed glyph
  and the amber wash) always shows what your mute actually is, and the ring
  around it shows what the game on the PC thinks; PC software that toggles
  its own mute can no longer make the button look stuck or lie about
  whether you are muted. Presses near the bottom edge of the screen also
  land on the mute button now instead of the PS button beneath it.
- Audio never touches storage and never leaves your network: it goes only
  to the PC you paired with, over the same encrypted connection as your
  controller input, one 20 ms slice at a time, and is discarded as soon as
  it is sent. It is never sent to us, and never included in crash reports.
- And the controller has a speaker now. Whatever a game or a chat app plays
  through the pad's own speaker on the PC comes out of your phone, in stereo,
  the moment it is played. Controller sound is on by default per controller;
  turning it off stops the PC sending it at all, rather than just muting it
  here.
- A DualShock 4 v2 or DualSense plugged in by USB (Direct mode) uses its own
  microphone and its own speaker or headset instead of the phone's, when
  Android hands us its audio. The Microphone and Controller sound rows appear
  for a plugged pad only while that is true: if the phone does not pick up the
  pad's audio, Dish says so instead of promising sound it cannot deliver, and
  the rows appear and disappear with the cable.
- The mute light works on a plugged-in DualSense too. A game that lights, or
  breathes, the pad's mute lamp now lights the real one, and the pad's own
  microphone is switched off behind it rather than left listening. The
  on-screen DualSense shows the same thing on its mute button.
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
  the DualShock/DualSense touchpad (from the pad itself in Direct mode, or
  from the phone screen) and battery levels, the same telemetry the
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

[2.0.0]: https://github.com/TinkerNorth/dish-android/releases/tag/2.0.0
[1.1.4]: https://github.com/TinkerNorth/dish-android/releases/tag/1.1.4
[1.1.1]: https://github.com/TinkerNorth/dish-android/releases/tag/1.1.1
[1.0.1]: https://github.com/TinkerNorth/dish-android/releases/tag/1.0.1
