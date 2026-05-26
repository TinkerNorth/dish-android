# Prompt: build the Play Store screenshot pipeline for Dish

Hand this file to a coding agent to scaffold the screenshot test infrastructure. It captures everything the agent needs in one place.

---

## Goal

Build the test infrastructure that produces all of Dish's Play Store screenshots automatically:

- Phone (8 captures, mostly portrait)
- 7-inch tablet (8 captures, landscape)
- 10-inch tablet (8 captures, landscape)
- Across six locales: en-US, bs, de-DE, es-ES, fr-FR, pt-BR

Output PNGs into the existing Fastlane Supply directory layout under `play/metadata/android/<locale>/images/<deviceType>Screenshots/`. Driven from a GitHub Actions workflow.

Total artifact count: 8 screens x 6 locales x 3 devices = 144 PNGs per full run.

## What's already in the repo (do not rebuild)

- AGP 9.2.1, Kotlin 2.3.21, Hilt DI throughout the app.
- Six in-app locales declared in `res/values-*/strings.xml` and `app/src/main/res/xml/locales_config.xml`.
- `play/metadata/android/{en-US,bs,de-DE,es-ES,fr-FR,pt-BR}/` already contains title, short_description, full_description, and changelogs.
- `:baselineprofile` Gradle Managed Device module is in place. A `pixel6Api34` `ManagedVirtualDevice` is configured in `baselineprofile/build.gradle.kts`. Mirror that pattern.
- `release.yml` already enables KVM and uses GMD. Reuse that pattern for the screenshots workflow.

## Hard rules

- **No em-dashes or en-dashes** in any text you generate. Replace with periods, commas, colons, parentheses, or "to" for number ranges. Compound hyphens (Wi-Fi, Bluetooth-host, sw600dp) are fine.
- **Minimal new comments**. Add a comment only when a future reader could not reconstruct the WHY from the code itself.
- **No new third-party services**. Everything runs on Google's free Maven, GitHub-provided runners, and Fastlane.
- **Deterministic output**. Re-running on the same commit must produce byte-identical PNGs given the same emulator image. Status bar time, battery, fake data, and font rendering must all be pinned.

## Deliverables

### 1. Test source set

```
app/src/androidTest/java/com/tinkernorth/dish/screenshots/
  DishScreenshots.kt              one @Test per screen
  fixtures/
    Fixtures.kt                   five scenarios as data classes
    FakeConnectionStore.kt        in-memory impl of the real ConnectionStore
    FakeMdnsDiscovery.kt          in-memory impl of the discovery layer
    FakeSlotRepository.kt         (and so on for any other data sources)
    Clock.kt                      fixed-instant clock for deterministic time
  di/
    ScreenshotStoreModule.kt      @TestInstallIn module replacing production data layer
```

### 2. Screengrab configuration

- `Screengrabfile` at repo root with the six locales and the Fastlane output directory.
- `fastlane/Pluginfile` if a plugin is needed.
- `androidTestImplementation("tools.fastlane:screengrab:2.1.1")` and matching version entries in `gradle/libs.versions.toml`.

### 3. GMD device profiles

Add three managed devices to `app/build.gradle.kts` (or split into a `:screenshots` module if cleaner). See "Device resolutions" below for exact sizes required by Play.

### 4. GitHub Actions workflow

`.github/workflows/screenshots.yml` triggered via `workflow_dispatch`. Matrix over the three device profiles. Uploads PNGs as a workflow artifact and (preferred) commits them back to a `screenshots/<run-id>` branch with a PR opened automatically.

### 5. Pipeline docs

A short section appended to `play/README.md` explaining how to run the pipeline locally and what gets produced. Do not create yet another README at repo root.

## Scenarios (concrete seed data)

These five fixtures power every screenshot. Values are intentional. Names and battery percentages must stay constant across runs so the PNGs are diffable.

### `FirstRun`

Used for: Welcome flow, Setup wizard, Help screens.

```
satellites: []
btHosts: []
slots: []
onboardingComplete: false
```

### `Discovery`

Used for: Connections screen during scanning.

```
satellites:
  - { name: "Gaming PC", ip: "192.168.1.50", paired: false, discoveredVia: mdns }
  - { name: "Office PC", ip: "192.168.1.52", paired: false, discoveredVia: mdns }
btHosts: []
slots: []
scanState: scanning
```

### `OneSatellite`

Used for: quiet dashboard, single-controller close-up.

```
satellites:
  - { name: "Gaming PC", ip: "192.168.1.50", paired: true }
btHosts: []
slots:
  - { id: 1, name: "Player 1", boundTo: "Gaming PC", controller: virtual, battery: 91 }
```

### `PowerUser`

Used for: hero dashboard, Settings, slot detail.

