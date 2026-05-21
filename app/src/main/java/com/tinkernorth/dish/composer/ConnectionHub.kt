// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.input.BluetoothGamepad
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.connection.ConnectIntent
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.source.store.ControllerTypeStore
import com.tinkernorth.dish.source.store.SlotBindingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kind of a connection target. Bluetooth is capped at a single active host by
 * Android's HID Device profile, but the hub still tracks multiple remembered hosts
 * and exposes them as separate [ConnectionSummary] entries so the user can switch
 * between them.
 */
enum class ConnectionKind { SATELLITE, BLUETOOTH }

/**
 * UI-facing link state for one connection.
 *
 * | LinkState   | Pairing axis    | Presence axis   | User-facing chip |
 * |-------------|-----------------|-----------------|------------------|
 * | [Found]     | unpaired        | seen            | "Found"          |
 * | [Stale]     | broken (lost)   | any             | "Needs pairing"  |
 * | [Saved]     | paired          | absent          | "Offline"        |
 * | [Ready]     | paired          | seen, no session| "Ready"          |
 * | [Connecting]| paired          | linking         | "Connecting…"    |
 * | [Connected] | paired          | live            | "Online"         |
 * | [Unstable]  | paired          | faltering       | "Unsteady"       |
 */
enum class LinkState { Found, Stale, Saved, Ready, Connecting, Connected, Unstable }

/** Server-visible controller type. The native protocol carries the raw int. */
const val CONTROLLER_TYPE_XBOX = 0
const val CONTROLLER_TYPE_PLAYSTATION = 1

/**
 * Snapshot of a single remembered or live connection.
 *
 * [boundSlotIds] is a list because a satellite session can host multiple
 * controllers; for Bluetooth the hub enforces at most one entry.
 */
data class ConnectionSummary(
    val id: String,
    val kind: ConnectionKind,
    val label: String,
    val detail: String,
    val live: LinkState,
    val boundSlotIds: List<String>,
    /** For BT only: name of the remembered [BluetoothGamepad.GamepadProfile]. */
    val btProfile: String? = null,
    /**
     * For SATELLITE only: per-slot controller type (Xbox / PlayStation /…). Empty
     * for Bluetooth since BT type is fixed by the remembered host's HID profile.
     */
    val satelliteControllerTypes: Map<String, Int> = emptyMap(),
)

/**
 * **Coordinator** — not a pattern instance itself. Orchestrates between three
 * underlying pattern instances:
 *
 *   - [SlotBindingStore] (source/store/) — `slotId -> connectionId`
 *   - [ControllerTypeStore] (source/store/) — `(connId, slotId) -> type` (satellites)
 *   - [ConnectionsComposer] (composer/) — derives the unified
 *     `List<ConnectionSummary>` consumers render
 *
 * The Hub exposes the same imperative API the old god-object did
 * (`bind` / `unbind` / `setSatelliteControllerType` / `autoReconnectAll`), but
 * every read goes through the stores' / composer's StateFlows. Eviction (the
 * Bluetooth one-slot-per-host rule, the satellite slot detach on rebind) lives
 * here because it spans multiple stores plus side-effects on
 * [com.tinkernorth.dish.source.connection.SatelliteConnection]; a pure store can't
 * do that.
 *
 * Reads consumers do today still work:
 *   - `hub.bindings` → forwards [SlotBindingStore.state]
 *   - `hub.satTypes` → forwards [ControllerTypeStore.state]
 *   - `hub.connections` → forwards [ConnectionsComposer.state]
 *
 * New code should prefer injecting the underlying store / composer directly.
 */
