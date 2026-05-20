// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepad
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kind of a connection target. Bluetooth is capped at a single active host by
 * Android's HID Device profile, but the hub still tracks multiple remembered
 * hosts and exposes them as separate [ConnectionSummary] entries so the user
 * can switch between them.
 */
enum class ConnectionKind { SATELLITE, BLUETOOTH }

/**
 * UI-facing link state for one connection (Satellite or Bluetooth host). This
 * is the chip a row renders; combines the persistent "Pairing" axis (have we
 * paired?) and the live "Presence" axis (do we see it / is the session up?).
 *
 * Internally a Satellite session also has [SessionState] (the wire-level
 * presence axis only); [LinkState] is derived from that plus discovery /
 * remembered presence in [ConnectionHub.buildSatelliteSummary].
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
 *
 * **[Stale]** is not yet entered: it requires the satellite to return a
 * `PAIRING_UNKNOWN` error so the client can distinguish "peer forgot us"
 * from a generic connect failure. Until that protocol change lands, a
 * server-side forget surfaces as a generic disconnect.
 *
 * **[Unstable]** is not yet entered: it requires the native layer to expose
 * the consecutive-missed-heartbeat count separately from the binary
 * `isConnectionAlive()` predicate. Today the connection flips Connected →
 * (Saved | Ready) directly when misses hit the death threshold.
 */
enum class LinkState { Found, Stale, Saved, Ready, Connecting, Connected, Unstable }

/** Server-visible controller type. The native protocol carries the raw int. */
const val CONTROLLER_TYPE_XBOX = 0
const val CONTROLLER_TYPE_PLAYSTATION = 1

/**
 * Snapshot of a single remembered or live connection, used by the UI to render
 * the connections list and the bind-to-connection picker on each slot.
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
     * For SATELLITE only: per-slot controller type (Xbox / PlayStation /…).
     * Empty for Bluetooth since BT type is fixed by the remembered host's
     * HID profile.
     */
    val satelliteControllerTypes: Map<String, Int> = emptyMap(),
)

/**
 * Single place every slot talks to. Aggregates [SatelliteConnectionManager] and
 * [BluetoothGamepadRegistry] into one stream of [ConnectionSummary] entries.
 *
 * Binding a slot to a connection is a UI-level concept (which slot routes its
 * input where) — the actual wiring lives in the managers; the hub just tracks
 * the bindings. Eviction is per-kind: Bluetooth is capped at one slot per
 * connection (HID Device profile only supports a single host) but satellites
 * can host as many slots as the server allows.
 */
