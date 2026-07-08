// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.tinkernorth.dish.composer.WakeStateController
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.overlay.GamepadActivityHost
import com.tinkernorth.dish.source.lowpower.LowPowerSignal
import com.tinkernorth.dish.source.notification.DishNotifications
import javax.inject.Inject

// The rootView passed to installGamepadHost must include @layout/overlay_low_power
// and @layout/overlay_low_power_chip.
abstract class BaseGamepadHostActivity : AppCompatActivity() {
    @Inject lateinit var wakeState: WakeStateController

    @Inject lateinit var gamepadRegistry: PhysicalGamepadRegistry

    @Inject lateinit var notifications: DishNotifications

    @Inject lateinit var lowPowerSignal: LowPowerSignal

    private var gamepadHost: GamepadActivityHost? = null

    protected fun installGamepadHost(rootView: View) {
        gamepadHost = attachGamepadHost(rootView, wakeState, gamepadRegistry, notifications, lowPowerSignal)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = gamepadHost?.dispatchKeyEvent(event) == true || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        gamepadHost?.dispatchGenericMotionEvent(event) == true || super.dispatchGenericMotionEvent(event)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = gamepadHost?.dispatchTouchEvent(ev) == true || super.dispatchTouchEvent(ev)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        gamepadHost?.onWindowFocusChanged(hasFocus)
    }

    override fun onStop() {
        super.onStop()
        gamepadHost?.cancelDimOnStop()
    }
}
