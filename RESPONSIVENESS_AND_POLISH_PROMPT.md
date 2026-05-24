# Responsiveness & Polish — Three-Phase Prompt

Three improvements that take the Dish app from "works on a phone in
landscape" to "feels like a 2026 Android app on every form factor": tablet
adaptive layouts, haptic feedback on interactive surfaces, and motion on
activity transitions.

Each phase is self-contained and can ship independently — but they're
grouped because they're the same shape of work (polish that touches every
screen) and benefit from one design review pass at the end. Each phase
ends with a handoff doc and a clean commit on a feature branch.

The design-system foundation (`docs/DESIGN_SYSTEM.md`) is the authoritative
reference for tokens, components, and rules. Every change here must extend
the system (new tokens, new variants), never bypass it.

---

## Pre-flight

Read these before touching any code:

1. `docs/DESIGN_SYSTEM.md` — the token tiers, composite recipes, and the
   "When to add a new X" rules.
2. `app/src/main/AndroidManifest.xml` — current orientation/configChanges
   posture per Activity.
3. `app/src/main/res/values/motion.xml` — existing motion duration tokens
   (the canonical UI speed scale was retired post-Phase-4 — this prompt
   reintroduces it in Phase 3).
4. `app/src/main/res/interpolator/dish_ease_standard.xml` — the only
   interpolator currently in the codebase.

