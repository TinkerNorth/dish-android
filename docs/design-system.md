# Dish Android: Design System

A rigid, two-tier token system plus a small library of `TextAppearance` /
`Widget` / composite-layout / activity-extension building blocks. Every UI
attribute that repeats in the app reaches its callsite through one of the
names enumerated below.

If a value is missing from this document, it is either deliberately one-off
(documented at the bottom of this file) or new drift to clean up: open the
file it lives in, give it a name here, and route the callsite through it.

---

## Token Tiers

Two layers, every time:

1. **Primitives**: raw values (hex, dp, sp, ms). Lives in `*_primitives.xml`.
   Never referenced directly from layouts, drawables, or Kotlin.
2. **Semantic**: names by role (`colorPrimary`, `spacing_md`, `card_padding`,
   `corner_card`, `text_label`, `elevation_floating`,
   `motion_duration_spinner`). The only names callsites use.

Why the indirection: a future redesign that wants `spacing_md` to be 12dp
instead of 8dp repoints the semantic name to a different primitive without
renaming every callsite. The primitive scale is value-encoded (`size_8` is
always 8dp), so a stale grep against the literal number still surfaces the
token.

---

## Tokens

### Colors

Day/night-aware. Two primitive layers + one semantic layer, with the
night qualifier swapping the primitive bindings under the same semantic
names.

| File | Role |
|---|---|
| `values/colors_primitives.xml` | shared raw palette (cyan, navy, slate, paper, severity) |
| `values/colors.xml` | **light-mode** semantic → primitive mapping |
| `values-night/colors.xml` | **dark-mode** override (only the slots that differ from light) |

Tokens not overridden in `values-night/` fall back to `values/`, so
mode-neutral roles (severity, scrim, alpha-derived outlines) live only in
the light file. The cyan brand primary is restated in both for one-place
lookup.

The user's choice in **Settings → Appearance** (System / Light / Dark) is
persisted by [`ThemePreferenceStore`](../app/src/main/java/com/tinkernorth/dish/source/store/ThemePreferenceStore.kt)
and applied via `AppCompatDelegate.setDefaultNightMode`, which flips
Android's resource resolution between the two `colors.xml` files the same
way a device-wide uiMode change does.

**Semantic tokens** (what layouts and Kotlin reference):

| Token | Light | Dark | Role |
|---|---|---|---|
| `colorBackground` | `paper_75` | `navy_900` | app + window background |
| `colorSurface` | `paper_50` | `navy_800` | cards, dialogs |
| `colorSurfaceDim` | `paper_100` | `navy_700` | empty-state surfaces |
| `colorOnSurface` | `navy_900` | `paper_50` | primary text |
| `colorOnPrimary` | `navy_900` | `navy_900` | text on cyan fills |
| `colorPrimary` | `signal_cyan_500` | (same) | brand accent |
| `colorPrimaryDark` | `signal_cyan_700` | (same) | filled-button bg |
| `colorMuted` | `slate_500` | `slate_300` | secondary text + chrome |
| `colorOutline` | `signal_cyan_300` | (same) | component borders (cyan @ ~18% alpha) |
| `colorCardStroke` | `signal_cyan_200` | (same) | card stroke (cyan @ ~12% alpha) |
| `colorIconContainerFill` | `signal_cyan_100` | (same) | subtle fills (cyan @ ~6%) |
| `colorSuccess` / `colorError` / `colorWarning` | severity primitives | (same) | status |
| `colorOverlayScrim` | `ink_900_a80` | (same) | low-power dim |

The full primitive palette (including the M3 container family, surface
hierarchy, tertiary, and inverse slots) lives in
`colors_primitives.xml`. Callsites should never reference primitives
directly; route through a semantic name and add one if the slot you
need doesn't exist.

**M3 attr surface.** `Theme.Dish` pins the full M3 container hierarchy
(`colorSurfaceContainer*`, `colorPrimaryContainer`, `colorTertiary`,
`colorSurfaceInverse`, the shape ramp) so widgets that read these slots
inherit brand-correct values instead of M3's auto-derived greys. The
mappings are concentrated in `values/themes.xml`; the block comments
there carry the M3-spec rationale for each pin.

**Theme parent.** `Theme.Material3Expressive.DayNight.NoActionBar.FocusRings`.
The `FocusRings` parent swaps the platform's near-invisible focus-state
layer for an M3 ring drawable on every focusable widget, important here
because the app's primary use case is a gamepad-driven controller picker,
where keyboard / D-pad / gamepad navigation has to show what's focused.

### State-list colors → `values/colors_state.xml` + `color/*.xml`

Android requires `<selector>` color resources to live in `res/color/`. The
values file holds the registry index + any state-list-only semantic aliases.

| Selector (`res/color/`) | Default → State |
|---|---|
| `chip_pickable_text.xml` | `colorOnSurface` → `state_selected` `colorOnPrimary` |
| `icon_secondary.xml` | always `colorMuted` (selector-shaped for naming clarity) |

