# Dish Android — Design System

A rigid, two-tier token system plus a small library of `TextAppearance` /
`Widget` / composite-layout / activity-extension building blocks. Every UI
attribute that repeats in the app reaches its callsite through one of the
names enumerated below.

If a value is missing from this document, it is either deliberately one-off
(documented at the bottom of this file) or new drift to clean up — open the
file it lives in, give it a name here, and route the callsite through it.

---

## Token Tiers

Two layers, every time:

1. **Primitives** — raw values (hex, dp, sp, ms). Lives in `*_primitives.xml`.
   Never referenced directly from layouts, drawables, or Kotlin.
2. **Semantic** — names by role (`colorPrimary`, `spacing_md`, `card_padding`,
   `corner_card`, `text_label`, `elevation_floating`, `motion_duration_short`).
   The only names callsites use.

Why the indirection: a future redesign that wants `spacing_md` to be 12dp
instead of 8dp repoints the semantic name to a different primitive without
renaming every callsite. The primitive scale is value-encoded (`size_8` is
always 8dp), so a stale grep against the literal number still surfaces the
token.

---

## Tokens

### Colors → `values/colors_primitives.xml` + `values/colors.xml`

**Primitives** (raw palette, never referenced from callsites):

| Token | Hex | Notes |
|---|---|---|
| `signal_cyan_500` | `#FF4FE3FF` | brand accent (full saturation) |
| `signal_cyan_700` | `#FF2C93AD` | filled-button bg, primary-dim |
| `signal_cyan_100` | `#0F4FE3FF` | 6% alpha — subtle surface tint |
| `signal_cyan_200` | `#1F4FE3FF` | 12% alpha — card strokes |
| `signal_cyan_300` | `#2E4FE3FF` | 18% alpha — outlines |
| `navy_900` | `#FF060818` | app background |
| `navy_800` | `#FF0C1027` | elevated surface (cards, dialogs) |
| `navy_700` | `#FF131A3A` | empty-state surface variant |
| `slate_300` | `#FF93A0C8` | muted text + secondary chrome |
| `slate_300_a40` | `#6693A0C8` | 40% alpha — disabled affordances |
| `paper_50` | `#FFE6ECFF` | high-contrast on-surface text |
| `paper_50_a40` | `#66E6ECFF` | 40% alpha — disabled-state text |
| `green_500` | `#FF22C55E` | success severity |
| `red_500` | `#FFE74C3C` | error severity |
| `amber_500` | `#FFF59E0B` | warning severity |
| `ink_900` | `#FF000000` | pure black |
| `ink_900_a80` | `#CC000000` | ~80% alpha scrim |
| `paper_pure` | `#FFFFFFFF` | opaque white |

**Semantic** (what layouts and Kotlin reference):

| Token | Resolves to | Role |
|---|---|---|
| `colorBackground` | `navy_900` | app + window + status/nav bars |
| `colorSurface` | `navy_800` | cards, dialogs |
| `colorOnPrimary` | `navy_900` | text on cyan fills |
| `colorOnSurface` | `paper_50` | primary text |
| `colorPrimary` | `signal_cyan_500` | brand accent |
| `colorPrimaryDark` | `signal_cyan_700` | filled-button bg |
| `colorPrimaryMid` | `signal_cyan_700` | secondary accent |
| `colorMuted` | `slate_300` | secondary text + chrome |
| `colorOutline` | `signal_cyan_300` | component borders (18%) |
| `colorCardStroke` | `signal_cyan_200` | card stroke (12%) |
| `colorSurfaceTint` | `signal_cyan_100` | subtle fills (6%) |
| `colorSurfaceDim` | `navy_700` | empty-state surfaces |
| `colorSuccess` / `colorError` / `colorWarning` | severity primitives | status |
| `colorRail_info / _success / _warn / _error` | severity primitives | notification leading-rail |
| `colorDisabled` | `slate_300_a40` | disabled icons + chips |
| `colorOverlayScrim` | `ink_900_a80` | low-power dim |
| `black` / `white` | `ink_900` / `paper_pure` | absolutes |

### State-list colors → `values/colors_state.xml` + `color/*.xml`

Android requires `<selector>` color resources to live in `res/color/`. The
values file holds the registry index + any state-list-only semantic aliases.

