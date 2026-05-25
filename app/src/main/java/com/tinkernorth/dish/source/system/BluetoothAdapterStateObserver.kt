// SPDX-License-Identifier: LGPL-3.0-or-later

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

enum class BluetoothAdapterState {
    UNSUPPORTED,
    OFF,
    ON,
}

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
