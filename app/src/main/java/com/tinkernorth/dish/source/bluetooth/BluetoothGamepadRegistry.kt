// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.bluetooth

import com.tinkernorth.dish.composer.ConnectionHub
import com.tinkernorth.dish.core.input.BluetoothGamepad
import com.tinkernorth.dish.core.input.buildHidReport
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.repository.RememberedBt
import com.tinkernorth.dish.source.system.BluetoothBondMonitor
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

/** Reason a remembered Bluetooth host is in the "Needs pairing" state. */
enum class BtStaleReason {
    /** OS fired `ACTION_KEY_MISSING` — host lost its end of the pairing key. */
    KEY_MISSING,

    /** BOND_BONDED → BOND_NONE — host was unpaired (intentionally or wiped). */
    BOND_REMOVED,
}

/**
 * Projects the single [BluetoothHidSession] onto a per-connection-id slot
 * view. Android's HID Device profile only allows one registered app per
 * process, so at most one slot is ever live; the registry keeps the mapping
 * from a caller-supplied id (transient `bt-pending-*` during discovery, or
 * stable `bt:<MAC>` after persistence) to its current [SlotState].
 *
 * Re-keying: callers start a session with a transient id because the MAC is
 * unknown until the host actually connects. When the session emits
 * [BluetoothSessionState.Connected] the registry swaps the transient id for
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
            val acquiring: Boolean = false,
        )

        private val lock = Any()
        private var activeConnId: String? = null

        private val _states = MutableStateFlow<Map<String, SlotState>>(emptyMap())
        val states: StateFlow<Map<String, SlotState>> = _states.asStateFlow()

        private val _errors =
            MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val errors: SharedFlow<String> = _errors.asSharedFlow()

        /**
         * Per-host "Needs pairing" markers, set by [BluetoothBondMonitor] when
         * the OS reports KEY_MISSING or an unexpected BOND_NONE on a remembered
         * MAC. Cleared on the next successful [BluetoothSessionState.Connected] for that
         * host. Drives [com.tinkernorth.dish.composer.LinkState.Stale] in
         * [com.tinkernorth.dish.composer.ConnectionHub] — the row chip
         * flips to "Needs pairing" so the user knows the host (not just our
         * app) needs to re-pair.
         *
         * Exposed as a map so the reason can drive notification copy
         * ("re-pair the host" vs "the bond was removed"). [ConnectionHub]'s
         * combine reads `.keys` for the Stale set lift.
         */
        private val _staleBtIds = MutableStateFlow<Map<String, BtStaleReason>>(emptyMap())
        val staleBtIds: StateFlow<Map<String, BtStaleReason>> = _staleBtIds.asStateFlow()

        /** Reason a host is in the Stale set, or null when it isn't. */
        fun staleReasonFor(id: String): BtStaleReason? = _staleBtIds.value[id]

        /**
         * Mark a remembered BT host as needing re-pair. Idempotent on identical
         * (id, reason); promotes BOND_REMOVED → KEY_MISSING if a later
         * KEY_MISSING arrives for the same host so the more-specific copy wins.
         */
        fun markStale(
            id: String,
            reason: BtStaleReason,
        ) {
            _staleBtIds.update { current ->
                val prior = current[id]
                if (prior == reason) return@update current
                // KEY_MISSING is the more specific signal — if we already
                // recorded one, don't downgrade to BOND_REMOVED.
                if (prior == BtStaleReason.KEY_MISSING && reason == BtStaleReason.BOND_REMOVED) {
                    return@update current
                }
                current + (id to reason)
            }
        }

        /** Clear a host from the Stale set. Called on the next [BluetoothSessionState.Connected]. */
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
                    .firstOrNull { it.profileName == entry.profileName || it.name == entry.profileName }
                    ?: return null
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
                is BluetoothSessionState.Acquiring -> Unit // seeded by start()
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
            // The host is now bonded again — any Stale marker we may have set
            // on an earlier KEY_MISSING / BOND_NONE is invalid by definition.
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