| Selector (`res/color/`) | Default → State |
|---|---|
| `chip_pickable_text.xml` | `colorOnSurface` → `state_selected` `colorOnPrimary` |
| `text_disabled.xml` | `colorOnSurface` → `state_enabled=false` `colorTextDisabled` |
| `icon_interactive.xml` | `colorPrimary` → `state_enabled=false` `colorMuted` |
| `icon_secondary.xml` | always `colorMuted` (selector-shaped for naming clarity) |

**State-list aliases** in `values/colors_state.xml`:

- `colorTextDisabled` → `paper_50_a40` (the on-surface tone at 40% alpha that
  `text_disabled.xml` uses; aliased so a future "subdued caption" surface
  reaches the same token).

### Spacing / sizing → `values/dimens_primitives.xml` + `values/dimens.xml`

**Primitives** (value-encoded dp/sp):

- dp scale: `size_1, _2, _3, _4, _6, _8, _10, _11, _12, _14, _16, _18, _20,
  _22, _24, _28, _32, _36, _40, _56, _64, _280` (literal dp value in name).
  `0dp` is intentionally NOT here — it is reserved as the idiomatic Android
  "weight-distributed child" sentinel.
- sp scale: `text_10` through `text_24`, plus `text_64` (hero clock).

**Semantic** (grouped by section in `dimens.xml`):

| Group | Names |
|---|---|
| spacing T-shirt scale | `spacing_xs` (4) → `spacing_6xl` (24) in 10 steps |
| strokes + corners | `border_thin`, `border_rail`, `divider_height`, `corner_chip`, `corner_picker_row`, `corner_button`, `corner_card`, `corner_notification`, `corner_pill` |
| icon sizes | `icon_dot[_lg]`, `icon_battery`, `icon_picker_glyph`, `icon_brand[_sm]`, `icon_section_header`, `icon_card_container`, `icon_card_glyph`, `icon_action`, `icon_row_glyph`, `icon_controller`, `icon_chevron_lg`, `icon_settings_button`, `icon_top_bar_size` |
| touch targets | `touch_target_min`, `button_min_width` |
| card | `card_padding[_horizontal/_bottom]`, `card_margin_bottom` |
| inline picker row | `picker_row_padding`, `picker_row_margin_top`, `picker_section_margin_top`, `picker_section_padding_bottom`, `picker_label_margin_end` |
| chip | `chip_padding_horizontal/_vertical`, `chip_margin_end` |
| dialog / overlay | `dialog_inline_max_width` |
| notification | `notification_corner_radius`, `notification_rail_width`, `notification_elevation`, `notification_padding_horizontal/_vertical`, `notification_text_leading_indent`, `notification_text_title/_body/_action` |
| text sizes (role-named sp) | `text_caption` (10) → `text_hero` (64) — see `text_appearance.xml` for the canonical mapping into TextAppearance styles |

### Elevation → `values/elevations.xml`

Four resting heights. Layouts and styles reference these instead of raw dp
values so a global shadow-depth adjustment is one file.

| Token | Value | Use |
|---|---|---|
| `elevation_none` | 0dp | flat surfaces (cards on a page, list rows) |
| `elevation_raised` | 2dp | subtle lift |
| `elevation_floating` | 6dp | Snackbars, FABs (`notification_elevation` aliases this) |
| `elevation_modal` | 12dp | dialogs, sheets |

### Motion → `values/motion.xml` + `interpolator/*.xml`

**Durations** (millisecond integers — use via
`android:duration="@integer/…"` in AVDs and
`context.resources.getInteger(R.integer.…).toLong()` from Kotlin):

| Token | Value | Use |
|---|---|---|
| `motion_duration_instant` | 75 ms | chip press feedback, ripples |
| `motion_duration_short` | 150 ms | button state, tooltip fade |
| `motion_duration_medium` | 300 ms | Snackbar reveal, dialog enter/exit |
| `motion_duration_long` | 500 ms | onboarding pulses |
| `motion_duration_spinner` | 1200 ms | DishLoaders spinner + dots loop |
| `motion_duration_bar` | 1400 ms | DishLoaders bar loop (intentional different cadence) |
| `motion_duration_battery` | 3600 ms | charging-battery AVD full ramp |

The bottom three are **named, off-scale** — tied to a specific asset's design
rhythm. Don't repurpose them for other UI.

**Interpolators** (referenceable from AVDs + ObjectAnimators):

