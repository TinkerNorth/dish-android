// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import com.tinkernorth.dish.core.input.BluetoothGamepad
import com.tinkernorth.dish.core.input.buildHidReport
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.repository.RememberedBt
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class BtStaleReason {
    KEY_MISSING,
    BOND_REMOVED,
}

@Singleton
class BluetoothGamepadRegistry
    @Inject
    constructor(
        private val store: ConnectionStore,
        private val session: BluetoothHidSession,
    ) {
        data class SlotState(
            val registered: Boolean = false,
            val connected: Boolean = false,
            val connectedName: String? = null,
            val profileName: String? = null,
            val autoReconnecting: Boolean = false,
            val acquiring: Boolean = false,
        )

        private val lock = Any()
        private var activeConnId: String? = null

        private val _states = MutableStateFlow<Map<String, SlotState>>(emptyMap())
        val states: StateFlow<Map<String, SlotState>> = _states.asStateFlow()

        private val _errors =
            MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val errors: SharedFlow<String> = _errors.asSharedFlow()

        private val _staleBtIds = MutableStateFlow<Map<String, BtStaleReason>>(emptyMap())
        val staleBtIds: StateFlow<Map<String, BtStaleReason>> = _staleBtIds.asStateFlow()

        fun staleReasonFor(id: String): BtStaleReason? = _staleBtIds.value[id]

        fun markStale(
            id: String,
            reason: BtStaleReason,
        ) {
            _staleBtIds.update { current ->
                val prior = current[id]
                if (prior == reason) return@update current
                // KEY_MISSING is more specific. Don't downgrade to BOND_REMOVED.
                if (prior == BtStaleReason.KEY_MISSING && reason == BtStaleReason.BOND_REMOVED) {
                    return@update current
                }
                current + (id to reason)
            }
        }

        fun clearStale(id: String) {
            _staleBtIds.update { if (id in it) it - id else it }
        }

        init {
            session.addListener(::onSessionState)
        }

        fun state(connId: String): SlotState = _states.value[connId] ?: SlotState()

        fun isConnected(connId: String): Boolean = state(connId).connected

        fun isActive(connId: String): Boolean = synchronized(lock) { activeConnId == connId }

        fun isAutoReconnecting(connId: String): Boolean = state(connId).autoReconnecting

        fun start(
            connId: String,
            profile: BluetoothGamepad.GamepadProfile,
            autoConnectMac: String? = null,
        ) {
            synchronized(lock) {
                activeConnId?.takeIf { it != connId }?.let { prior -> _states.update { it - prior } }
                activeConnId = connId
                _states.update { map ->
                    map + (
                        connId to
                            SlotState(
                                profileName = profile.profileName,
                                autoReconnecting = autoConnectMac != null,
                                acquiring = true,
                            )
                    )
                }
            }
            session.start(profile, autoConnectMac)
        }

        fun stop(connId: String) {
            val wasActive =
                synchronized(lock) {
                    val active = activeConnId == connId
                    if (active) activeConnId = null
                    _states.update { it - connId }
                    active
                }
            if (wasActive) session.stop()
        }

        fun stopAll() {
            synchronized(lock) {
                activeConnId = null
                _states.value = emptyMap()
            }
            session.stop()
        }

        fun sendReport(
            connId: String,
            report: ByteArray,
        ) {
            val active = synchronized(lock) { activeConnId == connId }
            if (!active) return
            if (!state(connId).connected) return
            session.sendReport(report)
        }

        fun buildReport(
            connId: String,
            buttons: Int,
            hat: Int,
            lx: Short,
            ly: Short,
            rx: Short,
            ry: Short,
            lt: Int,
            rt: Int,
        ): ByteArray? {
            val active = synchronized(lock) { activeConnId == connId }
            if (!active || !state(connId).connected) return null
            return buildHidReport(buttons, hat, lx, ly, rx, ry, lt, rt)
        }

        fun tryAutoReconnect(connId: String): BluetoothGamepad.GamepadProfile? {
            val entry = store.rememberedBt().firstOrNull { it.id == connId } ?: return null
            val profile =
                BluetoothGamepad.GamepadProfile.entries
                    .firstOrNull { it.profileName == entry.profileName || it.name == entry.profileName }
                    ?: return null
            val current = _states.value[connId]
            // Short-circuit while an acquire is in flight to avoid restarting mid-handshake.
            if (current?.registered == true ||
                current?.connected == true ||
                current?.autoReconnecting == true
            ) {
                return profile
            }
            start(connId, profile, autoConnectMac = entry.mac)
            return profile
        }

        private fun onSessionState(state: BluetoothSessionState) {
            val connId = synchronized(lock) { activeConnId } ?: return
            when (state) {
                is BluetoothSessionState.Idle,
                is BluetoothSessionState.Failed,
                ->
                    _states.update { map ->
                        val cur = map[connId] ?: return@update map
                        map + (
                            connId to
                                cur.copy(
                                    registered = false,
                                    connected = false,
                                    connectedName = null,
                                    autoReconnecting = false,
                                    acquiring = false,
                                )
                        )
                    }
                is BluetoothSessionState.Acquiring -> Unit
                is BluetoothSessionState.Registered ->
                    _states.update { map ->
                        val cur = map[connId] ?: SlotState()
                        map + (
                            connId to
                                cur.copy(
                                    registered = true,
                                    connected = false,
                                    connectedName = null,
                                    acquiring = false,
                                )
                        )
                    }
                is BluetoothSessionState.Connected -> onConnected(connId, state)
            }
            if (state is BluetoothSessionState.Failed) _errors.tryEmit(state.message)
        }

        private fun onConnected(
            currentId: String,
            state: BluetoothSessionState.Connected,
        ) {
            val stableId = idFor(state.mac)
            val name = state.name ?: state.mac
            store.rememberBt(
                RememberedBt(
                    id = stableId,
                    name = name,
                    mac = state.mac,
                    profileName = state.profile.profileName,
                ),
            )
            clearStale(stableId)
            synchronized(lock) {
                if (currentId != stableId) activeConnId = stableId
                _states.update { map ->
                    val prior = map[currentId] ?: SlotState()
                    val next =
                        prior.copy(
                            registered = true,
                            connected = true,
                            connectedName = name,
                            profileName = state.profile.profileName,
                            autoReconnecting = false,
                            acquiring = false,
                        )
                    (map - currentId) + (stableId to next)
                }
            }
        }

        companion object {
            fun idFor(mac: String): String = "bt:$mac"
        }
    }