@Singleton
class ConnectionHub
    @Inject
    constructor(
        private val satellite: SatelliteConnectionManager,
        private val bt: BluetoothGamepadRegistry,
        private val store: ConnectionStore,
        private val scope: CoroutineScope,
    ) {
        /** slotId -> connectionId of the connection currently routing that slot. */
        private val _bindings = MutableStateFlow<Map<String, String>>(emptyMap())
        val bindings: StateFlow<Map<String, String>> = _bindings.asStateFlow()

        /**
         * (connectionId, slotId) -> controller type for satellite bindings.
         * Source of truth for the UI's per-slot Xbox/PS toggle; the actual
         * push to the native session goes through [SatelliteConnection.setControllerType].
         * Bluetooth's type is fixed by the remembered host so it doesn't live here.
         */
        private val _satTypes = MutableStateFlow<Map<Pair<String, String>, Int>>(emptyMap())
        val satTypes: StateFlow<Map<Pair<String, String>, Int>> = _satTypes.asStateFlow()

        private val _connections = MutableStateFlow<List<ConnectionSummary>>(emptyList())
        val connections: StateFlow<List<ConnectionSummary>> = _connections.asStateFlow()

        @OptIn(ExperimentalCoroutinesApi::class)
        private val flatSatConnections: Flow<Map<String, SatelliteConnection>> =
            satellite.connections
                .flatMapLatest { satMap ->
                    // The outer Map<String, SatelliteConnection> only re-emits when
                    // a session is added/removed — *not* when an existing session's
                    // state flips IDLE → CONNECTING → CONNECTED. Without flattening
                    // into each session's `state` flow, the connections-list UI would
                    // show stale "Connecting…" until the screen is reopened.
                    if (satMap.isEmpty()) {
                        flowOf(satMap)
                    } else {
                        combine(satMap.values.map { it.state }) { satMap }
                    }
                }

        init {
            // We watch seven flows so the summaries refresh on any of:
            //   * sessions add/remove or transition state
            //   * BT slot states (Acquiring/Registered/Connected/Failed)
            //   * discovery presence (Saved vs Ready vs Found split)
            //   * bindings (boundSlotIds in summary)
            //   * controller-type swap (Xbox/PS chip)
            //   * Stale markers — both satellites (auto-reconnect pair-rejected)
            //     and BT (KEY_MISSING / bond removed). Without these in the
            //     combine() the row chip would stay "Offline" / "Online" even
            //     though the manager flipped it Stale.
            // The arity-7 vararg overload of `combine` takes an `Array<*>` —
            // we destructure positionally with a small `unwrap` helper so the
            // type narrowing stays at the boundary instead of leaking into
            // buildSummaries.
            combine(
                flatSatConnections,
                bt.states,
                satellite.discoveredServers,
                _bindings,
                _satTypes,
                satellite.staleSatelliteIds,
                bt.staleBtIds,
            ) { args -> unwrapAndBuild(args) }
                .onEach { _connections.value = it }
                .launchIn(scope)
        }

        @Suppress("UNCHECKED_CAST")
        private fun unwrapAndBuild(args: Array<*>): List<ConnectionSummary> {
            val satMap = args[0] as Map<String, SatelliteConnection>
            val btStates = args[1] as Map<String, BluetoothGamepadRegistry.SlotState>
            val discovered = args[2] as List<com.tinkernorth.dish.data.model.DiscoveredServer>
            val staleSat = args[5] as Set<String>
            // BT registry exposes a reason-keyed map; the hub only needs the
            // set of ids to lift Saved → Stale. The reason itself drives the
            // notification copy and lives one layer up in the activity.
            val staleBtMap = args[6] as Map<String, *>
            return buildSummaries(
                satMap,
                btStates,
                discoveredIdSet(discovered),
                staleSat,
                staleBtMap.keys,
            )
        }

        /**
         * Set of currently-discovered satellite ids. Used by
         * [buildSatelliteSummary] to split an Idle paired entry into
         * [LinkState.Ready] (we see it on the network) vs [LinkState.Saved]
         * (we don't).
         */
        private fun discoveredIdSet(discovered: List<com.tinkernorth.dish.data.model.DiscoveredServer>): Set<String> =
            discovered.mapTo(mutableSetOf()) { SatelliteConnection.idFor(it) }

        private fun buildSummaries(
            satMap: Map<String, SatelliteConnection>,
            btStates: Map<String, BluetoothGamepadRegistry.SlotState>,
            discoveredIds: Set<String>,
            staleSatIds: Set<String> = emptySet(),
            staleBtIds: Set<String> = emptySet(),
        ): List<ConnectionSummary> {
            val bindings = _bindings.value
            val satTypes = _satTypes.value
            val result = mutableListOf<ConnectionSummary>()

            val remembered = store.remembered().associateBy { it.id }
            val satIds = (remembered.keys + satMap.keys).toSet()
            for (id in satIds) {
                buildSatelliteSummary(
                    id,
                    satMap[id],
                    remembered[id],
                    bindings,
                    satTypes,
                    discoveredIds,
                    isStale = id in staleSatIds,
                )?.let(result::add)
            }

            val rememberedBtIds = mutableSetOf<String>()
            for (entry in store.rememberedBt()) {
                rememberedBtIds += entry.id
                result +=
                    buildRememberedBtSummary(
                        entry,
                        btStates[entry.id],
                        bindings,
                        isStale = entry.id in staleBtIds,
                    )
            }

            for ((id, state) in btStates) {
                if (id in rememberedBtIds) continue
                result += buildTransientBtSummary(id, state, bindings)
            }
            return result
        }

        /**
         * Satellites: remembered + any live not-yet-remembered (discovery hits).
         *
         * Derives [LinkState] from the wire-level [SessionState] plus whether
         * [id] is currently in the discovery set:
         * - SessionState.Live      → LinkState.Connected
         * - SessionState.Linking   → LinkState.Connecting
         * - SessionState.Faltering → LinkState.Unstable
         * - SessionState.Idle / null:
         *     [isStale] true       → LinkState.Stale   ("Needs pairing" — set by
         *                            the manager when an auto-reconnect's pair
         *                            handshake came back with ok=false, i.e.
         *                            the server has forgotten us)
         *     in discoveredIds     → LinkState.Ready
         *     not in discoveredIds → LinkState.Saved
         *
         * Stale wins over Ready/Saved because it carries actionable intent
         * (the user needs to re-enter a PIN); the chip and the action button
         * are picked off this state.
         */
        private fun buildSatelliteSummary(
            id: String,
            conn: SatelliteConnection?,
            remembered: RememberedSatellite?,
            bindings: Map<String, String>,
            satTypes: Map<Pair<String, String>, Int>,
            discoveredIds: Set<String>,
            isStale: Boolean,
        ): ConnectionSummary? {
            val server = conn?.server?.value ?: remembered?.toDiscovered() ?: return null
            val live =
                when (conn?.state?.value) {
                    SessionState.Live -> LinkState.Connected
                    SessionState.Linking -> LinkState.Connecting
                    SessionState.Faltering -> LinkState.Unstable
                    SessionState.Idle, null ->
                        when {
                            isStale -> LinkState.Stale
                            id in discoveredIds -> LinkState.Ready
                            else -> LinkState.Saved
                        }
                }
            val bound = bindings.entries.filter { it.value == id }.map { it.key }
            return ConnectionSummary(
                id = id,
                kind = ConnectionKind.SATELLITE,
                label = server.name.ifEmpty { server.ip },
                detail = "${server.ip} • UDP ${server.udpPort}",
                live = live,
                boundSlotIds = bound,
                satelliteControllerTypes = buildSlotTypes(id, bound, satTypes),
            )
        }

        /**
         * Bluetooth: one summary per remembered host; live state from registry.
         * [isStale] flips an idle remembered host to "Needs pairing" — set by
         * [BluetoothBondMonitor] on KEY_MISSING / BOND_NONE transitions and
         * cleared on a subsequent successful [SessionState.Connected].
         */
        private fun buildRememberedBtSummary(
            entry: RememberedBt,
            state: BluetoothGamepadRegistry.SlotState?,
            bindings: Map<String, String>,
            isStale: Boolean,
        ): ConnectionSummary {
            val bound = bindings.entries.filter { it.value == entry.id }.map { it.key }
            val live =
                if (isStale && state?.connected != true) {
                    LinkState.Stale
                } else {
                    liveStateOf(state)
                }
            return ConnectionSummary(
                id = entry.id,
                kind = ConnectionKind.BLUETOOTH,
                label = entry.name.ifEmpty { entry.mac },
                detail = "${entry.profileName} • ${entry.mac}",
                live = live,
                boundSlotIds = bound,
                btProfile = entry.profileName,
            )
        }

        /**
         * Transient BT slots: a registration that hasn't completed yet, so the
         * host MAC is unknown and the entry isn't in rememberedBt yet. Surfaced
         * so the user sees progress between "tap profile" and "host paired";
         * on success the registry re-keys to bt:<MAC> and the next emission
         * collapses this row into the remembered one.
         */
        private fun buildTransientBtSummary(
            id: String,
            state: BluetoothGamepadRegistry.SlotState,
            bindings: Map<String, String>,
        ): ConnectionSummary {
            val detail =
                when {
                    state.connected -> state.connectedName.orEmpty()
                    state.registered -> "Ready to pair — find this device on your host"
                    state.acquiring || state.autoReconnecting -> "Acquiring HID profile…"
                    else -> "Idle"
                }
            return ConnectionSummary(
                id = id,
                kind = ConnectionKind.BLUETOOTH,
                label = state.profileName ?: "Bluetooth gamepad",
                detail = detail,
                live = liveStateOf(state),
                boundSlotIds = bindings.entries.filter { it.value == id }.map { it.key },
                btProfile = state.profileName,
            )
        }

        /**
         * Bluetooth state → [LinkState]. Note that BT doesn't have the same
         * "seen on network" vs "saved offline" distinction satellites do —
         * a remembered BT host is just remembered, with no equivalent of an
         * mDNS/broadcast presence ping. So idle remembered hosts collapse
         * to [LinkState.Saved] (offline) regardless of physical proximity.
         */
        private fun liveStateOf(state: BluetoothGamepadRegistry.SlotState?): LinkState =
            when {
                state?.connected == true -> LinkState.Connected
                state?.registered == true ||
                    state?.autoReconnecting == true ||
                    state?.acquiring == true -> LinkState.Connecting
                else -> LinkState.Saved
            }

        private fun buildSlotTypes(
            connId: String,
            boundSlotIds: List<String>,
            satTypes: Map<Pair<String, String>, Int>,
        ): Map<String, Int> {
            val out = mutableMapOf<String, Int>()
            for (slotId in boundSlotIds) {
                val type = satTypes[connId to slotId] ?: continue
                out[slotId] = type
            }
            return out
        }

        fun summary(id: String): ConnectionSummary? = _connections.value.firstOrNull { it.id == id }

        /**
         * Bind [slotId] to [connectionId]. Eviction depends on the connection
         * kind: Bluetooth releases any prior slot on the same host (single-host
         * HID Device profile constraint), but satellites accept multiple slots
         * side-by-side. If the slot was previously bound elsewhere, that prior
         * binding is detached first regardless of kind.
         */
        fun bind(
            slotId: String,
            connectionId: String,
        ) {
            val current = _bindings.value.toMutableMap()

            // Slot was previously bound to a different connection: release it
            // there before claiming the new one. Without this the old satellite
            // session would keep treating this slot as its owner and reports
            // for it would still be addressed to the old controller index.
            val priorConnId = current[slotId]
            if (priorConnId != null && priorConnId != connectionId) {
                satellite.get(priorConnId)?.detachSlot(slotId)
                _satTypes.value = _satTypes.value - (priorConnId to slotId)
            }

            // Bluetooth-only: another slot already holds this connection?
            // Release it and tell the native session so the server sees a
            // clean CONTROLLER_REMOVE before the new slot's CONTROLLER_ADD.
            // Satellites are multi-slot so we leave existing bindings alone.
            val isBt = store.rememberedBt().any { it.id == connectionId }
            if (isBt) {
                val priorSlot = current.entries.firstOrNull { it.value == connectionId && it.key != slotId }?.key
                if (priorSlot != null) {
                    current.remove(priorSlot)
                    satellite.get(connectionId)?.detachSlot(priorSlot)
                }
            }

            current[slotId] = connectionId
            _bindings.value = current

            // Stash a default type for new satellite bindings so the UI's
            // per-slot toggle has something to render before the user picks.
            val type = _satTypes.value[connectionId to slotId] ?: CONTROLLER_TYPE_XBOX
            if (!isBt) {
                _satTypes.value = _satTypes.value + ((connectionId to slotId) to type)
            }

            // Re-emit connections so boundSlotIds refreshes.
            _connections.value = rebuildSummariesNow()
            // For satellite: attach the controller slot on the server side.
            satellite.get(connectionId)?.let { conn ->
                scope.launch { conn.attachSlot(slotId, controllerType = type) }
            }
        }

        fun unbind(slotId: String) {
            val current = _bindings.value.toMutableMap()
            val connId = current.remove(slotId) ?: return
            _bindings.value = current
            satellite.get(connId)?.detachSlot(slotId)
            _satTypes.value = _satTypes.value - (connId to slotId)
            _connections.value = rebuildSummariesNow()
        }

        /**
         * Imperative summaries refresh used by [bind] / [unbind] /
         * [setSatelliteControllerType] so the row UI reflects the change in
         * the same frame the user's action triggered it. The combine() in
         * [init] re-emits independently on its own tick — this just avoids a
         * visible delay between tap and chip update.
         */
        private fun rebuildSummariesNow(): List<ConnectionSummary> =
            buildSummaries(
                satellite.connections.value,
                bt.states.value,
                discoveredIdSet(satellite.discoveredServers.value),
                staleSatIds = satellite.staleSatelliteIds.value,
                staleBtIds = bt.staleBtIds.value.keys,
            )

        /**
         * Change the controller type (Xbox/PS/…) for a satellite-bound slot.
         * Pushes the change to the native session if it's live; otherwise
         * stashes the new value so it's used at the next registration.
         * No-op for Bluetooth bindings since BT type is fixed by the
         * remembered host's HID profile.
         */
        fun setSatelliteControllerType(
            connectionId: String,
            slotId: String,
            type: Int,
        ) {
            val key = connectionId to slotId
            if (_satTypes.value[key] == type) return
            _satTypes.value = _satTypes.value + (key to type)
            satellite.get(connectionId)?.let { conn ->
                scope.launch { conn.setControllerType(slotId, type) }
            }
            _connections.value = rebuildSummariesNow()
        }

        fun boundConnection(slotId: String): ConnectionSummary? =
            _bindings.value[slotId]?.let { id -> _connections.value.firstOrNull { it.id == id } }

        /**
         * Kick off a reconnect for every remembered satellite that isn't live
         * yet, plus the first remembered Bluetooth host (Android's HID Device
         * profile only supports one active host per app).
         *
         * Safe to call on every `MainActivity.onCreate` — both underlying managers
         * are idempotent when a session is already live.
         */
        fun autoReconnectAll() {
            for (remembered in store.remembered()) {
                val existing = satellite.get(remembered.id)
                if (existing?.state?.value != SessionState.Live) {
                    // AUTO_RECONNECT keeps failure paths silent — the row chip
                    // (Connecting → Saved/Stale) is the user-visible signal.
                    // Without this gate, every cold start with a powered-off
                    // satellite fired a notification for each remembered host.
                    satellite.connect(remembered.toDiscovered(), ConnectIntent.AUTO_RECONNECT)
                }
            }
            val btHosts = store.rememberedBt()
            // Include `autoReconnecting` so an in-flight acquire (triggered by an
            // earlier foreground kick) isn't torn down and restarted by the next
            // one — the registry is internally idempotent, but we still want to
            // avoid bouncing the SessionState machine.
            if (btHosts.none {
                    val s = bt.state(it.id)
                    s.connected || s.registered || s.autoReconnecting
                }
            ) {
                btHosts.firstOrNull()?.let { bt.tryAutoReconnect(it.id) }
            }
        }
    }