| File | Curve | Use |
|---|---|---|
| `dish_ease_standard.xml` | cubic-bezier(0.4, 0.0, 0.2, 1.0) | default UI transitions |
| `dish_ease_in.xml` | cubic-bezier(0.4, 0.0, 1.0, 1.0) | element exit (slow start, fast finish) |
| `dish_ease_out.xml` | cubic-bezier(0.0, 0.0, 0.2, 1.0) | element entry (fast start, slow finish) |
| `dish_ease_linear.xml` | identity (linear) | indeterminate loops |

### Typography → `values/text_appearance.xml`

14 canonical `TextAppearance.Dish.*` styles. Every TextView in
`res/layout/*.xml` adopts one via `style="@style/…"` (NOT
`android:textAppearance` — the `style="…"` form applies every attribute
including `lineSpacingMultiplier`; `android:textAppearance` silently drops
line spacing).

Letter-spacing canon (single source of truth, do not drift):
- `0.04` — Hero (giant numerals).
- `0.12` — Label / Label.Accent / Display.Large (eyebrows + brand wordmark).
- `0.18` — Caption (small mono micro-eyebrow / footer).
- Everything else is callsite-specific override (the low-power HUD treatments
  ride on top of Label/Body styles).

| Style | size | color | family | weight | spacing | line × |
|---|---|---|---|---|---|---|
| `Caption` | 10sp | muted | monospace | normal | 0.18 | — |
| `Label` | 11sp | muted | monospace | normal | 0.12 | — |
| `Label.Accent` | 11sp | primary | monospace | normal | 0.12 | — |
| `Label.Plain` | 11sp | muted | sans-serif | normal | — | — |
| `BodySmall` | 12sp | muted | sans-serif | normal | — | — |
| `Body` | 13sp | muted | sans-serif | normal | — | 1.35 |
| `Body.Mono` | 13sp | on-surface | monospace | normal | — | — |
| `BodyLarge` | 14sp | muted | monospace | normal | — | — |
| `BodyLarge.Bold` | 14sp | on-surface | sans-serif | bold | — | — |
| `Title` | 15sp | on-surface | sans-serif-medium | normal | — | 1.3 |
| `Title.Large` | 18sp | on-surface | sans-serif | bold | — | — |
| `Display` | 22sp | primary | monospace | bold | — | — |
| `Display.Large` | 24sp | primary | sans-serif | bold | 0.12 | — |
| `Hero` | 64sp | primary | monospace | bold | 0.04 | — |

Every style has `parent=""` so the attribute list is fully enumerated — no
surprise inheritance from Material/AppCompat text-appearance defaults.

---

## Components

### Theme