@Singleton
class ConnectionHub
    @Inject
    constructor(
        private val satellite: SatelliteConnectionManager,
        private val bt: BluetoothGamepadRegistry,
        private val store: ConnectionStore,
        private val bindingStore: SlotBindingStore,
        private val typeStore: ControllerTypeStore,
        private val composer: ConnectionsComposer,
        private val scope: CoroutineScope,
    ) {
        // Forwarded flows so existing callers don't have to inject the underlying
        // stores. The forwards are pass-through; the stores own the truth.
        val bindings: StateFlow<Map<String, String>> = bindingStore.state
        val satTypes: StateFlow<Map<Pair<String, String>, Int>> = typeStore.state

        // Connections is a forwarding StateFlow that mirrors the composer's
        // derivation, plus the imperative `bind`/`unbind` paths push a fresh
        // snapshot into it so the UI sees the change in the same frame the user's
        // tap triggered it (rather than waiting for the combine to re-emit).
        private val _connections =
            MutableStateFlow<List<ConnectionSummary>>(emptyList())
        val connections: StateFlow<List<ConnectionSummary>> = _connections.asStateFlow()

        init {
            // Mirror composer.state into the forwarding flow.
            composer.state
                .onEach { _connections.value = it }
                .launchIn(scope)
        }

        /** Look up a summary by id. */
        fun summary(id: String): ConnectionSummary? = _connections.value.firstOrNull { it.id == id }

        /**
         * Bind [slotId] to [connectionId]. Eviction depends on the connection
         * kind: Bluetooth releases any prior slot on the same host
         * (single-host HID Device profile constraint), but satellites accept
         * multiple slots side-by-side. If the slot was previously bound
         * elsewhere, that prior binding is detached first regardless of kind.
         */
        fun bind(
            slotId: String,
            connectionId: String,
        ) {
            // Slot was previously bound to a different connection: release it
            // there before claiming the new one. Without this the old satellite
            // session would keep treating this slot as its owner and reports for
            // it would still be addressed to the old controller index.
            val priorConnId = bindingStore.connectionFor(slotId)
            if (priorConnId != null && priorConnId != connectionId) {
                satellite.get(priorConnId)?.detachSlot(slotId)
                typeStore.clear(priorConnId, slotId)
            }

            // Bluetooth-only: another slot already holds this connection?
            // Release it and tell the native session so the server sees a clean
            // CONTROLLER_REMOVE before the new slot's CONTROLLER_ADD.
            val isBt = store.rememberedBt().any { it.id == connectionId }
            if (isBt) {
                val priorSlot =
                    bindingStore.slotsFor(connectionId).firstOrNull { it != slotId }
                if (priorSlot != null) {
                    bindingStore.unbind(priorSlot)
                    satellite.get(connectionId)?.detachSlot(priorSlot)
                }
            }

            bindingStore.bind(slotId, connectionId)

            // Stash a default type for new satellite bindings so the UI's
            // per-slot toggle has something to render before the user picks.
            if (!isBt) {
                typeStore.setTypeIfAbsent(connectionId, slotId, CONTROLLER_TYPE_XBOX)
            }

            // Push a fresh snapshot so the UI sees the new boundSlotIds without
            // waiting for the combine to re-emit on its own tick.
            _connections.value = composer.buildSummariesNow()

            // For satellite: attach the controller slot on the server side.
            val type = typeStore.typeFor(connectionId, slotId) ?: CONTROLLER_TYPE_XBOX
            satellite.get(connectionId)?.let { conn ->
                scope.launch { conn.attachSlot(slotId, controllerType = type) }
            }
        }

        fun unbind(slotId: String) {
            val connId = bindingStore.connectionFor(slotId) ?: return
            bindingStore.unbind(slotId)
            satellite.get(connId)?.detachSlot(slotId)
            typeStore.clear(connId, slotId)
            _connections.value = composer.buildSummariesNow()
        }

        /**
         * Change the controller type (Xbox/PS/…) for a satellite-bound slot.
         * Pushes the change to the native session if it's live; otherwise
         * stashes the new value so it's used at the next registration. No-op
         * for Bluetooth bindings since BT type is fixed by the remembered
         * host's HID profile.
         */
        fun setSatelliteControllerType(
            connectionId: String,
            slotId: String,
            type: Int,
        ) {
            if (typeStore.typeFor(connectionId, slotId) == type) return
            typeStore.setType(connectionId, slotId, type)
            satellite.get(connectionId)?.let { conn ->
                scope.launch { conn.setControllerType(slotId, type) }
            }
            _connections.value = composer.buildSummariesNow()
        }

        fun boundConnection(slotId: String): ConnectionSummary? =
            bindingStore.connectionFor(slotId)?.let { id ->
                _connections.value.firstOrNull { it.id == id }
            }

        /**
         * Kick off a reconnect for every remembered satellite that isn't live
         * yet, plus the first remembered Bluetooth host. Safe to call on
         * every `MainActivity.onCreate` — both underlying managers are
         * idempotent when a session is already live.
         */
        fun autoReconnectAll() {
            for (remembered in store.remembered()) {
                val existing = satellite.get(remembered.id)
                if (existing?.state?.value != SatelliteSessionState.Live) {
                    // AUTO_RECONNECT keeps failure paths silent — the row chip
                    // (Connecting → Saved/Stale) is the user-visible signal.
                    satellite.connect(remembered.toDiscovered(), ConnectIntent.AUTO_RECONNECT)
                }
            }
            val btHosts = store.rememberedBt()
            // Include `autoReconnecting` so an in-flight acquire (triggered by an
            // earlier foreground kick) isn't torn down and restarted.
            if (btHosts.none {
                    val s = bt.state(it.id)
                    s.connected || s.registered || s.autoReconnecting
                }
            ) {
                btHosts.firstOrNull()?.let { bt.tryAutoReconnect(it.id) }
            }
        }
    }
