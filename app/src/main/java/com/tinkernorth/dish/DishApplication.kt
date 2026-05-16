// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.tinkernorth.dish.data.network.BluetoothGamepadBridge
import com.tinkernorth.dish.data.network.ConnectionForegroundObserver
import com.tinkernorth.dish.data.network.PhysicalBatterySource
import com.tinkernorth.dish.data.network.PhysicalSlotBindingObserver
import com.tinkernorth.dish.data.network.RumbleBridge
import com.tinkernorth.dish.data.network.WakeStateController
import com.tinkernorth.dish.data.repository.PhysicalGamepadRegistry
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class DishApplication : Application() {
    @Inject lateinit var connectionForegroundObserver: ConnectionForegroundObserver

    @Inject lateinit var physicalSlotBindingObserver: PhysicalSlotBindingObserver

    @Inject lateinit var physicalBatterySource: PhysicalBatterySource

    @Inject lateinit var physicalGamepadRegistry: PhysicalGamepadRegistry

    @Inject lateinit var wakeStateController: WakeStateController

    @Inject lateinit var btRegistry: BluetoothGamepadRegistry

    override fun onCreate() {
        super.onCreate()
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(connectionForegroundObserver)
        // Physical-gamepad presence and the resulting native-pipeline bindings
        // both live at process scope so they survive the MainActivity →
        // GamepadOverlayActivity handoff (without this, the overlay would
        // arrive with every per-device binding already cleared).
        physicalGamepadRegistry.install()
        lifecycle.addObserver(physicalSlotBindingObserver)
        // Physical-pad battery reporting (Task 1.2) is also process-scoped so
        // it keeps polling across the dashboard → overlay hand-off, exactly
        // like the slot bindings above. It reports a wireless pad's own
        // battery, or the phone's battery for a USB-wired / batteryless pad.
        lifecycle.addObserver(physicalBatterySource)
        // Same handoff hazard: the partial wake lock and the "keep the screen
        // on" decision used to live on MainActivity and got torn down the
        // moment the gamepad overlay covered it. Hoisting to process scope
        // means both activities just observe shouldKeepScreenOn and toggle
        // the per-window flag.
        lifecycle.addObserver(wakeStateController)
        // The native input thread calls into BluetoothGamepadBridge for
        // physical-gamepad reports routed to a BT slot; install the registry
        // here (process-scoped) so it's ready before any input arrives.
        BluetoothGamepadBridge.install(btRegistry)
        // Rumble flows back from the satellite (game → virtual pad → wire).
        // RumbleBridge owns the phone's VibratorManager / Vibrator and is the
        // single dispatch target for satellite_jni.cpp::receiveAck. Routed
        // unconditionally to the device — no physical-controller fallback,
        // intentionally — see RumbleBridge.kt for the design rationale.
        RumbleBridge.install(this)
    }
}
