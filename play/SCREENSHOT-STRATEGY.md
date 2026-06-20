# Screenshot strategy

This file describes how every PNG currently sitting in `metadata/android/<locale>/images/` was produced, what data it depicts, and how to recreate the set. It is the source of truth for the screenshot pipeline so the captures stay reproducible across model edits, emulator changes, and future locale additions.

> Status: the screenshots currently committed were captured before the guided Setup flow and the dashboard card rework, so some show screens that no longer exist (the old welcome and setup-wizard screens). They are stale and must be re-captured before submission. The pipeline below is still the procedure to use, and the screen catalogue has been updated to the current screens.

## Output layout

162 screenshot PNGs: 9 screens times 6 locales times 3 form factors. The feature graphics live alongside them (see "Feature graphics" below).

```
play/metadata/android/<locale>/images/
  phoneScreenshots/        1080x1920 portrait, 1920x1080 landscape (08, 09)
  sevenInchScreenshots/    1920x1080 landscape
  tenInchScreenshots/      2560x1440 landscape
```

Locales: `en-US`, `bs`, `de-DE`, `es-ES`, `fr-FR`, `pt-BR`. Mirrors the in-app locale set declared in `app/src/main/res/xml/locales_config.xml`.

Aspect ratios are Play Console compliant: 9:16 for portrait phone shots, 16:9 for everything else.

## Screen catalogue

| # | Filename | Activity | Fixture | What it sells |
|---|---|---|---|---|
| 1 | `01_dashboard.png` | `MainActivity` | `BasicHero` | Hero: 1 of 2 satellites online, virtual + physical controller both routed to Gaming PC, "STREAMING KEEP APP OPEN" pill, streamingSlotCount=2. |
| 2 | `02_setup_input.png` | `SetupInputActivity` | `FirstRun` | Guided setup, "How do you want to play?": wired, Bluetooth, or on-screen. |
| 3 | `03_setup_connection.png` | `SetupConnectionActivity` | `FirstRun` | Guided setup, "How does input reach your PC?": a satellite over Wi-Fi vs a Bluetooth host. |
| 4 | `04_connections.png` | `ConnectionsActivity` | `MixedConnections` | Two satellites and two Bluetooth hosts, mixed live/saved. |
| 5 | `05_slot_detail.png` | `MainActivity` | `BasicHero` | Dashboard scrolled to expanded Wired Controller picker. |
| 6 | `06_settings.png` | `SettingsActivity` | `BasicHero` | Settings sections (Setup, Appearance, Diagnostics, About). |
| 7 | `07_help.png` | `HelpActivity` | `FirstRun` | Help and FAQ with topical sections. |
| 8 | `08_gamepad.png` | `GamepadOverlayActivity` | `InGame` | On-screen gamepad, landscape, connected to Gaming PC. |
| 9 | `09_touchpad.png` | `TouchpadOverlayActivity` | `InGame` | Touchpad overlay, landscape, connected to Gaming PC. |

## Fixtures

The instrumented test seeds these data classes into a set of `@TestInstallIn`-replaced Hilt bindings before launching each Activity. Reproduce by copying these values verbatim. See "Architecture seams" below for the bindings the test relies on. In the fixtures, `onboardingComplete` maps to the store's `welcomeCompleted` flag, seeded through `markWelcomeCompleted`.

### `FirstRun`

```
onboardingComplete = false
darkTheme          = true
satellites         = []
btHosts            = []
slots              = []
```

### `Discovery`

```
onboardingComplete = true
satellites = [
  { id: "gaming-pc", name: "Gaming PC", ip: "192.168.1.50", paired: false },
  { id: "office-pc", name: "Office PC", ip: "192.168.1.52", paired: false },
]
btHosts = []
slots   = []
```

### `OneSatellite`

```
onboardingComplete = true
satellites = [{ id: "gaming-pc", name: "Gaming PC", ip: "192.168.1.50" }]
btHosts    = []
slots = [
  { slotId: "virtual", playerName: "Player 1", boundConnectionId: "gaming-pc",
    isVirtual: true, batteryPercent: 91 },
]
```

### `InGame`

