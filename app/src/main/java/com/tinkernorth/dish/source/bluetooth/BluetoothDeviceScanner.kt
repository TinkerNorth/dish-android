// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
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

    // The caller decides whether scanning is allowed; the reads below still answer a revoked
    // grant with what they can (no bonded seed, no name, no scan) instead of an exception.
    private fun seedBondedLocked() {
        val adapter = adapterProvider() ?: return
        val bonded =
            try {
                adapter.bondedDevices
            } catch (e: SecurityException) {
                Log.w(TAG, "bonded devices unavailable without BLUETOOTH_CONNECT: ${e.message}")
                null
            } ?: return
        for (device in bonded) {
            val mac = device.address ?: continue
            byMac[mac] = Device(mac, nameOf(device), bonded = true)
        }
    }

    private fun nameOf(device: BluetoothDevice): String? =
        try {
            device.name
        } catch (e: SecurityException) {
            Log.w(TAG, "device name unavailable without BLUETOOTH_CONNECT: ${e.message}")
            null
        }

    private fun startDiscovery(): Boolean {
        val adapter = adapterProvider() ?: return false
        return try {
            adapter.startDiscovery()
        } catch (e: SecurityException) {
            Log.w(TAG, "discovery unavailable without BLUETOOTH_SCAN: ${e.message}")
            false
        }
    }

    private fun cancelDiscovery() {
        val adapter = adapterProvider() ?: return
        try {
            adapter.cancelDiscovery()
        } catch (e: SecurityException) {
            // Nothing of ours is scanning without the grant, so there is nothing to cancel.
            Log.d(TAG, "cancelDiscovery without BLUETOOTH_SCAN: ${e.message}")
        }
    }

    private inner class DiscoveryReceiver : BroadcastReceiver() {
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

    private fun registerReceiverLocked(): BroadcastReceiver {
        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
        val rx = DiscoveryReceiver()
        ContextCompat.registerReceiver(context, rx, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return rx
    }

    private fun onFound(intent: Intent) {
        val device = intentDevice(intent) ?: return
        val mac = device.address ?: return
        val name = nameOf(device)
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

    private fun intentDevice(intent: Intent): BluetoothDevice? =
        IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

    private companion object {
        const val TAG = "BluetoothDeviceScanner"
    }
}