Tooling (Windows PowerShell):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat ktlintFormat ktlintCheck detekt lint testDebugUnitTest
```

At the end of each phase, this command must complete clean with **zero
warnings** (the post-Phase-4 zero-warning baseline is the floor — don't
regress it). Fix warnings the right Android way; never mute them.

---

## Phase 1 — Tablet & foldable adaptive layouts

### Goal

Replace the implicit "single phone form factor" layout posture with
intentional adaptive shapes for tablets (`sw600dp`+) and foldables
(`sw840dp`+ or `w600dp` in landscape). The phone layout remains the
default; tablet/foldable layouts are additive overlays in `values-sw600dp/`,
`layout-sw600dp/`, etc.

### Scope

**Resource forks** to introduce:

- `app/src/main/res/values-sw600dp/dimens.xml` — wider content max-widths,
  a step-up T-shirt spacing scale (everything bumps one size), wider card
  paddings.
- `app/src/main/res/values-sw600dp/text_appearance.xml` (or `dimens.xml`
  text overrides) — text scale step-up only where it improves readability
  at viewing distance (Title/Display roles; do NOT scale Body — readers'
  scan distance is similar across form factors).
- `app/src/main/res/layout-sw600dp/activity_settings.xml` — centered
  content column at `maxWidth=560dp`, equal side gutters.
- `app/src/main/res/layout-sw600dp/activity_connections.xml` — same
  treatment; consider a two-column layout (satellites left, bluetooth
  right) at `w840dp`.
- `app/src/main/res/layout-sw600dp/activity_main.xml` — slot card grid
  becomes a 2-column grid at `w600dp`; gamepad/touchpad overlay launch
  buttons sit on a denser action bar.

**Layout changes** (default phone layout edits):

- Verify every `match_parent` scroll content is wrapped in a `maxWidth`-
  aware container (or rely on the centered-column treatment from the
  sw600dp layout).
- Replace any hardcoded "this layout is phone-shaped" assumptions
  (`paddingHorizontal="@dimen/spacing_5xl"` assumes phone-narrow gutters)
  with breakpoint-aware tokens (`@dimen/content_gutter_horizontal` which
  is 20dp on phone, 48dp on tablet, etc.).

**Manifest**:

- `MainActivity`, `SettingsActivity`, `ConnectionsActivity` — leave
  `screenOrientation` unset (already true) so they rotate to landscape
  on tablets/foldables and use the tablet layout file.
- `GamepadOverlayActivity` + `TouchpadOverlayActivity` — KEEP
  `screenOrientation="landscape"` (overlays are gameplay surfaces; their
  shape is intrinsic).

### Steps

1. Inventory every layout file's content max-width and gutter assumption.
   Produce a short audit listing each layout + what changes at sw600dp.
2. Introduce `@dimen/content_gutter_horizontal` and
   `@dimen/content_max_width` semantic tokens in `dimens.xml`; populate
   defaults (phone) + sw600dp overrides.
3. For each chrome Activity (Main, Settings, Connections), wrap the
   `NestedScrollView`'s content in a centered column at `content_max_width`
   for sw600dp. Pattern:
   ```xml
   <androidx.core.widget.NestedScrollView ...>
     <FrameLayout
       android:layout_width="match_parent"
       android:layout_height="wrap_content">
       <LinearLayout
         android:layout_width="match_parent"
         android:layout_height="wrap_content"
         android:layout_gravity="center_horizontal"
         android:maxWidth="@dimen/content_max_width"
         ... existing content ...>
   ```
4. For `activity_main.xml`, evaluate a 2-column slot card grid at
   `w600dp`+ (use `layout-w600dp/`). Keep the 1-column phone shape default.
5. For `activity_connections.xml`, consider a 2-column section split
   (satellites left, bluetooth right) at `w840dp`. Skip if it complicates
   the existing low-power overlay positioning.
6. Validate on every density bucket via the Layout Validation tool in
   Android Studio (Pixel 4, Pixel 8 Pro, Pixel Fold inner, Pixel Tablet).
7. Update `docs/DESIGN_SYSTEM.md` with the new breakpoint tokens, the
   adaptive-layout rules ("when to add a sw600dp variant"), and a brief
   note in "Where everything lives" about the resource forks.

### Success criteria

- Phone layouts visually unchanged.
- Pixel Tablet (sw800dp): chrome Activities show a centered content column
  with comfortable side gutters, content doesn't stretch full-width.
- Pixel Fold (inner, sw673dp): same; the 2-column slot grid (if shipped)
  fits cleanly.
- Pixel Fold (outer, sw320dp): falls back to phone layout, no overlap.
- Gamepad/Touchpad overlays unchanged (intentionally landscape-fixed).
- `lint` reports no `UnusedResources` from the new tokens.

### Handoff

Write `PHASE_1_HANDOFF.md` listing the new resource files, the dimens
tokens introduced, any layouts that needed restructuring (with the
before/after shape), and any deferred items (layouts you considered for
adaptive split but punted on).

---

## Phase 2 — Haptic feedback on interactive elements

### Goal

Every interactive surface in the app (buttons, switches, chips, slot card
expand/collapse, gamepad button presses, touchpad first-touch) produces a
short haptic on the user action. Uses `View.performHapticFeedback(int)`
with the canonical `HapticFeedbackConstants.*` constants — no custom
patterns, no vibration sequences. The app already declares the `VIBRATE`
permission (for forwarding rumble to gamepads), so no manifest change.

### Scope

**Token introduction** — `app/src/main/res/values/haptics.xml` (new):

```xml
<resources>
  <!-- HAPTIC FEEDBACK CONSTANTS — wrapper layer over
       HapticFeedbackConstants so the codebase reaches for named intents
       rather than raw int constants. The Kotlin side reads these via
       resources.getInteger() so a single source of truth governs which
       physical pulse maps to which UI intent. -->
  <integer name="haptic_button_press">6</integer>          <!-- CONFIRM -->
  <integer name="haptic_chip_toggle">1</integer>            <!-- VIRTUAL_KEY -->
  <integer name="haptic_switch_flip">8</integer>           <!-- TOGGLE_ON -->
  <integer name="haptic_slot_expand">17</integer>          <!-- GESTURE_END -->
  <integer name="haptic_long_press">0</integer>            <!-- LONG_PRESS -->
  <integer name="haptic_touchpad_first_contact">2</integer> <!-- KEYBOARD_TAP -->