Disabled-state selectors (`text_disabled.xml` / `icon_interactive.xml`) are
intentionally absent: they were dropped in the post-Phase-4 cleanup. Add
them back the first time a layout actually needs to toggle `isEnabled` on a
text/icon view (the recipe is a one-line `<selector>` in `res/color/`).

### Spacing / sizing → `values/dimens_primitives.xml` + `values/dimens.xml`

**Primitives** (value-encoded dp/sp):

- dp scale: `size_1, _2, _3, _4, _6, _8, _10, _11, _12, _14, _16, _18, _20,
  _22, _24, _28, _32, _36, _40, _56, _64, _280` (literal dp value in name).
  `0dp` is intentionally NOT here: it is reserved as the idiomatic Android
  "weight-distributed child" sentinel.
- sp scale: `text_10` through `text_24`, plus `text_64` (hero clock).

**Semantic** (grouped by section in `dimens.xml`):

| Group | Names |
|---|---|
| spacing T-shirt scale | `spacing_xs` (4) → `spacing_6xl` (24) in 10 steps |
| strokes + corners | `border_thin`, `divider_height`, `corner_chip`, `corner_picker_row`, `corner_button`, `corner_card`, `corner_notification`, `corner_pill` |
| icon sizes | `icon_dot[_lg]`, `icon_battery`, `icon_picker_glyph`, `icon_brand_sm`, `icon_section_header`, `icon_card_container`, `icon_card_glyph`, `icon_action`, `icon_row_glyph`, `icon_controller`, `icon_chevron_lg`, `icon_settings_button` |
| card | `card_padding[_horizontal/_bottom]`, `card_margin_bottom` |
| inline picker row | `picker_row_padding`, `picker_row_margin_top`, `picker_section_margin_top`, `picker_section_padding_bottom`, `picker_label_margin_end` |
| chip | `chip_padding_horizontal/_vertical`, `chip_margin_end` |
| dialog / overlay | `dialog_inline_max_width` |
| notification | `notification_rail_width`, `notification_elevation`, `notification_padding_horizontal/_vertical`, `notification_text_leading_indent`, `notification_text_title/_body/_action` |
| text sizes (role-named sp) | `text_caption` (10) → `text_hero` (64); see `text_appearance.xml` for the canonical mapping into TextAppearance styles |

### Elevation → `values/elevations.xml`

Two resting heights. Layouts and styles reference these instead of raw dp
values so a global shadow-depth adjustment is one file.

| Token | Value | Use |
|---|---|---|
| `elevation_none` | 0dp | flat surfaces (cards on a page, list rows) |
| `elevation_floating` | 6dp | Snackbars, FABs (`notification_elevation` aliases this) |

`elevation_raised` (2dp) and `elevation_modal` (12dp) used to exist but
were dropped in the post-Phase-4 cleanup: no current callsite needed
them. Re-introduce when a surface needs to pin to the canonical scale
(see "When to add a new token").

### Motion → `values/motion.xml` + `interpolator/*.xml`

**Durations** (millisecond integers: use via
`android:duration="@integer/…"` in AVDs and
`context.resources.getInteger(R.integer.…).toLong()` from Kotlin):

