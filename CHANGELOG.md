# Changelog

What's new in each version of Dish, in plain language. Newest first.

Some updates need the matching version of the free Satellite app on your
computer. Those lines say "update Satellite too".

---

## [Unreleased]

### Added

- **A Steam Controller paired over Bluetooth is a controller.** Pair a Steam
  Controller, the 2015 model or the 2026 one, with your phone in Android's
  Bluetooth settings and Dish takes it over as it does on a cable or through
  its receiver: out of its keyboard-and-mouse mode, as a Direct controller with
  its own card, its trackpad pick and its back buttons. Motion works on both;
  rumble on the 2026 model (the 2015 model has none on any connection). Dish
  cannot read the controller's own battery over Bluetooth, so the computer is
  told the phone's. When the controller sleeps or Bluetooth goes off its card
  goes away, and it comes back when the controller wakes. On Android 12 and
  later this needs the Bluetooth permission; without it Dish says so and the
  connections screen asks for it. Not tried on a real Steam Controller yet.
- **Steam Controller trackpads, your pick per controller.** A Steam Controller
  in Direct mode now has a Trackpads row on its configure screen: Right stick
  (the 2015 model's usual role for its right trackpad), Touchpad (both pads on
  the emulated controller's touch surface, left half and right half), Mouse
  (the right pad moves the pointer, the left pad scrolls, right pad click is the
  left button and left pad click the right button) or Off. The 2015 model starts
  on Right stick and the 2026 model on Touchpad where the emulated controller
  has one, else Off; Mouse is always your choice. Whichever you pick, a trackpad
  or its click lands in exactly one place, never on the stick and the touch
  surface at once. Not tried on a real Steam Controller yet.
- **A controller's extra buttons reach the computer.** On a Satellite that
  speaks protocol 4 (update Satellite too), a Steam Controller in Direct mode
  sends its back buttons: the 2015 model's two grips, its trigger clicks and a
  click on its left trackpad while a finger rests on it; the 2026 model's four
  back paddles, its "..." button, its trackpad and trigger clicks and its
  stick and grip touches. A DualSense Edge sends its two back buttons and two
  Fn buttons, and a Switch Pro Controller its Capture button. What the
  computer makes of them is up to the controller type Dish emulates there,
  which the next updates extend. Not tried on a real Steam Controller or
  DualSense Edge yet.
- **The controller inspector shows a controller's extra buttons.** Its live
  input now names a back paddle, the Misc 1 button (a Switch Pro Controller's
  Capture, the new Steam Controller's "..."), a trackpad or trigger click and a
  stick or grip touch as you press them, next to the usual buttons. With a
  Steam Controller's trackpads set to Mouse, its right click and scroll wheel
  show under the touchpad. Before, the inspector showed only the buttons an
  Xbox controller has.
- **DualSense Edge as a controller type.** A Satellite that offers it (update
  Satellite too) can be asked to plug in a DualSense Edge: a DualSense whose
  two back buttons and two Fn buttons are the extra buttons a controller sends
  on protocol 4, so a Steam Controller's back buttons or a real Edge's reach the
  game. It sits last in the list and Dish never picks it on its own.
- **Room on the wire for a controller's extra buttons.** `[wire-coordinated]`
  Protocol 4. Dish now speaks Satellite protocol 4: on a connection that
  settled on it, every controller report carries a second word for the buttons
  an Xbox pad does not have (back paddles, the Steam Controller's "..." button,
  trackpad and trigger clicks, the capacitive touches), and a controller that
  sources them says so in its capabilities. This update adds the word and the
  claim; the controllers that fill it follow. Update Satellite too: an older
  Satellite keeps working at protocol 3 and sees exactly what it saw before.
- **The new Steam Controller (2026) can be tried in Direct mode.** On its
  cable, or on its Puck or other wireless adapter, it can now be switched to
  Direct: Dish turns off the controller's own keyboard-and-mouse mode and
  sends the computer its sticks, triggers, buttons, motion and battery, and
  plays the game's rumble on it. This follows SDL's support for the
  controller and has not been tried on one yet, so Dish never picks Direct
  for it on its own. Switched back to Standard, it is a keyboard and mouse
  again within a few seconds. Bluetooth is not supported.
- **A Steam Controller wireless adapter serves every controller paired to
  it.** In Direct mode, the original Steam Controller's dongle and the new
  controller's Puck and other adapter used to reach only the first of their
  four controller slots. Dish now listens on all four: each controller that
  is switched on shows up as a controller of its own, with its own card and
  its own binding, within half a second; one that is switched off leaves,
  and comes back to the same card and binding when it is switched on again.
  Direct stays one choice for the whole adapter. Two adapters of the same
  model plugged in together are still read as one. This follows SDL and the
  Linux driver and has not been tried on an adapter yet.

### Changed

- **Your computer is told the lower of the controller's battery and the
  phone's, for every controller.** A controller on a USB cable that reports
  its own charge, such as a DualShock 4, DualSense or Switch Pro, used to send
  only the phone's battery to Satellite and Moonlight, whatever the controller
  said. It now counts the same as a Bluetooth one: the computer hears
  whichever of the two is lower, so its warning is about whichever runs out
  first. A controller with no battery still sends the phone's, the on-screen
  pad still sends the phone's, and the controller card still shows the
  controller's own charge.

### Fixed

- **Two controllers of the same model each work in Direct mode.** Two
  DualSense pads on USB cables, two Steam Controller receivers or two of the
  new Steam Controller's adapters used to share one entry in Dish: only one of
  them was read, and unplugging either one stopped the one being read. Each now
  gets its own Direct claim and its own slots, unplugging one leaves the other
  running, and one that cannot switch to Direct leaves the other on it. Direct
  or Standard is still one choice for the model. Not tried with two identical
  controllers yet.
- **The 8BitDo Ultimate 2 Wireless works in X-input mode.** With its switch on
  X-input, many phones never saw it at all: their Android is older than the
  Linux driver that knows this controller. Dish now recognises it and reads it
  in Direct mode on its own, the way it already reads the Ultimate 2C.
- **The Rumble pill on a controller's card no longer says "on" when its rumble
  switch is off.** It now dims, the way the Motion pill does, while the switch
  is off.
- **The new Steam Controller's wireless adapters are read from the right
  part.** On the Puck and the other adapter, Dish now uses only the parts of
  the adapter that SDL and Steam read controllers from, instead of the first
  one that looked like a controller. This follows SDL and Steam and has not
  been tried on an adapter yet.
- **A Switch Pro Controller on a USB cable gets the wake-up it streams after.**
  Dish used to send the pad a handshake and then the command that keeps it
  reporting, and the pad answered the handshake and stayed silent unless the
  phone's own Nintendo driver had already woken it before Dish took over. Dish
  now sends what Linux and SDL send: the handshake, the switch to the pad's
  faster link speed, the handshake again and then the keep-reporting command.
  The 8BitDo adapters and the Nintendo 64 controller for Switch Online, which
  share the wake-up, get the same. That the pad needs the faster-link step was
  shown on a Pro Controller over macOS; the new wake-up has not yet been run on
  an Android device.
- **A Switch Pro Controller on a USB cable sends its motion.** Dish woke the
  pad but never switched its motion sensors on, so every motion reading it
  sent for the pad was zero unless the phone's own Nintendo driver had
  switched them on first. Dish now sends the switch-on Linux and SDL send,
  right after the wake-up. That the pad needs it, and that its accelerometer
  then reads gravity at rest, was shown on a Pro Controller over macOS; the
  step has not yet been run on an Android device.
- **The new Steam Controller's battery report is accepted at the length SDL
  accepts.** The controller's own battery message is 15 bytes; Dish asked for
  17, so a message of SDL's length would have been thrown away and the
  controller's charge never shown or sent. This follows SDL and has not been
  tried on a controller yet.
- **The new Steam Controller's motion is sent once per reading.** The
  controller stamps each motion reading with its own clock, and a report can
  carry a reading the last one already did. Dish now sends a reading to the
  computer only when that stamp has moved, as SDL does, instead of once per
  report. This follows SDL and has not been tried on a controller yet.

## [2.5.0] - 2026-11-02

Steadier input when several controllers, the on-screen pad and voice chat are
in use at once.

### Added

- **Every release on GitHub now carries a measurement of the input path.** Two
  files, `measurements-<version>.md` and `.json`, show how long Dish's input
  code takes to send each controller report, timed on GitHub's build machines
  rather than on a phone, and confirm that no controller, on-screen pad or
  incoming message waits on a lock. A release that fails that check is not
  published on GitHub.

### Changed

- **Controller input no longer waits its turn behind other controllers.** A
  press from a USB controller, a Bluetooth one or the on-screen pad used to
  queue for a moment behind whatever another controller or the microphone was
  sending. Each now goes straight out, in order, without waiting on the others.
- **Rumble and lights on a USB controller no longer hold up what your computer
  sends next.** Each USB controller now has a helper of its own that writes
  its rumble, light bar and LEDs, so a slow USB write never delays the next
  message from Satellite, and a newer rumble or color simply replaces one that
  has not been written yet.
- **Messages from Satellite are received on a dedicated, high-priority
  thread**, the same priority the controllers' own input already had.

## [2.4.1] - 2026-10-25

Controllers that Android also reads as a keyboard, and a Diagnostics list of
the input devices Dish leaves alone.

### Fixed

- **A controller that Android also reads as a full keyboard now shows up in
  Dish.** Some pads carry a keyboard alongside their buttons and sticks, and
  Android can then call the whole device an alphabetic keyboard. Dish used to
  leave every such device alone, so the pad never appeared and never reached
  the computer. It now goes by the gamepad and joystick inputs alone.

### Added

- **Diagnostics lists the input devices Dish does not treat as controllers**,
  under the controllers, with what Android reports for each one: its inputs,
  keyboard type, hardware ID and raw source bits. A pad in a mode Dish
  cannot read shows up there with the reason instead of nowhere.

## [2.4.0] - 2026-10-25

The Nintendo 64 controller for Switch Online, Dish on the 32-bit Fire tablets
with a build for the Amazon Appstore, the Guide button and the Xbox Elite
Series 2 and 8BitDo controllers on both paths, and fixes for Moonlight hosts,
controller settings and audio.

### Added

- **The Nintendo 64 controller for Switch Online works with Dish.** Over a
  USB-C cable, direct mode wakes it the way it wakes the Switch Pro
  Controller, with rumble, battery and player lights. Over Bluetooth, the
  buttons land in the right places on phones with and without Nintendo's
  kernel driver. On the computer, A and B stay A and B, Z and ZR are the
  triggers, L and R the shoulders, Start is Start, Home is the guide button,
  Capture is Back, and the four C buttons move the right stick, which is
  where N64 emulators look for them.
- **The native library now ships for 32-bit ARM**, so Dish installs on
  devices with no 64-bit side at all, such as the Amazon Fire HD 8 (2018 and
  2020), Fire 7 (2019) and Fire HD 10 (2019). Before, Google Play did not
  offer Dish on them and the GitHub APK refused to install.
- **The release now also builds an APK for the Amazon Appstore.** It is the
  same app, it leaves updates to the Appstore instead of checking GitHub, and
  it asks for no tips: no donate button, banner or Settings card.

### Fixed

- **Adding a Bluetooth PC on Android 11 and older lists your paired devices
  and offers a button that opens Bluetooth settings for a new one.** Those
  Android versions report nearby devices only to an app with the location
  permission, which Dish does not ask for, so the scan in the picker could
  never find anything.
- **The screen shown when Dish's native library cannot load no longer tells
  you to reinstall from the Play Store**, which was wrong for every build
  installed from somewhere else. It now says to reinstall Dish from where you
  got it.
- **A Moonlight host's rumble or motion request that overtakes an earlier
  packet is no longer lost.** It now waits for the packet it overtook, the way
  ENet's own clients do, instead of being dropped with it. The link also
  refuses a host answer meant for another connection, ignores stale packets
  from an earlier session on the same port, resends on ENet's own schedule,
  ends a session with the termination type Wolf's control protocol names while
  still reading the older one, and sends the keepalive in the form captured
  from a real client.
- **DualSense HD haptics play again.** The host's word that it carries the
  haptics lane was dropped on the way into the app and reset by every catalog
  read, so the lane was never opened and the host, told the pad plays haptics,
  never sent rumble in its place either. The lane now rides the same merge as
  the mic and the speaker and survives a catalog write.
- **The mic-mute bit stays off the Moonlight wire.** A DualSense claimed in
  Direct mode sets bit 0x0800 on every muted frame; a Moonlight host has no
  button there. The frame mask and the advertised buttons now leave it out.
- **The onboarding help says crash reports are on by default and can be turned
  off**, which is what the switch does; it said "opt-in".
- **A controller setting changed while the previous change was still on its
  way reaches the satellite as your final choice.** Dish sends a satellite its
  session and controller changes one at a time, re-reads what you chose once
  each answer lands, and records what the satellite acknowledged rather than
  what the app wanted at the time, so a toggle flipped and flipped back no
  longer leaves the satellite on the first choice while the app shows the
  second. Reconnecting right after a disconnect also waits for the old session
  to close first, since a satellite keeps its connection id across sessions.
- **Controller audio stays off once you leave the app.** Audio that finished
  setting up after Dish had stopped could start a microphone capture or open a
  speaker output anyway; a stopped engine now ignores it, and a microphone that
  restarts waits for its previous capture to end, so no stale samples are sent.
- **The Guide button reaches the PC from a controller on the Standard path.** It
  was never forwarded; it arrives as Guide on a satellite and Home on a
  Moonlight host.
- **An Xbox controller Android has no key layout for drives the right stick and
  the triggers correctly on the Standard path.** The Elite Series 2 over USB,
  among others, puts its triggers on the axes Android calls Z and RZ, which
  rest at their minimum, so the right stick sat pinned up-left and the triggers
  read empty. On a Microsoft pad that reports no trigger axis of its own, Dish
  now reads those two axes as the triggers and RX and RY as the right stick.
- **An Xbox One or Elite Series 2 controller's Guide button no longer sticks on
  Direct.** The pad retransmits that report until it is acknowledged; Dish now
  answers it the way the kernel driver does.
- **8BitDo pads in D-input mode send Heart as Guide, and L and R drive one
  trigger each.** On the Standard path the pad's analog trigger echoes, which
  are swapped over USB, are left alone and the digital L and R drive the
  triggers. On Direct the M30 and both 8BitDo USB wireless adapters are known
  models whose buttons decode in 8BitDo's order: Z and C are the shoulders,
  Select is Back and Start is Start.
- **The PlayStation Classic controller decodes on Direct**, with its d-pad read
  as a d-pad rather than a stick.
- **A Sony or Switch controller identity that sends nothing is not claimed
  Direct.** Those families report whether or not anything moved, so a quiet pad
  with their identity is an adapter in another mode; it stays on the Standard
  path instead of in a Direct slot that streams nothing. The Switch Pro
  handshake also goes out as the full 64-byte packets the 8BitDo USB Wireless
  Adapter 2 needs.
- **The known-controller table no longer lists the Elite Series 2's Bluetooth
  identities as USB models**, and the 8BitDo FC30 Pro, N30 Pro and the SN30
  Pro's Bluetooth identity, none of which speaks XInput, no longer claim a
  quiet pad as a dead Xbox 360 controller.

### Changed

- **Diagnostics names the two new per-controller quirks**, 8BitDo D-input and
  Xbox triggers on Z/RZ, beside the existing ones.

## [2.3.2] - 2026-10-13

Two fixes from crash reports: Moonlight hosts on some devices, and a freeze when a
controller changes.

### Fixed

- **Moonlight hosts answer again on devices where Dish crashed the moment it
  checked its pairing** (seen on the Shield TV and the Retroid Pocket 4 Pro).
  The client certificate now goes to the TLS handshake directly instead of
  through a keystore that could not hold its key.
- **A controller being plugged in, woken or changed no longer freezes the
  app.** Dish asked Android about the controller on the thread that draws the
  screen, and on one phone the answer took long enough to trigger "Dish isn't
  responding". Those lookups now run on their own thread.

## [2.3.1] - 2026-10-12

A new app icon.

### Changed

- **The dish faces its signal.** The dish glyph aimed up and to the left while
  the signal arcs sat at the upper right. The reflector now points at the
  signal in every state glyph and in the app mark, so the launcher icon, the
  themed icon, the notification icon and the Play listing's icon read the
  same way.
- Updated libraries under the hood.

## [2.3.0] - 2026-10-04

Controller fixes, mostly for the DualSense, and a smoother Moonlight setup.

### Added

- **Your controller stays paired to its Moonlight host.** Restart the app or
  reconnect and it picks up where it left off.
- **Diagnostics now says why a controller's mic or speaker is off**, so you can
  see at a glance whether it is Android, the host or the cable.

### Changed

- **If a game disappears from your Moonlight host, Dish tells you** instead of
  quietly launching whatever is first in the list.

### Fixed

- **DualSense and DualShock 4 mics work over USB on Android 10 to 15.** Dish
  was looking for the controller's audio under the wrong name. The speaker
  stays off on Android 14 and newer: Android hides it on purpose, so a
  plugged-in controller never takes over your tablet's sound.
- **DualSense rumble in USB Direct mode.** Dish now asks the controller for
  rumble the way the official drivers do, and uses the newer rumble mode on
  recent firmware.
- **Moonlight pairing is cleaner.** Cancel really cancels, Back cancels too,
  turning your phone mid-pairing no longer breaks it, and a host found twice
  is one host, not two.
- **Light bars behave on Standard and Bluetooth controllers.** They come back
  after Dish was in the background and never borrow another controller's
  colour. Update Satellite too.
- **Rumble off means trigger rumble off too**, and a Direct controller's
  trigger motors stop when the host stops asking.
- Smaller things: pairing keys live in Android's secure keystore, a saved host
  keeps its trust after an update, and input stays smooth on a busy phone.

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
