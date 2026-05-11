// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.tinkernorth.dish.data.network.BluetoothGamepadBridge
import com.tinkernorth.dish.data.network.ConnectionForegroundObserver
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class DishApplication : Application() {
    @Inject lateinit var connectionForegroundObserver: ConnectionForegroundObserver

    @Inject lateinit var btRegistry: BluetoothGamepadRegistry

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(connectionForegroundObserver)
        // The native input thread calls into BluetoothGamepadBridge for
        // physical-gamepad reports routed to a BT slot; install the registry
        // here (process-scoped) so it's ready before any input arrives.
        BluetoothGamepadBridge.install(btRegistry)
    }
}