`OneSatellite` plus `darkTheme = true` and `streamingSlotCount = 1`. Used for the gamepad and touchpad overlays.

### `PowerUser`

```
onboardingComplete = true
satellites = [
  { id: "gaming-pc",      name: "Gaming PC",      ip: "192.168.1.50" },
  { id: "living-room-pc", name: "Living Room PC", ip: "192.168.1.51" },
  { id: "office-pc",      name: "Office PC",      ip: "192.168.1.52" },
]
btHosts = [{ id: "steam-deck", name: "Steam Deck", mac: "AA:BB:CC:DD:EE:FF" }]
slots = [
  { slotId: "slot-1",  playerName: "Player 1", boundConnectionId: "gaming-pc",      controllerType: 0, batteryPercent: 87 },
  { slotId: "slot-2",  playerName: "Player 2", boundConnectionId: "gaming-pc",      controllerType: 1, batteryPercent: 62 },
  { slotId: "slot-3",  playerName: "Player 3", boundConnectionId: "living-room-pc", controllerType: 0, batteryPercent: 100, charging: true },
  { slotId: "virtual", playerName: "Player 4", boundConnectionId: "office-pc",      isVirtual: true,   batteryPercent: null },
]
```

### `MixedConnections`

Used for `04_connections`.

```
onboardingComplete = true
darkTheme          = true
satellites = [
  { id: "gaming-pc",      name: "Gaming PC",      ip: "192.168.1.50", live: true  },
  { id: "living-room-pc", name: "Living Room PC", ip: "192.168.1.51", live: false },
]
btHosts = [
  { id: "steam-deck", name: "Steam Deck",  mac: "AA:BB:CC:DD:EE:FF", profileName: "Xbox" },
  { id: "rog-ally",   name: "ROG Ally X",  mac: "11:22:33:44:55:66", profileName: "Xbox" },
]
slots = []
```

### `BasicHero`

Used for `01_dashboard`, `05_slot_detail`, `06_settings`.

```
onboardingComplete  = true
darkTheme           = true
streamingSlotCount  = 2
satellites = [
  { id: "gaming-pc", name: "Gaming PC", ip: "192.168.1.50", live: true,  touchpadMode: "ds4" },
  { id: "office-pc", name: "Office PC", ip: "192.168.1.52", live: false },
]
btHosts = []
slots = [
  { slotId: "virtual", playerName: "Player 1",         boundConnectionId: "gaming-pc",
    isVirtual: true,   batteryPercent: 91 },
  { slotId: "1001",    playerName: "Wired Controller", boundConnectionId: "gaming-pc",
    controllerType: 1, batteryPercent: 78, motionEnabled: true },
]
physicalGamepads = [
  { deviceId: 1001, name: "Wired Controller", hasGyro: true },
]
```

Battery percentages, controller types, IP addresses, and slot ids are intentional and must be reproduced exactly so re-runs are byte-comparable.

## Architecture seams

The test cannot drive these screens by mutating production `@Singleton` classes from the outside because most relevant state is in private `MutableStateFlow`s. The pipeline introduces interface seams in the production code and a parallel `@TestInstallIn` module that swaps in fakes:

| Interface | Production impl | Test fake | Drives |
|---|---|---|---|
| `MainUiStateProvider` | `DefaultMainUiStateProvider` | `FakeMainUiStateProvider` | `MainActivity` dashboard state |
| `WakeStateSource` | `WakeStateController` | `FakeWakeStateSource` | Streaming pill, screen-on flag |
| `ConnectionsSource` | `ConnectionCoordinator` (implements directly) | `FakeConnectionsSource` | `ConnectionsActivity` list, overlay connection chip |

Each interface exposes only the read-only `StateFlow` surface the consumer needs. Production code mutates through the concrete classes; the test pushes literal `MainUiState` / `ConnectionSummary` values into the fakes.

The `OnboardingPreferenceStore` and `ThemePreferenceStore` are seeded through their existing public API (`markWelcomeCompleted`, `setMode`). The real `ConnectionStore` is also seeded, because `ConnectionsActivity` renders the saved-host list directly from `store.remembered()` even though `hub.bindings` comes through the fake.