```
satellites:
  - { name: "Gaming PC",      ip: "192.168.1.50", paired: true }
  - { name: "Living Room PC", ip: "192.168.1.51", paired: true }
  - { name: "Office PC",      ip: "192.168.1.52", paired: true }
btHosts:
  - { name: "Steam Deck", mac: "AA:BB:CC:DD:EE:FF", remembered: true }
slots:
  - { id: 1, name: "Player 1", boundTo: "Gaming PC",      controller: "Xbox Wireless", battery: 87  }
  - { id: 2, name: "Player 2", boundTo: "Gaming PC",      controller: "DualSense",     battery: 62  }
  - { id: 3, name: "Player 3", boundTo: "Living Room PC", controller: "8BitDo Pro 2",  battery: 100, charging: true }
  - { id: 4, name: "Player 4", boundTo: "Office PC",      controller: virtual,         battery: null }
```

### `InGame`

Used for: on-screen gamepad, touchpad overlay, low-power overlay.

```
inherits: OneSatellite
session:
  active: true
  motionEnabled: true
  lastInputAtMs: <fixed instant, e.g. 2026-05-26T09:41:30Z>
  errorState: none
  phoneBattery: 76
```

## Screen list

### Phone (8 captures)

| # | Filename                | Orientation | Scenario     | What it sells |
|---|-------------------------|-------------|--------------|---------------|
| 1 | `01_dashboard.png`      | portrait    | PowerUser    | Hero shot: 3 satellites, 4 controller slots populated. |
| 2 | `02_welcome.png`        | portrait    | FirstRun     | Welcome step 2 ("How it works") with the Wi-Fi-to-Satellite illustration. |
| 3 | `03_setup_wizard.png`   | portrait    | FirstRun     | Setup wizard step 1 showing the Wi-Fi vs Bluetooth choice. |
| 4 | `04_connections.png`    | portrait    | Discovery    | Connections screen with discovered satellites in the list. |
| 5 | `05_slot_detail.png`    | portrait    | PowerUser    | Controller card expanded, per-slot binding with controller type and battery. |
| 6 | `06_settings.png`       | portrait    | PowerUser    | Settings with the "Share crash reports" toggle and version info visible. |
| 7 | `07_help.png`           | portrait    | FirstRun     | Help & FAQ screen showing the topical section headers. |
| 8 | `08_gamepad.png`        | LANDSCAPE   | InGame       | On-screen gamepad in action. |

### 7-inch tablet (8 captures, all landscape)

| # | Filename                | Scenario     | What it sells |
|---|-------------------------|--------------|---------------|
| 1 | `01_dashboard.png`      | PowerUser    | sw600dp dashboard layout. |
| 2 | `02_welcome.png`        | FirstRun     | sw600dp welcome layout. |
| 3 | `03_setup_wizard.png`   | FirstRun     | sw600dp setup wizard layout. |
| 4 | `04_connections.png`    | Discovery    | sw600dp connections layout. |
| 5 | `05_gamepad.png`        | InGame       | On-screen gamepad. |
| 6 | `06_touchpad.png`       | InGame       | Touchpad overlay. |
| 7 | `07_settings.png`       | PowerUser    | sw600dp settings layout. |
| 8 | `08_help.png`           | FirstRun     | sw600dp Help & FAQ. |

### 10-inch tablet (8 captures, all landscape)

Same screen list as 7-inch. The sw600dp layouts are reused; the difference is canvas size and PPI.

### Skip

- **Google Play Games on PC**: skip. Dish is not a game and is not eligible for the Games-on-PC program.
- **Chromebook screenshots**: optional, omit for v1.0. Play falls back to the 10-inch tablet captures on Chromebook listings. Add later if you want a tier-1 finish.

## Device resolutions (matching Play specs)

Play specs:

| Form factor | Spec |
|---|---|
| Phone           | 320 to 3,840 px per side, 16:9 or 9:16, PNG or JPEG, max 8 MB each, 2 to 8 captures. |
| 7-inch tablet   | 320 to 3,840 px per side, 16:9 or 9:16, PNG or JPEG, max 8 MB each, up to 8 captures. |
| 10-inch tablet  | 1,080 to 7,680 px per side, 16:9 or 9:16, PNG or JPEG, max 8 MB each, up to 8 captures. |
| Chromebook (skip) | 1,080 to 7,680 px per side, 16:9 or 9:16, PNG or JPEG, max 8 MB each, 4 to 8 captures. |
| Games on PC (skip) | 720 to 7,680 px per side, 16:9 or 9:16, PNG or JPEG, max 15 MB each, 4 to 8 captures. |

GMD recommendations:

