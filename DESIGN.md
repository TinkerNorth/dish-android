# dish-android — Design tokens

All theme values live in [app/src/main/res/values/colors.xml](app/src/main/res/values/colors.xml)
and [app/src/main/res/values/themes.xml](app/src/main/res/values/themes.xml).

Token names follow the cross-repo schema documented in
`d:\TinkerNorth\BRAND.md` (TinkerNorth design system). When updating a value,
keep it in sync with the matching token in dish-mac, dish-linux, and the
Satellite local web UI.

## Available color tokens

| Token | Semantic role |
|---|---|
| `@color/colorBackground` | Body / window background |
| `@color/colorSurface` | Card / raised panel |
| `@color/colorSurfaceDim` | Recessed / empty state |
| `@color/colorPrimary` | Main accent (amber) |
| `@color/colorPrimaryMid` | Mid-amber for secondary states |
| `@color/colorPrimaryDark` | Pressed / disabled primary |
| `@color/colorOnPrimary` | Text/icon on top of primary |
| `@color/colorOnSurface` | Body text on surface |
| `@color/colorMuted` | Secondary text |
| `@color/colorOutline` | Borders / dividers |
| `@color/colorCardStroke` | Primary @ ~12% alpha for card borders |
| `@color/colorSuccess` | Status — success |
| `@color/colorError` | Status — error |
| `@color/colorWarning` | Status — warning |
| `@color/colorOverlayScrim` | Black @ ~80% alpha for full-screen dim overlays |

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
- `#0D0F12` in [ic_launcher_background.xml](app/src/main/res/drawable/ic_launcher_background.xml)
  → `@color/colorBackground`