| Style | Where | Role |
|---|---|---|
| `Theme.Dish` | `values/themes.xml`, `values-night/themes.xml` | App + activity theme. Wires Material color slots to `colorPrimary` / `colorOnSurface` / etc., sets `windowBackground` + `statusBarColor` + `navigationBarColor` to `colorBackground`, and pins `materialButtonStyle = Widget.Dish.Button` as the global default. |
| `Theme.Dish.Dialog` | `values/themes.xml` | Pair-PIN dialog overlay. Transparent `windowBackground` (so `bg_pill`'s rounded corners + cyan outline are the only visible shape), 60% backgroundDim, no title. |

### `Widget.Dish.*` styles

The component-level closure for repeated Material widget configurations.
Every callsite of these widgets in `res/layout/*.xml` references one of them
via `style="@style/Widget.Dish.X"`.

**Buttons** (in `values/themes.xml`, next to `Theme.Dish` because the theme
wires `materialButtonStyle = Widget.Dish.Button` as the global default):

| Style | Parent | Adds |
|---|---|---|
| `Widget.Dish.Button` | `Widget.MaterialComponents.Button` | filled: `colorPrimaryDark` bg, `colorOnSurface` text, uppercase, 0.04 spacing, `corner_button`, zero insets, `colorPrimaryMid` ripple |
| `Widget.Dish.Button.Outlined` | `Widget.MaterialComponents.Button.OutlinedButton` | transparent bg, `colorOnSurface` text, `colorOutline` stroke (`border_thin`), uppercase, 0.04 spacing, `corner_button`, zero insets, `colorPrimaryDark` ripple |

**Everything else** (in `values/styles_widgets.xml`):

| Style | Parent | Adds | When to use |
|---|---|---|---|
| `Widget.Dish.Card` | `Widget.MaterialComponents.CardView` | `colorSurface` bg, `corner_card` radius, `elevation_none`, `colorCardStroke` stroke (`border_thin`) | every `MaterialCardView` in the app |
| `Widget.Dish.Card.Telemetry` | (inherits Card) | `corner_picker_row` radius override | telemetry card inside the slot's expanded body (matches the picker-row geometry next to it) |
| `Widget.Dish.Toolbar` | `Widget.MaterialComponents.Toolbar` | `colorBackground` bg, `colorOnSurface` title, `ic_chevron_left` nav icon, action-bar height | both Activity toolbars (Settings + Connections) |
| `Widget.Dish.StatusDot.Small` | `""` | `icon_dot` size + `dot_circle` background | overlay status indicators |
| `Widget.Dish.StatusDot.Large` | `""` | `icon_dot_lg` size + `dot_circle` background | slot-card / row status indicators |
| `Widget.Dish.EmptyStateText` | `""` | `match_parent` width, `spacing_xs` top margin, `TextAppearance.Dish.BodySmall` | `picker_empty_label.xml` (single callsite; other empty-state TextViews have per-context padding and adopt `BodySmall` directly) |
| `Widget.Dish.Switch` | `Widget.MaterialComponents.CompoundButton.Switch` | hides side labels (`textOn`/`textOff` empty, `showText=false`); thumb + track colors come from the Material theme | every `SwitchMaterial` in the app |
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
| `section_header.xml` | dashboard CONTROLLERS, Connections SATELLITES + BLUETOOTH, Settings DIAGNOSTICS + ABOUT (5) | horizontal row: `iconSection` (Icon.Section, `gone` by default) + `labelSection` (Label.Accent, `weight=1`) + `btnSectionAction` (Button.Outlined, `gone` by default) | per-callsite vertical spacing — applied via the `<include>` tag's `layout_marginTop/Bottom` |
| `status_pill.xml` | gamepad-overlay connection pill, gamepad-overlay motion pill, touchpad-overlay connection pill (3) | `bg_pill` background, 85% alpha, padding 16dp h × 6dp v: `statusPillDot` (StatusDot.Small) + `statusPillLabel` (Label + `colorOnSurface` override for pill contrast) | nothing — pills are uniform across callsites |
| `card_row_icon_label_value.xml` | Settings Crash card, Settings Privacy card (2) | leading 36dp `bg_icon_container` FrameLayout hosting `cardRowIcon` (Icon.Card) + vertical title/subtitle column (`cardRowTitle` = Title, `cardRowSubtitle` = Body) | the `MaterialCardView` wrapper, the outer row's `gravity` (top vs center_vertical), the trailing affordance (Switch vs chevron) |
| `picker_empty_label.xml` | `ControllerAdapter.addLabel` (no-connections empty state in the slot picker) | single TextView with `Widget.Dish.EmptyStateText` style | nothing — adapter only sets `.text` |

`section_header.xml` is single-layout with code-driven content: `<include>`
overrides only support root-level `android:id`/layout_*/`android:visibility`,
so child ids carry canonical names and Activity code sets icon/label/button
content. Eyebrow callsites leave the icon + button at `gone` and only set
the label text; full-section callsites flip visibility + assign content.

`status_pill.xml`'s default text is `overlay_status_streaming` — the motion
pill's first frame would briefly read "STREAMING" instead of "MOTION: NO
GYROSCOPE", but `repaintFrom(currentMotionPaint())` runs synchronously in
`GamepadOverlayActivity.onCreate` before the first frame is presented.

`card_row_icon_label_value.xml` does NOT cover `item_controller.xml`'s
slot-card header — the slot card uses a bare 36dp tinted ImageView (no
`bg_icon_container`), a `BodySmall` subtitle, and a 5-sibling trailing zone.
Forcing it into the composite would bloat the composite with controller-
specific visibility-gone defaults.

---

## Activity Scaffolding

Three extension functions on `AppCompatActivity` + one optional interface,
all in `app/src/main/java/com/tinkernorth/dish/ui/common/ActivityExtensions.kt`.

| Symbol | Use |
|---|---|
| `fun setupDishToolbar(toolbar: Toolbar)` | Installs `toolbar` as the support action bar and wires its navigation icon to `finish()`. Toolbar should already carry `style="@style/Widget.Dish.Toolbar"` from its layout. Call after `setContentView`. |
| `fun attachGamepadHost(rootView, wakeState, gamepadRegistry, notifications): GamepadActivityHost` | Constructs + installs the gamepad host against `rootView`. Assign the returned host to a `lateinit var` on the activity so its lifecycle dispatches (`onStop`, `dispatchKeyEvent`, etc.) can reach it. Call after `setContentView`. |
| `fun applyDishSystemBars()` | Documented no-op. Status- and navigation-bar colors are theme-owned by `Theme.Dish` in both `values/themes.xml` and `values-night/themes.xml`; this extension is the call-site marker that "the bars on this screen are intentional" and a future-proofing seam if per-screen edge-to-edge or contrast adjustments ever land. |
| `interface GamepadHostLifecycle { gamepadHost; tearDownDimOnStop() }` | Opt-in interface for activities that hold a host and need the shared `onStop { gamepadHost.cancelDimOnStop() }` teardown. Defined but not yet wired — the three activities each call `gamepadHost.cancelDimOnStop()` directly. If teardown ever fans out beyond that one line, this is the seam. |

The input overlays (`GamepadOverlayActivity` + `TouchpadOverlayActivity`)
deliberately go through `BaseInputOverlayActivity`'s inherited scaffolding,
not these extensions — the immersive full-screen wiring (`hideSystemBars()`,
the `lateinit gamepadHost` field, the dispatch overrides, the 250 Hz resend
loop) is a bigger interface than these three pieces centralise.

---

## Rules

### When to add a new token

1. Is the value unique to one callsite? **Stay inline** and accept the
   literal — adding a one-callsite token is more drift than it prevents.
2. Is it shared across 2+ callsites? Add a primitive + semantic ref.
3. Is it a per-component aspect (`card_padding`, `chip_corner_radius`,
   `notification_rail_width`)? Add a component-scoped semantic name in the
   appropriate `dimens.xml` section — changing it moves all callsites in
   lockstep without affecting unrelated rows.
4. Always primitive **then** semantic. Never reference a primitive from a
   callsite — the indirection is what lets a future redesign repoint without
   renaming.

### When to add a new style

- **2+ identical attribute combinations on a TextView** → adopt or add a
  `TextAppearance.Dish.*` style. The 14 existing styles should cover almost
  every case — fix the callsite to one of them before adding #15.
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
  `android:visibility` — child ids are canonical, and callsites bind to them
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
3. **Only override inline** if the button needs a callsite-specific size or
   layout param (`minWidth`, `layout_weight`, `textAllCaps="false"` for a
   sentence-case button). Everything else inherits from the style.

A new TextView in the app:

1. **Pick the matching role** from the 14 `TextAppearance.Dish.*` styles —
   eyebrow → Label / Label.Accent, body copy → Body / BodySmall, card title
   → Title, big numeral → Display. If nothing fits, the callsite is the
   wrong shape — fix the layout, don't add a 15th style.
2. **Set `style="@style/TextAppearance.Dish.X"`** on the TextView, NOT
   `android:textAppearance="@style/…"`. The `style="…"` form applies every
   attribute including `lineSpacingMultiplier`.
3. **Don't add inline `android:textSize`/`fontFamily`/`textColor`/
   `letterSpacing`** — the style owns these. If the callsite genuinely needs
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
  are the exception — those use `colorPrimary` / `colorMuted` / `icon_*`
  selectors at the callsite.
- **Custom-view paint configuration**. `DishLoaders.kt` and
  `TouchpadSurfaceView.kt` set up their own `Paint` objects programmatically.
  Hex literals there are intentional — they're internal to the view's drawing
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
- **Per-callsite state-list adoptions for never-disabled views**. The
  `text_disabled.xml` / `icon_interactive.xml` selectors are kept in
  `res/color/` as opt-in tokens. Layout sites adopt them only when Kotlin
  actually toggles `isEnabled` on the view; static branding glyphs and
  overlay-contrast text keep their literal colors. The chevron in
  `item_controller.xml` adopts `icon_secondary` (always-muted) for naming
  clarity even though the value is the same as inline `colorMuted`.
- **`scaffold_toolbar_scroll.xml`**. The Phase 4 spec offered this as a
  deferred composite shared between Settings and Connections. Their bodies
  have diverged enough — Connections has an inter-section divider + a
  low-power overlay include, Settings doesn't; their NestedScrollView
  paddings differ — that the composite would either bloat with conditional
  flags or strip out the screen-specific geometry. The two toolbars already
  share `Widget.Dish.Toolbar`; that's the shared part. The rest is
  intentional per-screen geometry.

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
