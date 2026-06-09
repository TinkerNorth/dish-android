// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BluetoothDeviceScanner(
    private val context: Context,
    private val adapterProvider: () -> BluetoothAdapter?,
) {
    data class Device(
        val mac: String,
        val name: String?,
        val bonded: Boolean,
    )

    data class State(
        val devices: List<Device> = emptyList(),
        val scanning: Boolean = false,
    )

    private val lock = Any()
    private val byMac = LinkedHashMap<String, Device>()
    private var receiver: BroadcastReceiver? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun start(canScan: Boolean) {
        synchronized(lock) {
            teardownLocked()
            byMac.clear()
            seedBondedLocked()
            if (!canScan) {
                emitLocked(scanning = false)
                return
            }
            receiver = registerReceiverLocked()
            emitLocked(scanning = startDiscovery())
        }
    }

    fun stop() {
        synchronized(lock) {
            teardownLocked()
            byMac.clear()
            emitLocked(scanning = false)
        }
    }

    private fun teardownLocked() {
        receiver?.let { rx -> runCatching { context.unregisterReceiver(rx) } }
        receiver = null
        cancelDiscovery()
    }

    @SuppressLint("MissingPermission")
    private fun seedBondedLocked() {
        val bonded =
            adapterProvider()?.let { adapter -> runCatching { adapter.bondedDevices }.getOrNull() } ?: return
        for (device in bonded) {
            val mac = device.address ?: continue
            byMac[mac] = Device(mac, runCatching { device.name }.getOrNull(), bonded = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery(): Boolean {
        val adapter = adapterProvider() ?: return false
        return runCatching { adapter.startDiscovery() }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun cancelDiscovery() {
        val adapter = adapterProvider() ?: return
        runCatching { adapter.cancelDiscovery() }
    }

    private fun registerReceiverLocked(): BroadcastReceiver {
        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
        val rx =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> onFound(intent)
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onDiscoveryFinished()
                    }
                }
            }
        ContextCompat.registerReceiver(context, rx, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return rx
    }

    @SuppressLint("MissingPermission")
    private fun onFound(intent: Intent) {
        val device = intentDevice(intent) ?: return
        val mac = device.address ?: return
        val name = runCatching { device.name }.getOrNull()
        synchronized(lock) {
            // Drop broadcasts delivered after stop(): a stale receiver must not resurrect state.
            if (receiver == null) return
            // Bonded entries already carry the richer paired label; don't downgrade them.
            if (byMac[mac]?.bonded == true) return
            byMac[mac] = Device(mac, name, bonded = false)
            emitLocked(scanning = true)
        }
    }

    private fun onDiscoveryFinished() {
        synchronized(lock) {
            if (receiver == null) return
            emitLocked(scanning = false)
        }
    }

    private fun emitLocked(scanning: Boolean) {
        val ordered =
            byMac.values.sortedWith(
                compareByDescending<Device> { it.bonded }.thenBy { it.name ?: it.mac },
            )
        _state.value = State(ordered, scanning)
    }

    @Suppress("DEPRECATION")
    private fun intentDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
