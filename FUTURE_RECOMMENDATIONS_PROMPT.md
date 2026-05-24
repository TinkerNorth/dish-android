# Dish Android — Future Recommendations Prompt

The remaining backlog from the post-Phase-4 design-system review and the
senior Android + design-system assessment. Items are organised by tier
(quick wins → strategic bets) so a contributor can pick the scope that
fits the available time.

Every item is self-contained — no dependencies between items unless
called out. Each names its files, scope, validation, and success
criteria. None require touching the Phase-1–4 token foundation; they
build on it.

The design-system foundation (`docs/DESIGN_SYSTEM.md`) is the
authoritative reference for tokens, components, and rules. Every change
here must extend the system, never bypass it.

**Pre-flight**: same toolchain as the responsiveness prompt —

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat ktlintFormat ktlintCheck detekt lint testDebugUnitTest
```

Zero warnings remains the floor. Fix the right Android way; never mute.

---

## Tier 1 — Quick wins (hours, not days)

### #1 — Edge-to-edge + inset handling

**Goal**: comply with Android 15's edge-to-edge requirement (mandatory
on `targetSdk` ≥ 35) and remove the deprecated `fitsSystemWindows`
posture.

**Scope**:

- Add `androidx.activity:activity-ktx` (already in `libs.versions.toml`)
  + ensure `androidx.core:core-ktx` is current.
- In each `Activity.onCreate()`, BEFORE `setContentView`:
  ```kotlin
  enableEdgeToEdge()
  ```
  (Activity-KTX `enableEdgeToEdge()` handles status/nav bar colour for
  edge-to-edge automatically — the theme's explicit `statusBarColor` /
  `navigationBarColor` can stay as fallbacks.)
- In each root layout, replace `android:fitsSystemWindows="true"` with
  a `WindowInsetsCompat`-aware padding strategy:
  ```xml
  <androidx.coordinatorlayout.widget.CoordinatorLayout
      android:layout_width="match_parent"
      android:layout_height="match_parent"
      android:id="@+id/root">
  ```
  And in `onCreate`:
  ```kotlin
  ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
      val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
      insets
  }
  ```
- Special cases: the gamepad + touchpad overlay activities already call
  `hideSystemBars()` for immersive mode. They DO NOT need inset
  handling — leave them be.
- The low-power dim overlay (`overlay_low_power.xml`) must continue to
  cover the system bars; verify it does (it's z-ordered as the last
  include in the CoordinatorLayout, so it should).

**Files**: every `activity_*.xml` root + every `Activity.onCreate()`,
except `GamepadOverlayActivity` + `TouchpadOverlayActivity`.

**Validation**:

- On a device with a display cutout (Pixel 6+ camera notch), system
  status bar overlays content without occluding it.
- On a device with gesture nav (3-button mode also), the bottom
  inset reserves space for the indicator.
- On a foldable, transitioning to the inner display preserves the
  inset layout.
- `lint` passes (no `EdgeToEdge` warning).

**Why now**: required for `targetSdk` 35 and above; the codebase
is already on `targetSdk` 37. Currently the colour-matched bars
hide the issue, but Android 15+ will draw under the bars regardless.

---

### #2 — Drop `textAllCaps` on buttons (Material 3 convention)

**Goal**: align with the modern Android typography convention (Material 3
dropped all-caps from button defaults in 2021). Improves accessibility
(TalkBack reads all-caps letter-by-letter in some cases), localisation
(German nouns + Turkish dotted/dotless `i` don't capitalise cleanly), and
visual feel.

**Scope**:

- `app/src/main/res/values/themes.xml` + `values-night/themes.xml`:
  on `Widget.Dish.Button` and `Widget.Dish.Button.Outlined`, delete the
  `android:textAllCaps="true"` and `android:letterSpacing="0.04"` items.
- For each string consumed by a button, change the `<string>` value
  from all-caps to sentence-case in all 6 locale variants:
  - `app/src/main/res/values/strings.xml`
  - `values-bs/strings.xml`
  - `values-de/strings.xml`
  - `values-es/strings.xml`
  - `values-fr/strings.xml`
  - `values-pt-rBR/strings.xml`
- Re-check button strings — examples to convert:
  - `RECONNECT` → `Reconnect`
  - `SCAN` → `Scan`
  - `PAIR` → `Pair`
  - `ADD HOST` → `Add host`
  - `CANCEL` → `Cancel` (already lower in some places via `@android:string/cancel`)
- Any place that explicitly says `android:textAllCaps="true"` inline as
  callsite intent (the Settings version label, the brand footer) — leave
  alone. Those documented their inline override as a deliberate choice.
- Update `docs/DESIGN_SYSTEM.md`:
  - Remove the "0.04 spacing" + "uppercase" lines from the Button table.
  - Remove the `0.04` entry from the letter-spacing canon if no other
    style consumes it (`Hero` does — keep `0.04` documented there only).

**Files**: 2 theme files, 6 string files, 1 design-system doc.

**Validation**:

- Every button label in the app renders in sentence-case.
- Translations look correct (no off-language casing).
- `lint` clean.
- Visual: buttons read as "modern Android" instead of "Material 2 legacy."

**Why now**: it's a one-day fix, modernises the entire app's feel, and
makes the design system Material 3 ready end-to-end (you've already
migrated the parent styles).

---

### #6 — Delete `GamepadHostLifecycle` interface (YAGNI)

**Goal**: remove dead code per the design-system YAGNI rule. The
interface is defined in `ActivityExtensions.kt` but no Activity
implements it; the three host-bearing Activities each call
`gamepadHost.cancelDimOnStop()` directly. Defined-but-unused
infrastructure is exactly what YAGNI says to drop.

**Scope**:

- Remove `interface GamepadHostLifecycle { … }` from
  `app/src/main/java/com/tinkernorth/dish/ui/common/ActivityExtensions.kt`.
- Update `docs/DESIGN_SYSTEM.md` "Activity Scaffolding" table:
  delete the `GamepadHostLifecycle` row.
- No callsite changes (nothing uses it).

**Files**: 1 Kotlin + 1 doc.

**Validation**: build + lint + detekt + ktlint clean. Test suite still
green.

**Why now**: five-minute fix; carries the YAGNI signal forward.

---

## Tier 2 — Polish initiatives (days to a week)

### #9 — Design-system gallery activity

**Goal**: a single debug-only screen that renders every
`TextAppearance.Dish.*`, `Widget.Dish.*`, and composite include in
isolation. Three purposes: (1) visual reference for designers reviewing
the system, (2) visual regression detector for engineers, (3)
documentation that lives in code (and so can't go stale relative to
the actual rendered output).

**Scope**:

- New Activity: `app/src/main/java/com/tinkernorth/dish/debug/DesignSystemGalleryActivity.kt`.
  Manifest entry gated to debug builds via build variant filtering
  (`<activity android:enabled="@bool/is_debug" />` with `is_debug`
  defined separately per buildType).
- New layout: `app/src/main/res/layout/activity_design_system_gallery.xml`.
  Vertical scrolling list of sections:
  1. **Typography ramp** — every `TextAppearance.Dish.*` style rendered
     with the style name as label.
  2. **Buttons** — `Widget.Dish.Button` (default + disabled + pressed),
     `Widget.Dish.Button.Outlined` (default + disabled + pressed).
  3. **Cards** — empty `Widget.Dish.Card` at three widths.
  4. **Switch** — on / off / disabled.
  5. **Status pills** — connected / connecting / disconnected /
     unknown.
  6. **Status dots** — small + large × success / error / muted.
  7. **Empty states** — Picker variant + Section variant.
  8. **Composite includes** — `section_header` (eyebrow only + full
     with icon and button), `card_row_icon_label_value`, `status_pill`.
  9. **Severity colours** — `colorSuccess` / `colorError` / `colorWarning`
     swatches with contrast ratio annotated.
- Entry point: from `SettingsActivity`'s "About" section (debug builds
  only), a hidden row "Design system gallery (debug)" linking to the
  activity. Or: a single-line shell command `adb shell am start
  -n com.tinkernorth.dish/.debug.DesignSystemGalleryActivity`.

**Files**: 1 Activity, 1 layout, 1 manifest entry, 1 conditional row
in Settings.

**Validation**:

- All styles render without crashes.
- Visual: every component rendered matches its description in
  `docs/DESIGN_SYSTEM.md`.
- Production builds do NOT include the activity (`is_debug` is false in
  release).

**Why**: as the design system evolves, a gallery is the single source
of "what does this style actually look like." Designers + engineers
look at the same thing. Snapshot tests in #10 build on top.

---

### #10 — Snapshot (visual regression) tests for the design system

**Goal**: every `Widget.Dish.*` and composite include pinned to a
known-good screenshot. Any future code change that perturbs the
visual output fails the CI run with a side-by-side diff.

**Scope**:

- Add Roborazzi (Robolectric + screenshot) — `libs.versions.toml`:
  ```toml
  roborazzi = "1.30.0"
  robolectric = "4.13"
  ```
- Test source set: `app/src/test/java/com/tinkernorth/dish/snapshot/`.
- One test class per component, each method renders an instance of
  the component, asks Roborazzi to capture, compares to the pinned
  baseline:
  ```kotlin
  @RunWith(RobolectricTestRunner::class)
  @Config(qualifiers = "w411dp-h891dp-xxhdpi")
  class WidgetDishButtonSnapshotTest {
      @get:Rule val roborazzi = RoborazziRule()

      @Test
      fun filledButton_default() {
          val ctx = ApplicationProvider.getApplicationContext<Context>()
          val view = MaterialButton(ctx, null, R.attr.materialButtonStyle).apply {
              text = "Reconnect"
          }
          view.captureRoboImage("buttons/filled_default")
      }
  }
  ```
- Bind the gallery activity (#9) for the larger snapshot:
  ```kotlin
  @Test
  fun designSystemGallery() {
      ActivityScenario.launch(DesignSystemGalleryActivity::class.java).use {
          it.captureRoboImage("gallery/full")
      }
  }
  ```
- Pinned baselines live in `app/src/test/snapshots/`, committed to git.
- A change that perturbs rendering produces a diff under
  `build/outputs/roborazzi/`; the reviewer either accepts (re-baselines)
  or fixes the regression.

**Files**: ~10 test files, snapshots directory, gradle config.

**Validation**:

- CI fails when a deliberate visual change is made without re-baselining.
- The full snapshot suite runs in under 60 seconds.
- A new contributor can re-baseline with one command:
  `./gradlew recordRoborazziDebug`.

**Why**: the design system is a contract. Without snapshot tests, the
contract is enforced by a human reviewer eyeballing the demo. Roborazzi
is fast, deterministic, and integrates with the existing JUnit suite.

**Depends on**: #9 (the gallery activity is the largest target).

---

### #11 — Settings expansion (input mapping + a11y controls)

**Goal**: turn the current 2-card Settings screen into a real
preferences surface that exposes the rich settings the input layer
already supports. Right now the Settings screen shows crash reporting +
privacy URL. For a controller-input app, this is conspicuously thin.

**Scope** (each is a new card; pick the subset that matters):

- **Input mapping** — a card that opens a sub-screen letting users
  remap virtual gamepad buttons (e.g. swap A/B, remap touchpad-click to
  L3). Persist via SharedPreferences. Wire into `GamepadTouchView`'s
  paint loop.
- **Deadzone tuning** — slider that adjusts the virtual stick deadzone
  per-input. Persist + apply in `GamepadTouchView`.
- **Motion sensitivity** — slider that scales gyro output before
  emission. Already supported by the wire format (`MotionReport`); just
  needs a multiplier applied in `MotionSampler`.
- **Language picker** — `LocaleManager.setApplicationLocales(...)` for
  per-app language. Lists the 6 available locales + system default.
- **Contrast / text size** — toggle that bumps text styles up one
  step (BodySmall → Body, Body → BodyLarge, etc.) for users who want
  larger UI without going through system-wide Display settings.
- **Reset to defaults** — explicit button that clears the above prefs.

**Scope (mechanical)**:

- Each new card adopts `Widget.Dish.Card` and the
  `card_row_icon_label_value.xml` composite.
- Per-card icon: pick from existing iconography or add a new
  single-tone vector (24dp canvas, 2dp stroke). Document the new icon
  in `docs/DESIGN_SYSTEM.md` per the icon-style rules.
- Strings live in `values/strings.xml` + 5 locale variants.
- Persistence: a single `DishPreferences` class wrapping
  `SharedPreferences` with typed accessors per setting.

**Files**: ~6 new strings × 6 locales, ~3 new card layouts, 1 new
preferences class, ~3 sub-Activity layouts if the settings have
sub-screens.

**Validation**:

- Every new setting persists across app restart.
- Language picker changes the entire app's locale immediately.
- The Settings screen still feels under-furnished only if you wanted
  it to be — but at least the obvious gaps are filled.

**Why**: power users will complain about thin Settings before they
complain about anything else. Also the input layer already supports
all of this; surfacing it in the UI is the bottleneck.

---

## Tier 3 — Strategic bets (multi-week, multi-quarter)

### #13 — Compose for new screens (incremental migration)

**Goal**: new screens authored in Compose, existing screens stay
View-based. Lets you bring Compose into the codebase without a risky
mid-product rewrite. The token system maps cleanly:

- `MaterialTheme.colorScheme.primary` ↔ `colorPrimary`
- `MaterialTheme.colorScheme.surface` ↔ `colorSurface`
- `Modifier.padding(spacing_md)` reading `@dimen/spacing_md` via
  `dimensionResource(R.dimen.spacing_md)`
- `MaterialTheme.typography.titleMedium` ↔ `TextAppearance.Dish.Title`

**Scope**:

- Add Compose to `libs.versions.toml`:
  ```toml
  composeBom = "2025.05.00"
  ```
- New module-level dependencies in `app/build.gradle.kts`:
  ```kotlin
  implementation(platform("androidx.compose:compose-bom:${libs.versions.composeBom.get()}"))
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui-tooling-preview")
  debugImplementation("androidx.compose.ui:ui-tooling")
  ```
- Bridge the token system into a Compose theme:
  `app/src/main/java/com/tinkernorth/dish/ui/compose/DishComposeTheme.kt`:
  ```kotlin
  @Composable
  fun DishComposeTheme(content: @Composable () -> Unit) {
      val colorScheme = darkColorScheme(
          primary = colorResource(R.color.colorPrimary),
          onPrimary = colorResource(R.color.colorOnPrimary),
          surface = colorResource(R.color.colorSurface),
          // … remaining slots
      )
      MaterialTheme(
          colorScheme = colorScheme,
          typography = dishTypography(),
          shapes = dishShapes(),
          content = content,
      )
  }
  ```
- Migrate ONE existing screen first as a proof: `SettingsActivity`'s
  body (everything below the toolbar) is a good candidate — it's
  list-shaped, mostly static, low touch surface, and the toolbar can
  stay View-based via `ComposeView` interop. Document the migration
  pattern in `docs/DESIGN_SYSTEM.md` for future contributors.
- All NEW screens (e.g. the Settings sub-screens from #11) author in
  Compose from day one.

**Files**: gradle + theme + one existing screen + N new screens.

**Validation**:

- Compose Preview works for new composables.
- No performance regression vs. the View-based version on slow devices
  (compare APK size + cold-start time pre/post).
- The token system stays the single source — no Compose-only colours
  or sizes drift in.

**Why**: new screens cost less LOC in Compose, type-safe, hot-reload
during development. Migration risk is contained because the existing
screens are not forced to change.

---

### #14 — Light mode (only if product wants it)

**Goal**: optional. Design + ship a light-mode palette so the app
respects system theme preference. Most controller apps are dark-only;
shipping light mode could differentiate from competitors AND help
users in bright environments.

**Scope** (this is mostly a design exercise, then mechanical):

1. **Design** — palette decisions (1–2 weeks with a designer):
   - `colorBackground` (light): paper white? off-white?
   - `colorSurface` (light): slightly tinted variant?
   - `colorPrimary` (light): same brand cyan? darker variant?
   - `colorOnSurface` (light): near-black?
   - `colorMuted` (light): slate-with-more-density?
   - Icon palettes: are the multi-tone illustrations still legible?
   - The "deep-space" identity may not translate to light at all; an
     alternate brand expression for the light variant might be the
     real answer (this is exactly why this is in Tier 3, not Tier 1).
2. **Implementation** — move the entire `values/colors.xml` semantic
   layer into both `values/colors.xml` (light) AND
   `values-night/colors.xml` (dark, current). The primitives stay in
   one file; the semantic mappings fork.
3. **`Theme.Dish`** in `values/themes.xml` now uses the LIGHT palette;
   `values-night/themes.xml` uses the DARK palette. The
   `Theme.Material3.DayNight.NoActionBar` parent already handles the
   switching.
4. **WCAG audit** — recompute all contrast pairs for the light palette
   (see `docs/DESIGN_SYSTEM.md` "Accessibility (WCAG AA contrast)" for
   the dark-palette reference). Document the light-palette pairs the
   same way.
5. **Visual test** — open every screen in light mode + dark mode and
   confirm both look intentional. Snapshot tests (#10) will catch
   regressions automatically once light mode is the baseline.

**Files**: ~3 colour files, 2 theme files, expanded WCAG section.

**Validation**:

- System preference flip (Settings > Display > Light/Dark) switches
  the app's palette instantly.
- Both modes pass WCAG AA on every text pair.
- The brand identity reads coherent in both modes.

**Why later**: this is a brand-level decision, not a tech-level one.
Don't ship without a designer in the room.

---

### #15 — NavGraph / Navigation 3

**Goal**: replace manual `Intent` navigation between Activities with
a NavGraph (or Navigation 3's NavController). Wins:
- Type-safe destinations.
- Deep-link support (notifications can open a specific Connection,
  not just MainActivity).
- Animated transitions defined per-edge.
- Predictive-back-gesture support on Android 14+.

**Scope**:

- Add Navigation 3 to `libs.versions.toml`:
  ```toml
  nav3 = "2.10.0"  # check current
  ```
- Decide: NavGraph XML (View-based, declarative) OR NavController +
  Composable destinations (if you've already done #13).
- Convert the four user-facing chrome screens
  (Main / Settings / Connections / GamepadOverlay / TouchpadOverlay)
  to NavGraph destinations. Overlays stay separate Activities (they're
  full-screen immersive surfaces — not a great NavGraph citizen).
- Wire deep links: e.g. `dish://connections/{satelliteId}` opens
  Connections scrolled to the named satellite.
