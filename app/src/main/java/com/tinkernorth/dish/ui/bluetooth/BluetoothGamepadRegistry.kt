package com.tinkernorth.dish.ui.bluetooth

import com.tinkernorth.dish.data.network.ConnectionStore
import com.tinkernorth.dish.data.network.RememberedBt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Projects the single [BluetoothHidSession] onto a per-connection-id slot
 * view. Android's HID Device profile only allows one registered app per
 * process, so at most one slot is ever live; the registry keeps the mapping
 * from a caller-supplied id (transient `bt-pending-*` during discovery, or
 * stable `bt:<MAC>` after persistence) to its current [SlotState].
 *
 * Re-keying: callers start a session with a transient id because the MAC is
 * unknown until the host actually connects. When the session emits
 * [SessionState.Connected] the registry swaps the transient id for
 * [idFor] `<MAC>`, persists the host through [ConnectionStore.rememberBt],
 * and drops the transient entry so the UI sees a single stable row.
 */
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
        )

        private val lock = Any()
        private var activeConnId: String? = null

        private val _states = MutableStateFlow<Map<String, SlotState>>(emptyMap())
        val states: StateFlow<Map<String, SlotState>> = _states.asStateFlow()

        init {
            session.addListener(::onSessionState)
        }

        fun state(connId: String): SlotState = _states.value[connId] ?: SlotState()

        fun isConnected(connId: String): Boolean = state(connId).connected

        fun isActive(connId: String): Boolean = synchronized(lock) { activeConnId == connId }

        fun isAutoReconnecting(connId: String): Boolean = state(connId).autoReconnecting

        /**
         * Start a fresh HID registration for [connId] with [profile]. If a
         * different connection is currently active it is evicted first — the
         * session tears down its proxy internally, so only the registry's
         * bookkeeping needs to catch up.
         */
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

        /**
         * If [connId] matches a remembered host, register the HID with its saved
         * profile and try to re-attach to its MAC silently. Returns the profile
         * used or null if nothing was restored.
         *
         * Idempotent: if the slot is already registered/connected we leave the
         * live session alone instead of tearing it down. This matters because
         * the foreground observer and MainActivity both call us on every return
         * to the foreground.
         */
        fun tryAutoReconnect(connId: String): BluetoothGamepad.GamepadProfile? {
            val entry = store.rememberedBt().firstOrNull { it.id == connId } ?: return null
            val profile =
                BluetoothGamepad.GamepadProfile.entries
                    .firstOrNull { it.name == entry.profileName } ?: return null
            val current = _states.value[connId]
            // Also short-circuit while an acquire is already in flight, otherwise
            // the second foreground kick would restart start() mid-handshake.
            if (current?.registered == true ||
                current?.connected == true ||
                current?.autoReconnecting == true
            ) {
                return profile
            }
            start(connId, profile, autoConnectMac = entry.mac)
            return profile
        }

        // ── Session projection ────────────────────────────────────────────────

        private fun onSessionState(state: SessionState) {
            val connId = synchronized(lock) { activeConnId } ?: return
            when (state) {
                is SessionState.Idle,
                is SessionState.Failed,
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
                                )
                        )
                    }
                is SessionState.Acquiring -> Unit // seeded by start()
                is SessionState.Registered ->
                    _states.update { map ->
                        val cur = map[connId] ?: SlotState()
                        map + (
                            connId to
                                cur.copy(
                                    registered = true,
                                    connected = false,
                                    connectedName = null,
                                )
                        )
                    }
                is SessionState.Connected -> onConnected(connId, state)
            }
        }

        private fun onConnected(
            currentId: String,
            state: SessionState.Connected,
        ) {
            val stableId = idFor(state.mac)
            val name = state.name ?: state.mac
            store.rememberBt(
                RememberedBt(
                    id = stableId,
                    name = name,
                    mac = state.mac,
                    profileName = state.profile.name,
                ),
            )
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
                        )
                    (map - currentId) + (stableId to next)
                }
            }
        }

        companion object {
            fun idFor(mac: String): String = "bt:$mac"
        }
    }
