// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable state of the single HID host session owned by [BluetoothHidSession].
 *
 * Android's HID Device profile caps us at one registered app per process, so
 * the whole connection lifecycle is modelled as a single linear state
 * machine rather than a per-slot object. The [BluetoothGamepadRegistry]
 * layer projects this onto per-slot UI state.
 */
sealed interface SessionState {
    data object Idle : SessionState

    data class Acquiring(
        val profile: BluetoothGamepad.GamepadProfile,
        val autoConnectMac: String?,
    ) : SessionState

    data class Registered(
        val profile: BluetoothGamepad.GamepadProfile,
        val autoConnectMac: String?,
    ) : SessionState

    data class Connected(
        val profile: BluetoothGamepad.GamepadProfile,
        val mac: String,
        val name: String?,
    ) : SessionState

    data class Failed(
        val message: String,
    ) : SessionState
}

/**
 * Process-scoped state machine for the app's Bluetooth HID gamepad session.
 *
 * Responsibilities:
 *   - Own the [HidProxyClient] binding and guarantee a clean release on
 *     every teardown path (restart, stop, framework release, error).
 *   - Translate raw framework events into [SessionState] transitions.
 *   - Gate [sendReport] to only fire in [SessionState.Connected].
 *
 * Not responsible for:
 *   - Mapping sessions to UI slot ids (see [BluetoothGamepadRegistry]).
 *   - Persisting remembered hosts (see [com.tinkernorth.dish.data.network.ConnectionStore]).
 *   - Recovering across app foreground/background (see [BluetoothForegroundObserver]).
 *
 * Thread-safety: every public method and every proxy callback acquires the
 * same monitor. Framework callbacks from Binder threads are safe to interleave
 * with UI-thread calls; the monitor is held for microseconds.
 */
class BluetoothHidSession(
    private val proxyFactory: () -> HidProxyClient,
) {
    /** Synchronous observer for downstream state machines (e.g. [BluetoothGamepadRegistry]). */
    fun interface Listener {
        fun onStateChange(state: SessionState)
    }

    private val lock = Any()
    private var proxy: HidProxyClient? = null
    private var generation: Int = 0
    private val listeners = mutableListOf<Listener>()

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

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
            emitLocked(SessionState.Acquiring(profile, autoConnectMac))
            newProxy.acquire(eventsFor(myGen))
        }
    }

    fun stop() {
        synchronized(lock) {
            teardownLocked()
            emitLocked(SessionState.Idle)
        }
    }

    fun sendReport(report: ByteArray): Boolean {
        val (px, canSend) =
            synchronized(lock) {
                proxy to (_state.value is SessionState.Connected)
            }
        return if (canSend) px?.sendReport(report) ?: false else false
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun teardownLocked() {
        generation++ // invalidates any pending events from the previous proxy
        proxy?.unregisterAndRelease()
        proxy = null
    }

    private fun emitLocked(state: SessionState) {
        _state.value = state
        // Snapshot under lock (listeners is mutable) so late mutation is safe.
        val snapshot = listeners.toList()
        snapshot.forEach { it.onStateChange(state) }
    }

    /**
     * Events delivered by an older proxy (after restart / release) must not
     * mutate the current session's state. Every callback re-checks the
     * generation it was created with; stale calls are silently dropped.
     */
    private fun eventsFor(gen: Int) =
        object : HidProxyClient.Events {
            override fun onAcquired() =
                ifCurrent(gen) {
                    val s = _state.value as? SessionState.Acquiring ?: return@ifCurrent
                    proxy?.registerApp(s.profile)
                }

            override fun onReleased() =
                ifCurrent(gen) {
                    // Framework yanked the proxy: drop everything back to Idle. The
                    // foreground observer is responsible for waking us up again.
                    teardownLocked()
                    emitLocked(SessionState.Idle)
                }

            override fun onAppRegistered() =
                ifCurrent(gen) {
                    val s = _state.value as? SessionState.Acquiring ?: return@ifCurrent
                    emitLocked(SessionState.Registered(s.profile, s.autoConnectMac))
                    val mac = s.autoConnectMac ?: return@ifCurrent
                    val alreadyConnectedName = proxy?.findOsConnectedHost(mac)
                    if (alreadyConnectedName != null) {
                        emitLocked(SessionState.Connected(s.profile, mac, alreadyConnectedName))
                    } else {
                        proxy?.connectToHost(mac)
                    }
                }

            override fun onAppUnregistered() =
                ifCurrent(gen) {
                    teardownLocked()
                    emitLocked(SessionState.Idle)
                }

            override fun onHostConnected(
                mac: String,
                name: String?,
            ) = ifCurrent(gen) {
                val profile = currentProfileLocked() ?: return@ifCurrent
                emitLocked(SessionState.Connected(profile, mac, name))
            }

            override fun onHostDisconnected(mac: String) =
                ifCurrent(gen) {
                    val connected = _state.value as? SessionState.Connected ?: return@ifCurrent
                    if (!connected.mac.equals(mac, ignoreCase = true)) return@ifCurrent
                    emitLocked(SessionState.Registered(connected.profile, autoConnectMac = null))
                }

            override fun onError(message: String) =
                ifCurrent(gen) {
                    teardownLocked()
                    emitLocked(SessionState.Failed(message))
                }
        }

    private fun currentProfileLocked(): BluetoothGamepad.GamepadProfile? =
        when (val s = _state.value) {
            is SessionState.Acquiring -> s.profile
            is SessionState.Registered -> s.profile
            is SessionState.Connected -> s.profile
            else -> null
        }

    private inline fun ifCurrent(
        gen: Int,
        block: () -> Unit,
    ) {
        synchronized(lock) { if (gen == generation) block() }
    }
}
