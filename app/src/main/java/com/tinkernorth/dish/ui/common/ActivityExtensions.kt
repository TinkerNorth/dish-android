// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.WakeStateController
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.overlay.GamepadActivityHost
import com.tinkernorth.dish.source.notification.DishNotifications

// Activity-level glue for the Dish design system. The three extension
// functions here capture the boilerplate every Activity used to inline:
//
//   1. setupDishToolbar — setSupportActionBar + back-on-nav-click. Every
//      screen that carries Widget.Dish.Toolbar wires it the same way.
//   2. attachGamepadHost — construct + install GamepadActivityHost. The
//      three production hosts (dashboard, connections, and the input
//      overlays via BaseInputOverlayActivity) all need identical
//      `GamepadActivityHost(this, root, wakeState, gamepadRegistry)
//      .also { it.install(notifications) }` wiring.
//   3. applyDishSystemBars — switches the activity into edge-to-edge mode
//      and applies system-bar insets as root padding. Replaces the
//      deprecated `fitsSystemWindows="true"` layout attribute and is the
//      single source of truth for chrome screens' bar handling.
//
// The input overlays (GamepadOverlayActivity + TouchpadOverlayActivity)
// deliberately go through BaseInputOverlayActivity's inherited wiring
// rather than these extensions, because the immersive full-screen
// scaffolding (hideSystemBars(), the lateinit gamepadHost field, the
// dispatch overrides) is a bigger interface than the three pieces this
// file centralises.

/**
 * Install [toolbar] as the activity's support action bar and wire its
 * navigation icon to [AppCompatActivity.finish]. The toolbar should already
 * carry `style="@style/Widget.Dish.Toolbar"` from its layout — this function
 * doesn't restyle, it only owns the lifecycle wiring.
 *
 * Call after [AppCompatActivity.setContentView] so [toolbar] is attached.
 */
fun AppCompatActivity.setupDishToolbar(toolbar: Toolbar) {
    setSupportActionBar(toolbar)
    toolbar.setNavigationOnClickListener { finish() }
}

/**
 * Construct and install a [GamepadActivityHost] against [rootView] (the
 * inflated activity root that includes `@layout/overlay_low_power`). The
 * returned host should be assigned to a `lateinit var` on the activity so
 * its lifecycle forwards (`onStop`, `dispatchKeyEvent`,
 * `dispatchGenericMotionEvent`, `dispatchTouchEvent`, `onWindowFocusChanged`)
 * can reach it.
 *
 * The [notifications] argument is forwarded to [GamepadActivityHost.install]
 * so themed Snackbars anchor against the host's root and re-anchor above the
 * low-power countdown pill when it's visible.
 *
 * Call after [AppCompatActivity.setContentView].
 */
fun AppCompatActivity.attachGamepadHost(
    rootView: View,
    wakeState: WakeStateController,
    gamepadRegistry: PhysicalGamepadRegistry,
    notifications: DishNotifications,
): GamepadActivityHost =
    GamepadActivityHost(this, rootView, wakeState, gamepadRegistry).also {
        it.install(notifications)
    }

/**
 * Switch the activity into edge-to-edge mode and wire system-bar insets as
 * padding on [root]. Required for `targetSdk` ≥ 35 (Android 15 enforces
 * edge-to-edge regardless of the deprecated `fitsSystemWindows` flag).
 *
 * The default [enableEdgeToEdge] call uses [androidx.activity.SystemBarStyle.auto]
 * for both bars — on the dark Dish palette that resolves to a dark
 * transparent scrim, which keeps the existing visual identity while
 * satisfying the platform contract.
 *
 * [root] receives `systemBars` insets as padding so content doesn't draw
 * under the status / navigation bars. Callers should pass the binding root
 * (typically the [androidx.coordinatorlayout.widget.CoordinatorLayout]
 * wrapping the screen content). Scrolling children that want their content
 * to extend past the inset should still use `clipToPadding="false"` and
 * apply their own bottom padding to the inset value if needed.
 *
 * The input overlays handle their own immersive bar treatment in
 * [com.tinkernorth.dish.ui.main.BaseInputOverlayActivity]; this extension
 * is for the standard chrome screens (dashboard, connections, settings).
 */
fun AppCompatActivity.applyDishSystemBars(root: View) {
    enableEdgeToEdge()
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}

/**
 * Install Dish's fade-through transition for both the OPEN and CLOSE
 * directions of this activity. Read off `fade_through_enter` + `fade_through_exit`
 * which both ride on `motion_duration_medium` (250 ms) and
 * `dish_ease_standard`.
 *
 * Call from `onCreate` (before or after `setContentView` is fine — the
 * platform caches the override and applies it to the next transition).
 *
 * API gating:
 *  - **34+**: uses [Activity.overrideActivityTransition] for both OPEN and
 *    CLOSE; the close transition fires when the activity is finished.
 *  - **24..33**: falls back to the deprecated [Activity.overridePendingTransition]
 *    which only replaces the OPEN transition. The close transition uses the
 *    platform default cut on these older releases — acceptable trade-off,
 *    since pre-34 devices are a shrinking minority and the close cut is
 *    fast (not jarring like a misaligned open).
 *
 * The two input overlays (Gamepad / Touchpad) intentionally do NOT call
 * this — they're input surfaces where every millisecond of perceived
 * latency matters, and the standard platform cut delivers the gamepad
 * faster than a 250 ms fade.
 */
fun AppCompatActivity.applyDishActivityTransitions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_OPEN,
            R.anim.fade_through_enter,
            R.anim.fade_through_exit,
        )
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_CLOSE,
            R.anim.fade_through_enter,
            R.anim.fade_through_exit,
        )
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.fade_through_enter, R.anim.fade_through_exit)
    }
}