- Per-destination transitions: e.g. Main → Settings uses fade-through
  (from Phase 3 of the responsiveness prompt); Connections → PairPin
  dialog stays a Dialog overlay.

**Files**: ~1 NavGraph XML or NavHost composable, ~5 Activity
refactors, manifest deep-link declarations.

**Validation**:

- Every chrome screen reachable via NavController API.
- Notifications can deep-link to a specific Connection or Settings
  section.
- System back gesture animates correctly per destination.

**Why later**: 4 screens is small enough that manual Intent navigation
isn't painful yet. Past ~6 screens, this becomes the next major
refactor. Bundle with #13 if you do both.

---

### #16 — Dynamic colour (Material You) — for the accent only

**Goal**: respect the user's Material You palette (Android 12+) for
the accent colour while keeping the deep-space brand identity for
chrome. Half the work is already done — the monochrome launcher
icon is in place.

**Scope**:

- In `Theme.Dish`, replace the explicit `colorPrimary`,
  `colorOnPrimary`, etc. with `?attr/colorPrimary` resolved via
  `DynamicColors.applyToActivitiesIfAvailable(application)` in
  `DishApplication.onCreate()`.
- Conditional: ONLY apply dynamic colour when the user opts in
  (Settings toggle). The default should preserve the cyan brand.
- The monochrome launcher icon already exists at
  `app/src/main/res/drawable/ic_launcher_monochrome.xml`; Android 13+
  uses it for themed-icon mode automatically.
- Notification accent could respect the dynamic palette too.

**Files**: 1 Application class change, 1 theme attribute swap, 1
Settings preference, 1 manifest meta-data.

**Validation**:

- With dynamic colour OFF: the app uses the brand cyan everywhere
  (current behaviour).
- With dynamic colour ON: accent surfaces tint to the user's
  Material You palette while the deep-space chrome stays dark.

**Why later**: the brand identity matters more than dynamic colour for
this app. But it's a relatively cheap nod to OS conventions if the
user wants it.

---

## How to ship these

- **Tier 1** items can ride on the same PR (it's all ~1 day of work
  total). Title: `polish: edge-to-edge + sentence-case buttons + dead-code drop`.
- **Tier 2** items each warrant their own PR; #10 depends on #9 so
  ship them in sequence.
- **Tier 3** items are quarter-scale projects; each gets a feature
  branch + design review + dedicated handoff doc.

After each PR:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat ktlintFormat ktlintCheck detekt lint testDebugUnitTest assembleDebug
```

Zero warnings; no test regressions; no visual surprises on a real
device.
