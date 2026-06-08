// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.view.View
import androidx.activity.SystemBarStyle
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

fun AppCompatActivity.setupDishToolbar(toolbar: Toolbar) {
    setSupportActionBar(toolbar)
    toolbar.setNavigationOnClickListener { finish() }
    wireDonateButton()
}

fun AppCompatActivity.wireDonateButton() {
    findViewById<View>(R.id.btnDonate)?.setOnClickListener { DishNavigator(this).toDonate() }
}

fun AppCompatActivity.attachGamepadHost(
    rootView: View,
    wakeState: WakeStateController,
    gamepadRegistry: PhysicalGamepadRegistry,
    notifications: DishNotifications,
): GamepadActivityHost =
    GamepadActivityHost(this, rootView, wakeState, gamepadRegistry).also {
        it.install(notifications)
    }

// SystemBarStyle.auto flips icon colour with the resolved uiMode; chosen over the theme-level
// windowLightStatusBar / windowLightNavigationBar attrs (API 23 / 27 gated) so the bars track
// AppCompatDelegate.setDefaultNightMode across the full minSdk range.
fun AppCompatActivity.applyDishSystemBars(root: View) {
    val barStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
    enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}

// Pre-34 has no CLOSE override; the OPEN-only fallback is fine since the platform close cut is
// fast. Input overlays opt out — every ms of latency matters there.
fun AppCompatActivity.applyDishActivityTransitions() {
    if (animationsDisabled()) return
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

/**
 * True when the system-wide animator duration scale is 0 — the "remove
 * animations" toggle in Settings → Accessibility (and Developer Options).
 * Material widgets check this internally; the activity-transition path
 * uses fixed-duration anim resources, so we have to gate it ourselves.
 *
 * Defaults to "animations enabled" on any error or unexpected float
 * format (Settings.Global.getFloat throws SettingNotFoundException on a
 * missing key; older OS images sometimes do).
 */
internal fun Activity.animationsDisabled(): Boolean =
    try {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) == 0f
    } catch (_: Settings.SettingNotFoundException) {
        false
    }