</resources>
```

(Verify constant values against the current `HapticFeedbackConstants` —
the integers above are illustrative; pin to the actual constants and
adopt the named constant in code, not the integer literal.)

**Kotlin** — `app/src/main/java/com/tinkernorth/dish/ui/common/HapticExtensions.kt`
(new):

```kotlin
fun View.dishHaptic(@HapticFeedbackConstants.HapticFeedbackType type: Int) {
    performHapticFeedback(type)
}
```

(Or directly inline `performHapticFeedback` at each callsite — pick one
pattern and stick to it; the extension is preferred so the call shape
matches the design-system idiom of "every UI primitive has a Dish
extension".)

**Callsite wiring**:

- `Widget.Dish.Button` — apply a `setOnTouchListener` shim OR a global
  Activity wrapper that catches `MaterialButton` press events. Cleanest:
  override `MaterialButton`'s default haptic via `android:soundEffectsEnabled`
  + `android:hapticFeedbackEnabled="true"` in the style. (Verify M3
  MaterialButton already emits a haptic on tap on Android 13+ — if so,
  this is a no-op except for older API levels.)
- Chip selection (`chip_pickable`) — wire `performHapticFeedback(VIRTUAL_KEY)`
  on the click handler in `ControllerAdapter` where the chip click is
  routed.
- Switch toggles (`switchCrashReporting`, `swMotion`) — wire
  `performHapticFeedback(TOGGLE_ON / TOGGLE_OFF)` in the
  `OnCheckedChangeListener`. (`TOGGLE_ON/OFF` constants are API 30+; gate
  on `Build.VERSION.SDK_INT >= R` and fall back to `VIRTUAL_KEY`.)
- Slot card expand/collapse — in `ControllerAdapter.onBindViewHolder`'s
  click handler, after toggling the expanded state, call
  `performHapticFeedback(GESTURE_END)`.
- `TouchpadSurfaceView.onTouchEvent` ACTION_DOWN — call
  `performHapticFeedback(KEYBOARD_TAP)` ONLY on the first finger that
  claims the pad (not on every multi-touch finger).
- `GamepadTouchView` — already forwards rumble; do NOT add UI haptics
  here. The user is mid-gameplay; ambient haptics would interfere with
  the forwarded controller rumble.

### Steps

1. Audit every interactive callsite in the codebase. List in the handoff.
2. Add the integer tokens + the `dishHaptic` extension.
3. Wire each callsite, batching by file so the diff is reviewable.
4. Test on a real device — emulators don't have a vibrator and the
   behaviour can only be confirmed physically.
5. Update `docs/DESIGN_SYSTEM.md` with a "Haptics" subsection under
   Motion, naming the integer tokens and the intent map.

### Success criteria

- Every button tap produces a confirm haptic.
- Every switch flip produces a toggle haptic.
- Chip selection produces a virtual-key haptic.
- Slot card expand produces a gesture-end haptic.
- Touchpad first finger produces a keyboard-tap haptic; subsequent
  fingers DO NOT.
- Gamepad buttons produce NO UI haptic (gameplay rumble takes
  precedence).
- `lint` clean.

### Handoff

Write `PHASE_2_HANDOFF.md` listing each interactive callsite touched, the
constant used, and any callsites you intentionally skipped (with reason).

---

## Phase 3 — Activity transition motion

### Goal

Replace the default jarring Activity-to-Activity cut with a fade-through
transition tuned to the brand's deep-space identity. Re-introduce the
canonical UI speed scale (`motion_duration_short` / `medium` / `long`)
that was retired post-Phase-4, now with a real consumer. Apply via
`Activity.overrideActivityTransition` (API 34+) with a graceful fallback
to `overridePendingTransition` on older releases.

### Scope

**Re-introduce the speed scale** — `app/src/main/res/values/motion.xml`:

```xml
<!-- UI speed scale — short transitions, medium navigation, long emphasis.
     Activity transitions ride on `motion_duration_medium`; in-screen
     state changes (chip ripple, switch toggle) ride on `motion_duration_short`. -->
