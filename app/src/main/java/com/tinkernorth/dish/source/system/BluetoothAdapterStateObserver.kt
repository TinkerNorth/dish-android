// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.system

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Live Bluetooth adapter availability. Mirrors `BluetoothAdapter.getState()`. */
enum class BluetoothAdapterState {
    /** No Bluetooth hardware (rare, mostly emulators / tablets). */
    UNSUPPORTED,

    /** Hardware present; adapter currently off. User can turn on from settings. */
    OFF,

    /** Adapter on. */
    ON,
}

/**
 * Process-scoped observer for the Bluetooth adapter's on/off state. The activities
 * use this for two things:
 *
 *  - Render a persistent in-section banner on Connections when the adapter is off,
 *    with a "TURN ON" CTA. Without this the user had to discover their adapter was
 *    off by tapping Add and getting a one-shot toast.
 *  - Live-update on `BluetoothAdapter.ACTION_STATE_CHANGED` so the banner
 *    disappears the moment the user toggles BT on, no manual refresh needed.
 *
 * **Pattern:** [AbstractStateSource]`<BluetoothAdapterState>` — owns
 * a BroadcastReceiver, publishes state, no events.
 */
@Singleton
class BluetoothAdapterStateObserver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AbstractStateSource<BluetoothAdapterState>(BluetoothAdapterState.UNSUPPORTED) {
        @Volatile private var registered = false

        private val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                    refresh()
                }
            }

        init {
            // Seed before any subscriber attaches so `state.value` is correct on first read.
            setState(currentState())
        }

        override fun onStart(owner: LifecycleOwner) {
            if (registered) return
            refresh()
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            registered = true
        }

        override fun onStop(owner: LifecycleOwner) {
            if (!registered) return
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }

        private fun refresh() {
            setState(currentState())
        }

        private fun currentState(): BluetoothAdapterState {
            val adapter = adapter() ?: return BluetoothAdapterState.UNSUPPORTED
            return if (adapter.isEnabled) BluetoothAdapterState.ON else BluetoothAdapterState.OFF
        }

        private fun adapter(): BluetoothAdapter? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            } else {
                @Suppress("DEPRECATION")
                BluetoothAdapter.getDefaultAdapter()
            }
    }