- **Phone**: 1080x1920 (true 9:16). Stock `Pixel 7` defaults to 1080x2400 (20:9), which Play rejects. Configure a custom resolution OR post-crop in the workflow with `imagemagick convert <in.png> -resize 1080x1920^ -gravity center -extent 1080x1920 <out.png>`.
- **7-inch tablet**: 1080x1920 (9:16) portrait or 1920x1080 (16:9) landscape. Stock `Nexus 7` profile is close (1200x1920) and needs a crop pass.
- **10-inch tablet**: 1440x2560 (9:16) portrait or 2560x1440 (16:9) landscape. Both sides comfortably in the 1080 to 7680 range.

If AGP 9's GMD DSL does not yet expose arbitrary resolutions, fall back to capturing at the device's native size and post-cropping in the workflow before saving to the Fastlane folder.

## Status-bar and clock cleanup

In the test's `@Before`:

1. Call `CleanStatusBar.enableWithDefaults()` from Screengrab. This forces the system clock display to 09:41, full battery, full Wi-Fi, no notifications.
2. Inject a `Clock` provider via Hilt and freeze it to `2026-05-26T09:41:30Z` (or any fixed instant) in `ScreenshotStoreModule`. This pins any in-app timestamps the dashboard or notifications might render.
3. Disable animations: `adb shell settings put global window_animation_scale 0`, `transition_animation_scale 0`, `animator_duration_scale 0` in the workflow before the test runs.

## Sample code starting points

### `ScreenshotStoreModule.kt`

```kotlin
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [ConnectionStoreModule::class, DiscoveryModule::class, ClockModule::class]
)
object ScreenshotStoreModule {
    @Provides @Singleton
    fun provideConnectionStore(): ConnectionStore = FakeConnectionStore()

    @Provides @Singleton
    fun provideMdnsDiscovery(): MdnsDiscovery = FakeMdnsDiscovery()

    @Provides @Singleton
    fun provideClock(): Clock = FixedClock(instant = "2026-05-26T09:41:30Z")
}
```

### `DishScreenshots.kt` skeleton

```kotlin
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class DishScreenshots {
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val localeRule = LocaleTestRule()

    @Inject lateinit var store: ConnectionStore

    @Before fun setUp() {
        hiltRule.inject()
        CleanStatusBar.enableWithDefaults()
    }

    @Test fun phone_01_dashboard() {
        (store as FakeConnectionStore).seed(Fixtures.PowerUser)
        ActivityScenario.launch(MainActivity::class.java).use {
            Espresso.onIdle()
            Screengrab.screenshot("01_dashboard")
        }
    }

    // one @Test per screen, named <device>_<index>_<screen>
}
```

### `Screengrabfile` skeleton

```ruby
locales(['en-US', 'bs', 'de-DE', 'es-ES', 'fr-FR', 'pt-BR'])
app_package_name('com.tinkernorth.dish')
use_tests_in_packages(['com.tinkernorth.dish.screenshots'])
output_directory('play/metadata/android')
clear_previous_screenshots(true)
exit_on_test_failure(true)
```

## Implementation phases

Tackle in this order. Do not skip ahead. If anything blocks during a phase, surface it before moving on.

1. **One screenshot, one locale, one device.** Get `phone_01_dashboard` rendering in `en-US` on a single phone GMD. Validates the entire toolchain.
2. **Remaining phone screens.** Add the other 7 `@Test` methods for the phone. Still en-US only.
3. **Locale loop.** Configure Screengrab and re-run. Expect 8 x 6 = 48 PNGs in `play/metadata/android/<locale>/images/phoneScreenshots/`.
4. **Tablet devices.** Add the 7-inch and 10-inch GMDs, run again. Expect 144 PNGs total.
5. **Workflow.** Wire `screenshots.yml`. Run via `workflow_dispatch`. Confirm the artifact archive contains 144 PNGs and the PR open step works.
6. **Status-bar polish + animation kill.** Apply the cleanup steps above and re-run. Compare before-and-after to verify consistency.

## Acceptance criteria

- `bundle exec fastlane screengrab` runs locally to completion in under 25 minutes on a developer workstation.
- All PNGs land in the correct Fastlane Supply paths.
- Every PNG passes the Play Console upload check (aspect ratio plus dimensions).
- The GitHub Actions workflow produces an artifact containing 144 PNGs.
- Re-running on the same commit produces identical PNGs (modulo timestamps if any leaked through).
- Zero em-dashes or en-dashes in any new file.
- Zero new comments in new files unless they document a non-obvious WHY.

## Out of scope (defer)

- Headline overlays per locale (the "Wireless gamepad for PC" banner across the top of each screenshot). Build the raw capture pipeline first; overlay work is a separate pass using Figma or imagemagick that consumes the PNGs this pipeline produces.
- Chromebook and Games-on-PC captures.
- Animated GIF or video captures. Play uses still PNGs for the screenshot field; video uses a separate YouTube URL.
- Auto-upload to Play Console. Fastlane Supply can do this with `bundle exec fastlane supply --metadata_path play/metadata`, but the API key + service account setup is separate from this task.