<integer name="motion_duration_short">150</integer>
<integer name="motion_duration_medium">250</integer>
<integer name="motion_duration_long">400</integer>
```

(Keep the named asset-specific tokens — `motion_duration_spinner`,
`motion_duration_bar`, `motion_duration_battery`, `motion_duration_bolt_pulse`
— unchanged. They're tied to specific drawables, not the UI speed scale.)

**Animation resources** — `app/src/main/res/anim/` (new folder):

- `fade_through_enter.xml`:
  ```xml
  <set xmlns:android="http://schemas.android.com/apk/res/android"
       android:duration="@integer/motion_duration_medium"
       android:interpolator="@interpolator/dish_ease_standard">
    <alpha android:fromAlpha="0.0" android:toAlpha="1.0" />
  </set>
  ```
- `fade_through_exit.xml`:
  ```xml
  <set xmlns:android="http://schemas.android.com/apk/res/android"
       android:duration="@integer/motion_duration_medium"
       android:interpolator="@interpolator/dish_ease_standard">
    <alpha android:fromAlpha="1.0" android:toAlpha="0.0" />
  </set>
  ```

**Kotlin** — `app/src/main/java/com/tinkernorth/dish/ui/common/ActivityExtensions.kt`:

Add a `fun AppCompatActivity.applyDishOpenTransition()` extension that:
- API ≥34: calls `overrideActivityTransition(OVERRIDE_TRANSITION_OPEN,
  R.anim.fade_through_enter, R.anim.fade_through_exit)`
- API <34: calls `overridePendingTransition(R.anim.fade_through_enter,
  R.anim.fade_through_exit)` after `super.onCreate()` (idiomatic pre-34
  fallback).

Similarly `applyDishCloseTransition()` for the `finish()` direction.

**Callsite wiring** — invoke the extension from every Activity's
`onCreate()` immediately after `setContentView()`. Activities to wire:
`MainActivity`, `SettingsActivity`, `ConnectionsActivity`,
`GamepadOverlayActivity`, `TouchpadOverlayActivity`,
`FatalNativeActivity`.

### Steps

1. Add the speed-scale integers and verify nothing else regresses
   (search for any stale references to the dropped scale tokens).
2. Create `res/anim/fade_through_enter.xml` + `fade_through_exit.xml`.
3. Add the two extensions to `ActivityExtensions.kt`.
4. Wire each Activity. Confirm via a debug build that the transitions
   render at 60fps + look intentional.
5. Update `docs/DESIGN_SYSTEM.md`:
   - Restore the UI speed scale documentation under Motion.
   - Add an "Activity transitions" subsection describing the
     fade-through pattern and the extension.
6. Run all CI, commit, push.

### Success criteria

- Every Activity-to-Activity navigation cross-fades with the
  `dish_ease_standard` interpolator over 250ms.
- Back-stack pop has the same fade-through in reverse.
- No flicker, no double-paint, no visible flash of background colour
  between activities.
- API 23 minimum still works (verify with an API 23 emulator).
- `lint` clean.

### Handoff

Write `PHASE_3_HANDOFF.md` listing each Activity wired, the API gating
strategy used, and any visual edge cases discovered (e.g. low-power
overlay re-flash during transition).

---

## Final commit + push

After all three phases:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat ktlintFormat ktlintCheck detekt lint testDebugUnitTest assembleDebug
```

Three commits (one per phase) so the history reads:
1. `feat(adaptive): tablet/foldable layouts via sw600dp+ resource forks`
2. `feat(haptics): wire HapticFeedbackConstants on every interactive surface`
3. `feat(motion): activity-transition fade-through using the dish speed scale`

Push to a feature branch (`feat/responsiveness-and-polish`) and open
a PR with a summary linking the three handoff docs.
