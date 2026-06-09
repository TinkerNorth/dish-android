// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import com.tinkernorth.dish.core.input.BluetoothGamepad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface BluetoothSessionState {
    data object Idle : BluetoothSessionState

    data class Acquiring(
        val profile: BluetoothGamepad.GamepadProfile,
        val autoConnectMac: String?,
    ) : BluetoothSessionState

    data class Registered(
        val profile: BluetoothGamepad.GamepadProfile,
        val autoConnectMac: String?,
    ) : BluetoothSessionState

    data class Connected(
        val profile: BluetoothGamepad.GamepadProfile,
        val mac: String,
        val name: String?,
    ) : BluetoothSessionState

    data class Failed(
        val message: String,
    ) : BluetoothSessionState
}

class BluetoothHidSession(
    private val proxyFactory: () -> HidProxyClient,
) {
    fun interface Listener {
        fun onStateChange(state: BluetoothSessionState)
    }

    private val lock = Any()
    private var proxy: HidProxyClient? = null
    private var generation: Int = 0
    private val listeners = mutableListOf<Listener>()

    private val _state = MutableStateFlow<BluetoothSessionState>(BluetoothSessionState.Idle)
    val state: StateFlow<BluetoothSessionState> = _state.asStateFlow()

    fun addListener(listener: Listener) {
        synchronized(lock) { listeners += listener }
    }

    fun removeListener(listener: Listener) {
        synchronized(lock) { listeners -= listener }
    }

    fun start(
        profile: BluetoothGamepad.GamepadProfile,
        autoConnectMac: String?,
    ) {
        synchronized(lock) {
            teardownLocked()
            val newProxy = proxyFactory()
            proxy = newProxy
            val myGen = ++generation
            emitLocked(BluetoothSessionState.Acquiring(profile, autoConnectMac))
            newProxy.acquire(eventsFor(myGen))
        }
    }

    fun stop() {
        synchronized(lock) {
            teardownLocked()
            emitLocked(BluetoothSessionState.Idle)
        }
    }

    fun sendReport(report: ByteArray): Boolean {
        val (px, canSend) =
            synchronized(lock) {
                proxy to (_state.value is BluetoothSessionState.Connected)
            }
        return if (canSend) px?.sendReport(report) ?: false else false
    }

    private fun teardownLocked() {
        generation++
        proxy?.unregisterAndRelease()
        proxy = null
    }

    private fun emitLocked(state: BluetoothSessionState) {
        _state.value = state
        val snapshot = listeners.toList()
        snapshot.forEach { it.onStateChange(state) }
    }

    // Events from older proxies (post-restart/release) are dropped by generation check.
    private fun eventsFor(gen: Int) =
        object : HidProxyClient.Events {
            override fun onAcquired() =
                ifCurrent(gen) {
                    val s = _state.value as? BluetoothSessionState.Acquiring ?: return@ifCurrent
                    proxy?.registerApp(s.profile)
                }

            override fun onReleased() =
                ifCurrent(gen) {
                    teardownLocked()
                    emitLocked(BluetoothSessionState.Idle)
                }

            override fun onAppRegistered() =
                ifCurrent(gen) {
                    val s = _state.value as? BluetoothSessionState.Acquiring ?: return@ifCurrent
                    emitLocked(BluetoothSessionState.Registered(s.profile, s.autoConnectMac))
                    val mac = s.autoConnectMac ?: return@ifCurrent
                    val alreadyConnectedName = proxy?.findOsConnectedHost(mac)
                    if (alreadyConnectedName != null) {
                        emitLocked(BluetoothSessionState.Connected(s.profile, mac, alreadyConnectedName))
                    } else {
                        proxy?.connectToHost(mac)
                    }
                }

            override fun onAppUnregistered() =
                ifCurrent(gen) {
                    teardownLocked()
                    emitLocked(BluetoothSessionState.Idle)
                }

            override fun onHostConnected(
                mac: String,
                name: String?,
            ) = ifCurrent(gen) {
                // Only accept inbound connections from a state that is awaiting one (Registered).
                // When started for a specific host, reject any other mac the OS reports, mirroring
                // the onHostDisconnected mac guard so a stray/foreign host cannot hijack the session.
                val registered = _state.value as? BluetoothSessionState.Registered ?: return@ifCurrent
                val intendedMac = registered.autoConnectMac
                if (intendedMac != null && !intendedMac.equals(mac, ignoreCase = true)) return@ifCurrent
                emitLocked(BluetoothSessionState.Connected(registered.profile, mac, name))
            }

            override fun onHostDisconnected(mac: String) =
                ifCurrent(gen) {
                    val connected = _state.value as? BluetoothSessionState.Connected ?: return@ifCurrent
                    if (!connected.mac.equals(mac, ignoreCase = true)) return@ifCurrent
                    emitLocked(BluetoothSessionState.Registered(connected.profile, autoConnectMac = null))
                }

            override fun onError(message: String) =
                ifCurrent(gen) {
                    teardownLocked()
                    emitLocked(BluetoothSessionState.Failed(message))
                }
        }

    private inline fun ifCurrent(
        gen: Int,
        block: () -> Unit,
    ) {
        synchronized(lock) { if (gen == generation) block() }
    }
}
