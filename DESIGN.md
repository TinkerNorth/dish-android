# dish-android — Design tokens

Theme values are split across:
- [app/src/main/res/values/colors.xml](app/src/main/res/values/colors.xml) — **light-mode** semantic tokens.
- [app/src/main/res/values-night/colors.xml](app/src/main/res/values-night/colors.xml) — **dark-mode** semantic tokens (overrides the light file when night qualifier wins).
- [app/src/main/res/values/colors_primitives.xml](app/src/main/res/values/colors_primitives.xml) — the shared raw palette consumed by both modes.
- [app/src/main/res/values/themes.xml](app/src/main/res/values/themes.xml) — the single Theme.Dish definition (DayNight-parented; one file, both modes).

The user's in-app Appearance choice (Settings → Appearance: System / Light /
Dark) flips Android's night-mode qualifier via `AppCompatDelegate.setDefaultNightMode`,
which switches resource resolution between the two `colors.xml` files at runtime.
See [`ThemePreferenceStore`](app/src/main/java/com/tinkernorth/dish/source/store/ThemePreferenceStore.kt).

Token names follow the cross-repo schema documented in
`d:\TinkerNorth\BRAND.md` (TinkerNorth design system). When updating a value,
keep it in sync with the matching token in dish-mac, dish-linux, and the
Satellite local web UI.

## Available color tokens

Both modes share the same semantic names. The values below show the light /
dark resolved pair for each role. The brand cyan (`colorPrimary` /
`colorPrimaryDark` / `colorPrimaryMid`) and the severity tones are mode-neutral.

| Token | Light value | Dark value | Semantic role |
|---|---|---|---|
| `@color/colorBackground` | `#F2F4F8` | `#060818` | Body / window background |
| `@color/colorSurface` | `#FFFFFF` | `#0C1027` | Card / raised panel |
| `@color/colorSurfaceDim` | `#E5E9F1` | `#131A3A` | Recessed / empty state |
| `@color/colorPrimary` | `#4FE3FF` | `#4FE3FF` | Main accent — cyan (`--tn-signal`) |
| `@color/colorPrimaryMid` | `#2C93AD` | `#2C93AD` | Aliased to PrimaryDark |
| `@color/colorPrimaryDark` | `#2C93AD` | `#2C93AD` | Pressed / disabled primary |
| `@color/colorOnPrimary` | `#060818` | `#060818` | Text/icon on top of primary |
| `@color/colorOnSurface` | `#060818` | `#E6ECFF` | Body text on surface |
| `@color/colorMuted` | `#6E7894` | `#93A0C8` | Secondary text |
| `@color/colorOutline` | `#2E4FE3FF` | `#2E4FE3FF` | Borders — cyan @ ~18% alpha |
| `@color/colorCardStroke` | `#1F4FE3FF` | `#1F4FE3FF` | Primary @ ~12% alpha for card borders |
| `@color/colorSuccess` | `#22C55E` | `#22C55E` | Status — success |
| `@color/colorError` | `#E74C3C` | `#E74C3C` | Status — error |
| `@color/colorWarning` | `#F59E0B` | `#F59E0B` | Status — warning |
| `@color/colorOverlayScrim` | `#CC000000` | `#CC000000` | Black @ ~80% alpha for full-screen dim overlays |

Palette: **cyan / deep-space** — mirrors dish-website. Status colors
(`Success` / `Error` / `Warning`) are deliberately the same across both
the cyan (marketing/Dish) and amber (legacy Satellite) palettes.

`@color/black` and `@color/white` exist for legacy references only — prefer
the semantic tokens above.

## Outliers — illustration palette (intentional, do not migrate)

The following drawables contain hardcoded hex values **on purpose**: they
represent third-party brand colors (PlayStation / Xbox button colors). These
are illustration assets, not theme assets, and should remain literal.

- [app/src/main/res/drawable/ctrl_playstation.xml](app/src/main/res/drawable/ctrl_playstation.xml)
  — `#003087` (PlayStation blue), `#7BC8A4` (triangle green), `#E8555D`
  (circle red), `#C8A2D4` (square purple), `#6B9FD4` (touchpad outline)
- `app/src/main/res/drawable/ic_gp_ps_*.xml` — PlayStation button glyphs
  (`#FFFF6666` circle, `#FF7C66E8` cross, `#FFFF69F8` square, `#FF40E2A0`
  triangle, `#FFE73246` D-pad accent)
- `app/src/main/res/drawable/ic_gp_xbox_*.xml` — Xbox button glyphs
  (`#FF7DB700` A green, `#FFEF4E29` B red, `#FF009FEB` X blue, `#FFFEB504`
  Y yellow, `#FFE73246` D-pad accent)
- `app/src/main/res/drawable/ic_chevron_down.xml`,
  `ic_gamepad.xml`, `ic_gamepad_virtual.xml`, `ic_open_gamepad.xml` —
  white-fill UI icons; tinted at usage site if a themed color is desired.

These should not be replaced with theme tokens — they are not on the design
system's surface.

## Recent migrations

- `#CC000000` in [overlay_low_power.xml](app/src/main/res/layout/overlay_low_power.xml)
  → `@color/colorOverlayScrim`
- `@color/colorBackground` in [ic_launcher_background.xml](app/src/main/res/drawable/ic_launcher_background.xml)
  → `@color/navy_900` (pinned to a fixed primitive — see file header; the
  semantic token resolves to the light cloud surface in day mode and would
  render the launcher icon nearly invisible against light home screens).
- Day/night colour split: `values/colors.xml` is now the light-mode
  palette; `values-night/colors.xml` is the dark-mode override; the
  redundant `values-night/themes.xml` was removed (the single
  `values/themes.xml` is shared across both modes and references the
  colour tokens that resolve per-qualifier).
