// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
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
//   3. applyDishSystemBars — documented no-op. Status- and navigation-bar
//      colouring is owned by Theme.Dish across both day and night resource
//      qualifiers; this extension is the call-site marker that "the bars
//      on this screen are intentional" and a future-proofing seam if
//      per-screen edge-to-edge or contrast adjustments ever land.
//
// The GamepadHostLifecycle interface is the opt-in companion for the
// paired `onStop { gamepadHost.cancelDimOnStop() }` call — see its KDoc.
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
 * No-op marker for the activity's system-bar configuration. Status- and
 * navigation-bar colors are theme-owned (`@color/colorBackground` via
 * `Theme.Dish` in both `values/themes.xml` and `values-night/themes.xml`),
 * so there is nothing to do programmatically — calling this function from
 * an activity's `onCreate` documents the intent and keeps the call site
 * available if a future screen needs per-instance window-insets tuning.
 *
 * The full-screen input overlays handle their own `hideSystemBars()` in
 * [BaseInputOverlayActivity][com.tinkernorth.dish.ui.main.BaseInputOverlayActivity];
 * this extension is for the standard chrome-on screens (dashboard,
 * connections, settings).
 */
@Suppress("UnusedReceiverParameter")
fun AppCompatActivity.applyDishSystemBars() {
    // Theme owns statusBarColor + navigationBarColor; nothing to apply.
}

/**
 * Opt-in interface for activities that hold a [GamepadActivityHost] and
 * need the shared `onStop { gamepadHost.cancelDimOnStop() }` teardown. The
 * activity exposes its host via [gamepadHost], and calls [tearDownDimOnStop]
 * from its `onStop` override.
 *
 * The interface is a documentation seam — there is no base-class
 * inheritance and no kludged behaviour, just a one-line method the host
 * activity forwards to. If a future activity grows additional teardown,
 * the implementation can fan out without touching every existing override.
 */
interface GamepadHostLifecycle {
    val gamepadHost: GamepadActivityHost

    /** Forward from `onStop` after `super.onStop()`. */
    fun tearDownDimOnStop() {
        gamepadHost.cancelDimOnStop()
    }
}