## Emulator setup

These commands run once per AVD before the first test invocation. Without them the captures get spoiled by status-bar artefacts or wrong layout selection.

```sh
adb -s <serial> shell settings put global window_animation_scale     0
adb -s <serial> shell settings put global transition_animation_scale 0
adb -s <serial> shell settings put global animator_duration_scale    0

# Suppress the Android 13+ "Viewing full screen / Got it" snackbar that overlays
# the gamepad and touchpad shots the first time an Activity goes immersive.
adb -s <serial> shell settings put secure immersive_mode_confirmations confirmed
```

For the 7-inch tablet form factor (no native Pixel 7-inch AVD ships), boot the existing `Pixel_Tablet` AVD with a forced 1920x1080 skin and override the density so `sw720dp` layouts get picked:

```sh
emulator -avd Pixel_Tablet -port 5558 -skin 1920x1080 -no-snapshot-save -gpu swiftshader_indirect
adb -s emulator-5558 shell wm density 240
```

## Per-locale invocation

Locale switching uses the platform Per-App Language CLI rather than `AppCompatDelegate.setApplicationLocales`, because `MainActivity` extends `GameActivity` (not `AppCompatActivity`) and does not auto-recreate on the AppCompat path:

```sh
adb -s <serial> shell cmd locale set-app-locales com.tinkernorth.dish --locales <tag>
adb -s <serial> shell am force-stop com.tinkernorth.dish
adb -s <serial> shell am force-stop com.tinkernorth.dish.test
adb -s <serial> shell am instrument -w \
  -e testLocale <tag> \
  -e class 'com.tinkernorth.dish.screenshots.DishScreenshots#<test>' \
  com.tinkernorth.dish.test/com.tinkernorth.dish.screenshots.DishHiltTestRunner
```

The test reads `testLocale` only for the mirror output directory name (`/sdcard/Android/data/com.tinkernorth.dish/files/screengrab/<tag>/<name>.png`); the actual locale change is the `cmd locale` call above.

## Post-crop

Captures off the device are at native AVD resolution:

| Device | Native | Cropped to |
|---|---|---|
| Pixel 9 phone (portrait) | 1080x2424 | 1080x1920 |
| Pixel 9 phone (landscape overlay)  | 2424x1080 | 1920x1080 |
| Pixel Tablet at 1920x1080 (7-inch sim) | 1920x1080 | no crop |
| Pixel Tablet native (10-inch)        | 2560x1600 | 2560x1440 |

The center-crop script (`scripts/crop_screenshots.ps1` in the source tree) does this in place per file. It is idempotent.

## Feature graphics

`metadata/android/<locale>/images/featureGraphic/<filename>.png` holds the active feature graphic per locale. Bosnian (`bs`) has no feature graphic in this commit and needs to be added before submission. Fastlane Supply expects a single `featureGraphic.png` rather than a subdirectory, so copy or rename the active file at submission time:

```sh
cp play/metadata/android/<locale>/images/featureGraphic/<active>.png \
   play/metadata/android/<locale>/images/featureGraphic.png
```

## Known caveats

1. Status-bar clock reads the emulator's real time rather than the standard 09:41 demo time. Screengrab's `CleanStatusBar` is a no-op on API 33+ because the SystemUI demo-mode broadcast is gated. Fix would be either an emulator-side `setprop` time freeze or a custom SystemUI demo writer.
2. The gamepad overlay's motion indicator color reflects whatever the real `CapabilityComposer` emits, since that source has not been swapped out. The indicator will read as off / disabled even when `BasicHero` declares gyro enabled on the wired slot. Extracting a capability source interface analogous to the three above would fix it.
3. The 7-inch form factor is simulated on the 10-inch AVD via a skin override and density override rather than a true 7-inch device profile. A real `avdmanager` profile would be more faithful but requires installed cmdline-tools, which the original capture host did not have.
4. `ConnectionsActivity` shows BT and satellite entries as "Saved" rather than "Connected" because the fake `ConnectionsSource` is populated with non-live summaries in `MixedConnections`. Flip `live: true` on the desired entries if you want green pills there.