*Named, off-scale (each tied to a specific asset's design rhythm):*

| Token | Value | Use |
|---|---|---|
| `motion_duration_spinner` | 1200 ms | DishLoaders spinner + dots loop |
| `motion_duration_bar` | 1400 ms | DishLoaders bar loop (intentional different cadence) |
| `motion_duration_battery` | 3600 ms | charging-battery AVD full ramp |
| `motion_duration_bolt_pulse` | 750 ms | charging-battery bolt sinusoidal opacity loop |

*UI speed scale (medium only; short/long re-added when consumed):*

| Token | Value | Use |
|---|---|---|
| `motion_duration_medium` | 250 ms | navigation transitions (activity fade-through rides on this) |

`motion_duration_short` (150ms) and `motion_duration_long` (400ms) are the
canonical short/long companions, but neither has a consumer in the
codebase today. Per the YAGNI rule, they're not declared until a real
in-screen transition pins to them. When you add one, re-introduce the
sibling tokens together as a paired set so the scale stays coherent.

**Interpolators** (referenceable from AVDs + ObjectAnimators):

| File | Curve | Use |
|---|---|---|
| `dish_ease_standard.xml` | cubic-bezier(0.4, 0.0, 0.2, 1.0) | default UI transitions (used by `ic_battery_charging` bolt-pulse loop + activity fade-through) |

The `dish_ease_in.xml` / `dish_ease_out.xml` / `dish_ease_linear.xml`
interpolators were dropped in the post-Phase-4 cleanup: no animation
consumes them today. Re-introduce when an AVD or ObjectAnimator needs
a curve other than the standard easing.

**Activity transitions** (`app/src/main/res/anim/`):

| File | Use |
|---|---|
| `fade_through_enter.xml` | OPEN/CLOSE enter half of the activity fade-through. Alpha 0 → 1 over `motion_duration_medium` with `dish_ease_standard`. |
| `fade_through_exit.xml` | OPEN/CLOSE exit half. Alpha 1 → 0 over the same duration and curve. |

Wired into chrome activities via `applyDishActivityTransitions()`. See
the Activity Scaffolding table below. The Gamepad / Touchpad overlays
intentionally do NOT call this (immersive input surfaces prioritise
zero-latency display over a 250 ms fade).

### Typography → `values/text_appearance.xml`

17 canonical `TextAppearance.Dish.*` styles. Every TextView in
`res/layout/*.xml` adopts one via `style="@style/…"` (NOT
`android:textAppearance`: the `style="…"` form applies every attribute
including `lineSpacingMultiplier`; `android:textAppearance` silently drops
line spacing).

Letter-spacing canon (single source of truth, do not drift):
- `0.02`: Body / BodySmall / BodyLarge and their variants (Body.Mono,
  BodyLarge.Bold), the low end of the M3 body tracking range.
- `0.04`: Hero (giant numerals).
- `0.06`: Label.Tight (compact status tag beside a value).
- `0.12`: Label / Label.Accent / Display.Large (eyebrows + brand wordmark).
- `0.18`: Caption (small mono micro-eyebrow / footer).
- Title / Title.Large / Display stay at the platform default of 0.

`Widget.Dish.Button` + `Widget.Dish.Button.Outlined` are NOT in this canon:
the M3 base sets `letterSpacing=0` (no tracking) and `textAllCaps=false`
(sentence-case). Strings supply their own capitalisation. The old M2-era
"tracked uppercase" treatment retired with the M3 migration.

| Style | size | color | family | weight | spacing | line × |
|---|---|---|---|---|---|---|
| `Caption` | 10sp | muted | monospace | normal | 0.18 | none |
| `Label` | 11sp | muted | monospace | normal | 0.12 | none |
| `Label.Accent` | 11sp | primary | monospace | normal | 0.12 | none |
| `Label.Plain` | 11sp | muted | sans-serif | normal | none | none |
| `Label.Micro` | 10sp | muted | monospace | normal | 0.12 | none |
| `Label.Tight` | 11sp | muted | monospace | normal | 0.06 | none |
| `BodySmall` | 12sp | muted | sans-serif | normal | 0.02 | none |
| `BodySmall.Mono.Bold` | 12sp | on-surface | monospace | bold | 0.02 | none |
| `Body` | 13sp | muted | sans-serif | normal | 0.02 | 1.35 |
| `Body.Mono` | 13sp | on-surface | monospace | normal | 0.02 | none |
| `BodyLarge` | 14sp | muted | monospace | normal | 0.02 | none |
| `BodyLarge.Bold` | 14sp | on-surface | sans-serif | bold | 0.02 | none |
| `Title` | 15sp | on-surface | sans-serif-medium | normal | none | 1.3 |
| `Title.Large` | 18sp | on-surface | sans-serif | bold | none | none |
| `Display` | 22sp | primary | monospace | bold | none | none |
| `Display.Large` | 24sp | primary | sans-serif | bold | 0.12 | none |
| `Hero` | 64sp | primary | monospace | bold | 0.04 | none |

Every style has `parent=""` so the attribute list is fully enumerated: no
surprise inheritance from Material/AppCompat text-appearance defaults.

---

## Components

### Theme

Parented at **Material 3 Expressive + FocusRings**
(`Theme.Material3Expressive.DayNight.NoActionBar.FocusRings`). The
DayNight base resolves `values/colors.xml` vs `values-night/colors.xml`
per qualifier (no separate `values-night/themes.xml`: one theme,
two colour bindings). The Expressive parent enables future expressive
component defaults; the FocusRings parent replaces M3's near-invisible
focus state layer with a visible ring drawable, which is what makes
keyboard / D-pad / gamepad focus legible on the controller picker.

The full M3 container family (`colorSurfaceContainer*`,
`colorPrimaryContainer`, `colorTertiary`, the surface inverse slots,
the shape ramp) is pinned in `values/themes.xml` so any M3 widget that
reads those slots inherits brand-correct values instead of M3's
auto-derived greys. `elevationOverlayEnabled = false` blocks M3's
tonal-elevation overlay from tinting elevated cards with the cyan
primary as elevation rises.

| Style | Where | Role |
|---|---|---|
| `Theme.Dish` | `values/themes.xml` | App + activity theme. Pins the M3 colour / surface-container / typography / shape ramps, the default `materialButtonStyle`, and `chipStyle`. DayNight-aware via the colour-resource split. |
| `Theme.Dish.Dialog` | `values/themes.xml` | Pair-PIN dialog overlay (parented at `Theme.Material3.DayNight.Dialog`). Transparent `windowBackground` (so `bg_pill`'s rounded corners + cyan outline are the only visible shape), 60% backgroundDim, no title. |
| `ThemeOverlay.Dish.MaterialAlertDialog` | `values/themes.xml` | Branded chrome for `MaterialAlertDialogBuilder` so the remaining stock dialogs (forget-BT confirmation, Bluetooth profile picker) match the app's brand language. |

**Appearance picker.** Settings → Appearance lets the user override the
device-wide day/night switch (System / Light / Dark). The choice is
persisted to `user_preferences` SharedPreferences (cloud-backed) by
[`ThemePreferenceStore`](../app/src/main/java/com/tinkernorth/dish/source/store/ThemePreferenceStore.kt),
which also flips `AppCompatDelegate.setDefaultNightMode`. That triggers
every live AppCompatActivity (including the GameActivity-derived
`MainActivity`) to recreate with the new colour primitives.

### `Widget.Dish.*` styles

The component-level closure for repeated Material widget configurations.
Every callsite of these widgets in `res/layout/*.xml` references one of them
via `style="@style/Widget.Dish.X"`.

**Buttons** (in `values/themes.xml`, next to `Theme.Dish` because the theme
wires `materialButtonStyle = Widget.Dish.Button` as the global default):

| Style | Parent | Adds |
|---|---|---|
| `Widget.Dish.Button` | `Widget.Material3.Button` | filled: `colorPrimaryDark` bg, `colorOnPrimary` text (sentence-case), `corner_button`, zero insets, `colorPrimaryMid` ripple |
| `Widget.Dish.Button.Outlined` | `Widget.Material3.Button.OutlinedButton` | transparent bg, `colorOnSurface` text (sentence-case), `colorOutline` stroke (`border_thin`), `corner_button`, zero insets, `colorPrimaryDark` ripple |

**Everything else** (in `values/styles_widgets.xml`):

| Style | Parent | Adds | When to use |
|---|---|---|---|
| `Widget.Dish.Card` | `Widget.Material3.CardView.Outlined` | `colorSurface` bg, `corner_card` radius, `elevation_none`, `colorCardStroke` stroke (`border_thin`) | every `MaterialCardView` in the app |
| `Widget.Dish.Toolbar` | `Widget.Material3.Toolbar` | `colorBackground` bg, `colorOnSurface` title, `ic_chevron_left` nav icon, action-bar height | both Activity toolbars (Settings + Connections) |
| `Widget.Dish.StatusDot.Small` | `""` | `icon_dot` size + `dot_circle` background | overlay status indicators |
| `Widget.Dish.StatusDot.Large` | `""` | `icon_dot_lg` size + `dot_circle` background | slot-card / row status indicators |
| `Widget.Dish.EmptyStateText` | `""` | base: `match_parent` width, `TextAppearance.Dish.BodySmall` | never used directly; pick a variant |
| `Widget.Dish.EmptyStateText.Picker` | `Widget.Dish.EmptyStateText` | + `spacing_xs` top margin | `picker_empty_label.xml` (slot-card empty picker) |
| `Widget.Dish.EmptyStateText.Section` | `Widget.Dish.EmptyStateText` | + `spacing_xl` vertical padding | `tvSatelliteEmpty` + `tvBtEmpty` in `activity_connections.xml` (standalone empty-state row inside a section, no surrounding card) |
| `Widget.Dish.Switch` | `Widget.Material3.CompoundButton.MaterialSwitch` | hides side labels (`textOn`/`textOff` empty, `showText=false`); thumb + track colors come from the Material theme | every `com.google.android.material.materialswitch.MaterialSwitch` in the app (M3 widget class, different from the M2 `SwitchMaterial` the codebase used pre-migration) |
| `Widget.Dish.EditText.Pin` | `Widget.AppCompat.EditText` | numeric input, center gravity, `text_display` size, `colorOnSurface`, monospace, 0.30 spacing, `bg_pair_pin_field` drawable, `card_padding` padding | pair-PIN entry field (API 26+ `autofillHints` + `importantForAutofill` stay inline at the callsite; style items can't carry `tools:targetApi`) |
| `Widget.Dish.Icon.Section` | `""` | `icon_section_header` size, no tint | leading multi-tone icons on section headers (ic_satellite, ic_bluetooth) |
| `Widget.Dish.Icon.Card` | `""` | `icon_card_glyph` size + `colorPrimary` tint | leading single-tone glyphs on Settings card rows (ic_bug, ic_shield) |

---

## Layout Composites

Reusable layout fragments included from their callsites via
`<include layout="@layout/…"/>`. Each composite bakes in the canonical
inner ids; include sites reach those ids through View Binding (e.g.
`binding.sectionSatellites.btnSectionAction`).

| Composite | Callsites | What's inside | What's NOT inside |
|---|---|---|---|
| `section_header.xml` | dashboard CONNECTIONS + CONTROLLERS, Connections SATELLITES + BLUETOOTH, Settings DIAGNOSTICS + ABOUT (6) | horizontal row: `iconSection` (Icon.Section, `gone` by default) + `labelSection` (Label.Accent, `weight=1`) + `btnSectionAction` (Button.Outlined, `gone` by default) | per-callsite vertical spacing, applied via the `<include>` tag's `layout_marginTop/Bottom` |
| `status_pill.xml` | gamepad-overlay connection pill, gamepad-overlay motion pill, touchpad-overlay connection pill (3) | `bg_pill` background, 85% alpha, padding 16dp h × 6dp v: `statusPillDot` (StatusDot.Small) + `statusPillLabel` (Label + `colorOnSurface` override for pill contrast) | nothing: pills are uniform across callsites |
| `card_row_icon_label_value.xml` | Settings Crash card, Settings Privacy card (2) | leading 36dp `bg_icon_container` FrameLayout hosting `cardRowIcon` (Icon.Card) + vertical title/subtitle column (`cardRowTitle` = Title, `cardRowSubtitle` = Body) | the `MaterialCardView` wrapper, the outer row's `gravity` (top vs center_vertical), the trailing affordance (Switch vs chevron) |
| `picker_empty_label.xml` | `ControllerAdapter.addLabel` (no-connections empty state in the slot picker) | single TextView with `Widget.Dish.EmptyStateText` style | nothing: adapter only sets `.text` |

`section_header.xml` is single-layout with code-driven content: `<include>`
overrides only support root-level `android:id`/layout_*/`android:visibility`,
so child ids carry canonical names and Activity code sets icon/label/button
content. Eyebrow callsites leave the icon + button at `gone` and only set
the label text; full-section callsites flip visibility + assign content.

`status_pill.xml`'s default text is `overlay_status_streaming`: the motion
pill's first frame would briefly read "STREAMING" instead of "MOTION: NO
GYROSCOPE", but `repaintFrom(currentMotionPaint())` runs synchronously in
`GamepadOverlayActivity.onCreate` before the first frame is presented.

`card_row_icon_label_value.xml` does NOT cover `item_controller.xml`'s
slot-card header: the slot card uses a bare 36dp tinted ImageView (no
`bg_icon_container`), a `BodySmall` subtitle, and a 5-sibling trailing zone.
Forcing it into the composite would bloat the composite with controller-
specific visibility-gone defaults.

---

## Navigation

The chrome + overlay activities are wired through a single
`androidx.navigation` graph at `app/src/main/res/navigation/nav_graph.xml`.
All destinations are `<activity>` nodes (Dish uses per-Activity
architecture); `ActivityNavigator` translates each `navigate(actionId)`
call into `startActivity(Intent)` with the action's argument bundle
applied as Intent extras (argument names map 1:1 to extras).

The graph is consumed via `DishNavigator`
(`app/src/main/java/com/tinkernorth/dish/ui/common/DishNavigator.kt`), a
thin typed wrapper that gives every navigation site Kotlin-level
type-safety (a mistyped extra is a compile error rather than a silent
runtime "extra missing" branch):

```kotlin
private val nav by lazy { DishNavigator(this) }
// ...
nav.toConnections()
nav.toConnectionsForPairing(connectionId)
nav.toGamepad(connectionId = cid, usePsLayout = true)
nav.toTouchpad(connectionId = cid, slotId = slotId)
```

| Destination | Activity | Args |
|---|---|---|
| `mainActivity` (start) | `MainActivity` | none |
| `connectionsActivity` | `ConnectionsActivity` | `extra_pair_prompt_for_id` (nullable string) |
| `settingsActivity` | `SettingsActivity` | none |
| `configureBindingsActivity` | `ConfigureBindingsActivity` | `extra_slot_id` |
| `setupInputActivity` | `SetupInputActivity` | none |
| `setupUsbActivity` | `SetupUsbActivity` | none |
| `setupBluetoothControllerActivity` | `SetupBluetoothControllerActivity` | none |
| `setupConnectionActivity` | `SetupConnectionActivity` | `extra_setup_input_type`, `extra_setup_slot_id` |
| `setupBluetoothHostActivity` | `SetupBluetoothHostActivity` | `extra_setup_input_type`, `extra_setup_slot_id` |
| `setupConfigureActivity` | `SetupConfigureActivity` | `extra_setup_slot_id`, `extra_setup_connection_id` |
| `helpActivity` | `HelpActivity` | none |
| `donateActivity` | `DonateActivity` | none |
| `gamepadOverlayActivity` | `GamepadOverlayActivity` | `extra_connection_id`, `extra_use_ps_layout` |
| `touchpadOverlayActivity` | `TouchpadOverlayActivity` | `extra_connection_id`, `extra_slot_id` |
| `nativeUnavailableActivity` | `NativeUnavailableActivity` | none |

**Deep links**: NOT declared in the graph. The navigation runtime's lint
check (`DeepLinkInActivityDestination`) flags `<deepLink>` on `<activity>`
destinations because the back-stack behaviour is wrong for cross-app
implicit-intent flows: system back should return to the calling app, but
Activity-destination deep links land the user inside this app's own task.
External deep linking should land via a manual `<intent-filter>` on the
receiving Activity in `AndroidManifest.xml`, or wait until a Fragment-
destination migration unlocks the navigation runtime's deep-link wiring.
The current "pairing needed" notification routes through MainActivity's
in-app callback (`DishNavigator.toConnectionsForPairing`), not a deep
link, so the lint check stays clean.

**What stays outside the graph**: external `startActivity` calls that
target other apps (browser `ACTION_VIEW`, Bluetooth settings, Wi-Fi
settings) keep their direct `startActivity(Intent(...))` form. They're
not Dish destinations and don't belong in the in-app graph.

---

## Activity Scaffolding

Three extension functions on `AppCompatActivity`, all in
`app/src/main/java/com/tinkernorth/dish/ui/common/ActivityExtensions.kt`.

| Symbol | Use |
|---|---|
| `fun setupDishToolbar(toolbar: Toolbar)` | Installs `toolbar` as the support action bar and wires its navigation icon to `finish()`. Toolbar should already carry `style="@style/Widget.Dish.Toolbar"` from its layout. Call after `setContentView`. |
| `fun attachGamepadHost(rootView, wakeState, gamepadRegistry, notifications): GamepadActivityHost` | Constructs + installs the gamepad host against `rootView`. Assign the returned host to a `lateinit var` on the activity so its lifecycle dispatches (`onStop`, `dispatchKeyEvent`, etc.) can reach it. Call after `setContentView`. |
| `fun applyDishSystemBars(root: View)` | Switches the activity into edge-to-edge mode (`enableEdgeToEdge()`) and applies `WindowInsetsCompat.Type.systemBars()` as padding on `root` (typically the binding root). Replaces the deprecated `fitsSystemWindows="true"` layout flag and is required for `targetSdk` ≥ 35. Skipped by the immersive Gamepad / Touchpad overlays, which handle their own bars via `hideSystemBars()`. |
| `fun applyDishActivityTransitions()` | Installs Dish's fade-through transition for both OPEN and CLOSE directions. On API 34+ uses `overrideActivityTransition`; on API 24-33 falls back to the deprecated `overridePendingTransition` (OPEN only; pre-34 CLOSE is platform default). Skipped by the input overlays (they prioritise zero-latency display). |

The input overlays (`GamepadOverlayActivity` + `TouchpadOverlayActivity`)
deliberately go through `BaseInputOverlayActivity`'s inherited scaffolding,
not these extensions: the immersive full-screen wiring (`hideSystemBars()`,
the `lateinit gamepadHost` field, the dispatch overrides, the edge-burst
resend loop) is a bigger interface than these three pieces centralise.

---

## Rules

### When to add a new token

1. Is the value unique to one callsite? **Stay inline** and accept the
   literal: adding a one-callsite token is more drift than it prevents.
2. Is it shared across 2+ callsites? Add a primitive + semantic ref.
3. Is it a per-component aspect (`card_padding`, `chip_corner_radius`,
   `notification_rail_width`)? Add a component-scoped semantic name in the
   appropriate `dimens.xml` section. Changing it moves all callsites in
   lockstep without affecting unrelated rows.
4. Always primitive **then** semantic. Never reference a primitive from a
   callsite: the indirection is what lets a future redesign repoint without
   renaming.
5. **Disabled-state selectors are added when first needed**, not pre-baked.
   A `text_disabled.xml` / `icon_interactive.xml` selector lives in
   `res/color/` only when a real layout toggles `isEnabled` on a view it
   would catch. The same rule covers any other state-list resource:
   carry the cost of the selector + its alpha-primitive only after a
   callsite exists to consume it.

### When to add a new style

- **2+ identical attribute combinations on a TextView** → adopt or add a
  `TextAppearance.Dish.*` style. The 17 existing styles should cover almost
  every case. Fix the callsite to one of them before adding #18.
- **2+ identical attribute combinations on a Material widget** → add a
  `Widget.Dish.<Component>.<Variant>` style in `values/styles_widgets.xml`.
- **A new component-attribute closure** (a brand-new Material widget with
  its own configuration) → add a `Widget.Dish.<Component>` parent style.

### When to add a new layout composite

- A repeated multi-view structure appears in 2+ callsites with identical
  geometry, child types, and child ids? Promote it to
  `res/layout/<context>_<purpose>.xml` and replace callsites with
  `<include layout="@layout/…"/>`.
- The composite owns the shared sub-structure only. Per-callsite divergence
  (gravity, trailing affordance, click behaviour) stays at the include site.
- `<include>` overrides only support root-level `android:id`/layout_*/
  `android:visibility`. Child ids are canonical, and callsites bind to them
  via View Binding through the include's root id.

### When to add a new activity extension

- An Activity-level boilerplate pattern repeats in 2+ activities (toolbar
  setup, lifecycle wiring, system-bar configuration)? Add an extension
  function to `ActivityExtensions.kt`. Compose; never base-class.

### Adding a recipe (e.g. "where do I add a new button?")

A new button in the app:

1. **Drop a `MaterialButton`** into your layout. `Theme.Dish` already wires
   `materialButtonStyle = Widget.Dish.Button`, so any unstyled button gets
   the filled treatment for free.
2. **For an outlined variant**, set `style="@style/Widget.Dish.Button.Outlined"`.
3. **String content carries capitalisation.** Sentence-case is the default
   ("Reconnect", not "RECONNECT"). The style no longer applies
   `textAllCaps`, so the string renders as-written.
4. **Only override inline** if the button needs a callsite-specific size or
   layout param (`minWidth`, `layout_weight`). Everything else inherits
   from the style.

A new TextView in the app:

1. **Pick the matching role** from the 17 `TextAppearance.Dish.*` styles:
   eyebrow → Label / Label.Accent, body copy → Body / BodySmall, card title
   → Title, big numeral → Display. If nothing fits, the callsite is the
   wrong shape. Fix the layout, don't add an 18th style.
2. **Set `style="@style/TextAppearance.Dish.X"`** on the TextView, NOT
   `android:textAppearance="@style/…"`. The `style="…"` form applies every
   attribute including `lineSpacingMultiplier`.
3. **Don't add inline `android:textSize`/`fontFamily`/`textColor`/
   `letterSpacing`**: the style owns these. If the callsite genuinely needs
   an override (severity color, contrast on a tinted background), add an
   inline attribute next to a comment explaining why.

---

## What's deliberately NOT in the design system

- **Vector drawable canvas sizes**. Every `<vector>`'s root `android:width`
  / `android:height` is artwork intrinsic geometry; the layout-side
  `ImageView` sizes it via `icon_*` dimens.
- **Vector drawable embedded fills**. Multi-tone artwork (battery glyph,
  gamepad button icons, satellite, Bluetooth, dish logo) carries its own
  palette. Single-tone single-fill drawables that get tinted from the layout
  are the exception: those use `colorPrimary` / `colorMuted` / `icon_*`
  selectors at the callsite.
- **Custom-view paint configuration**. `DishLoaders.kt` and
  `TouchpadSurfaceView.kt` set up their own `Paint` objects programmatically.
  Hex literals there are intentional: they're internal to the view's drawing
  contract, not part of the token system.
- **`0dp` weight-distribution markers**. `dimens_primitives.xml` deliberately
  excludes `size_0`; `0dp` is the idiomatic Android sentinel for "let the
  weight property compute my size", and giving it a token name would obscure
  that intent.
- **API-26+ attributes on style items**. `android:autofillHints` +
  `android:importantForAutofill` can't sit in a style item (style items can't
  carry a `tools:targetApi` annotation), so callsites apply them inline next
  to `tools:targetApi="26"`. See `Widget.Dish.EditText.Pin`'s callsite in
  `dialog_pair_pin.xml`.
- **Pre-baked disabled-state selectors**. The `text_disabled.xml` /
  `icon_interactive.xml` opt-in selectors were dropped in the post-Phase-4
  cleanup: nothing in the app currently toggles `isEnabled` on a text or
  icon view in a way the selectors would catch. Re-add them when the first
  real callsite needs the fade (one-line `<selector>` in `res/color/` plus
  the alpha primitive + `colorTextDisabled` alias if the selector reaches
  for one). The chevron in `item_controller.xml` adopts `icon_secondary`
  (always-muted) for naming clarity even though the value is the same as
  inline `colorMuted`.
- **`scaffold_toolbar_scroll.xml`**. The Phase 4 spec offered this as a
  deferred composite shared between Settings and Connections. Their bodies
  have diverged enough (Connections has an inter-section divider + a
  low-power overlay include, Settings doesn't; their NestedScrollView
  paddings differ) that the composite would either bloat with conditional
  flags or strip out the screen-specific geometry. The two toolbars already
  share `Widget.Dish.Toolbar`; that's the shared part. The rest is
  intentional per-screen geometry.

---

## Accessibility (WCAG AA contrast)

Every semantic foreground/background pair the app actually paints was
computed against the WCAG 2.1 relative-luminance formula. Targets:

- **Normal text** (any size below 18sp regular / 14sp bold): **≥ 4.5:1** (AA)
- **Large text** (≥18sp regular / ≥14sp bold): **≥ 3:1** (AA)
- **UI components** identifiable only by their boundary (button outlines,
  status dots, dividers, etc.): **≥ 3:1**

### Text pairs

| Foreground | Background | Ratio | Grade |
|---|---|---|---|
| `colorOnSurface` (paper_50) | `colorBackground` (navy_900) | **16.85 : 1** | AAA |
| `colorOnSurface` (paper_50) | `colorSurface` (navy_800) | **16.0 : 1** | AAA |
| `colorOnSurface` (paper_50) | `colorSurfaceDim` (navy_700) | **14.8 : 1** | AAA |
| `colorMuted` (slate_300) | `colorBackground` (navy_900) | **7.77 : 1** | AAA |
| `colorMuted` (slate_300) | `colorSurface` (navy_800) | **7.38 : 1** | AAA |
| `colorPrimary` (cyan_500) | `colorBackground` (navy_900) | **13.0 : 1** | AAA |
| `colorPrimary` (cyan_500) | `colorSurface` (navy_800) | **12.4 : 1** | AAA |
| `colorOnPrimary` (navy_900) | `colorPrimaryDark` (cyan_700) | **5.57 : 1** | AA |
| `colorOnPrimary` (navy_900) | `colorPrimary` (cyan_500) | **13.0 : 1** | AAA |
| `colorSuccess` (green_500) | `colorBackground` (navy_900) | **8.72 : 1** | AAA |
| `colorError` (red_500) | `colorBackground` (navy_900) | **5.21 : 1** | AA |
| `colorError` (red_500) | `colorSurface` (navy_800) | **4.94 : 1** | AA (margin: 0.44) |
| `colorWarning` (amber_500) | `colorBackground` (navy_900) | **9.27 : 1** | AAA |

**Notable history**: filled-button text previously used `colorOnSurface`
(paper_50, white) on `colorPrimaryDark` (cyan_700). That pair is **3.02:1**,
below the WCAG AA normal-text threshold. Migration to `colorOnPrimary`
(navy_900) gives 5.57:1. The M3 convention of pairing `colorOnPrimary` text
with primary-family fills exists exactly for this reason.

### Borderline UI element contrasts (intentional)

Three tokens sit below the WCAG 3:1 UI-component minimum **on purpose**.
They paint a visual texture rather than carry meaning that has to be read
in isolation:

| Token | Approx ratio on `colorBackground` | Why it's intentional |
|---|---|---|
| `colorOutline` (cyan @ 18%) | **~1.43 : 1** | Outlined-button border. The button is identified by its label (16.85:1 contrast); the border is a calm visual cue, not the affordance. |
| `colorCardStroke` (cyan @ 12%) | **~1.25 : 1** | Card stroke. The card is identified by its `colorSurface` background fill + the content inside; the stroke is a brand hairline. |
| `colorSurfaceTint` (cyan @ 6%) | **~1.10 : 1** | Subtle surface tint behind the Settings-row icon container. Decorative, never used to convey state. |

If any of these tokens is ever used to carry meaning on its own (e.g. a
border that *signals* a selected state without a paired colour or icon
change), it must be revisited.

### Severity colours on tinted surfaces

`colorError` on `colorSurface` is 4.94:1, comfortably above the AA
threshold, but with the smallest margin of any text pair in the system.
If a future card adopts a darker surface variant (`colorSurfaceDim` or
deeper), the error red can drop below AA. Compute before adopting.

### What this audit does **not** cover

- **Multi-tone vector drawables** (battery glyph, gamepad button icons,
  satellite/Bluetooth illustrations). Their embedded palettes are not in
  the token system, and they're decorative: text labels accompany every
  meaningful glyph. No contrast obligation.
- **`DishLoaders` and other custom-paint views** that draw with hard-coded
  hex values internal to the view's drawing contract.
- **Dynamic states** (focus rings, pressed-state ripples): these inherit
  M3 defaults sized to the underlying widget colours, and the M3 defaults
  themselves are designed to meet AA against their host palette.

---

## Where everything lives

| Concern | File |
|---|---|
| Color primitives | `app/src/main/res/values/colors_primitives.xml` |
| Color semantics | `app/src/main/res/values/colors.xml` |
| Color state-list registry | `app/src/main/res/values/colors_state.xml` |
| Color state-list selectors | `app/src/main/res/color/*.xml` |
| Dimension primitives | `app/src/main/res/values/dimens_primitives.xml` |
| Dimension semantics | `app/src/main/res/values/dimens.xml` |
| Elevation tokens | `app/src/main/res/values/elevations.xml` |
| Motion durations | `app/src/main/res/values/motion.xml` |
| Motion interpolators | `app/src/main/res/interpolator/*.xml` |
| TextAppearance styles | `app/src/main/res/values/text_appearance.xml` |
| Widget styles (non-button) | `app/src/main/res/values/styles_widgets.xml` |
| Button styles + themes | `app/src/main/res/values/themes.xml` (+ `values-night/themes.xml` for dark-mode overrides) |
| Layout composites | `app/src/main/res/layout/section_header.xml`, `status_pill.xml`, `card_row_icon_label_value.xml`, `picker_empty_label.xml` |
| Activity extensions | `app/src/main/java/com/tinkernorth/dish/ui/common/ActivityExtensions.kt` |
| Activity transitions | `app/src/main/res/anim/fade_through_enter.xml`, `fade_through_exit.xml` |
| Navigation graph | `app/src/main/res/navigation/nav_graph.xml` |
| Navigation wrapper | `app/src/main/java/com/tinkernorth/dish/ui/common/DishNavigator.kt` |

## Naming

- Layout files: `activity_*` for a full-screen root (CoordinatorLayout,
  carries the low-power overlays) or a scaffold content layout inflated via
  `setScaffoldContent`; `content-free` composites use their role
  (`section_*`, `view_*`, `overlay_*`, `dialog_*`, feature-prefixed rows).
- View ids: semantic names for containers and composites (`cardSupport`,
  `sectionLatency`, `hostList`); a short type prefix only for leaf controls
  where the type is the point (`tvTitle`, `btnApply`, `ivIcon`, `swDirect`).
  Both exist in older layouts; new layouts follow this split.
