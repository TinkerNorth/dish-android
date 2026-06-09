// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.connection.ConnectIntent
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.source.store.ControllerTypeStore
import com.tinkernorth.dish.source.store.SlotBindingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectionKind { SATELLITE, BLUETOOTH }

enum class LinkState { Found, Stale, Saved, Ready, Connecting, Connected, Unstable }

const val CONTROLLER_TYPE_XBOX = 0
const val CONTROLLER_TYPE_PLAYSTATION = 1

data class ConnectionSummary(
    val id: String,
    val kind: ConnectionKind,
    val label: String,
    val detail: String,
    val live: LinkState,
    val boundSlotIds: List<String>,
    val btProfile: String? = null,
    val satelliteControllerTypes: Map<String, Int> = emptyMap(),
)

@Singleton
class ConnectionCoordinator
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
        val bindings: StateFlow<Map<String, String>> = bindingStore.state
        val satTypes: StateFlow<Map<Pair<String, String>, Int>> = typeStore.state

        val connections: StateFlow<List<ConnectionSummary>> = composer.state

        fun summary(id: String): ConnectionSummary? = connections.value.firstOrNull { it.id == id }

        fun bind(
            slotId: String,
            connectionId: String,
        ) {
            val priorConnId = bindingStore.connectionFor(slotId)
            if (priorConnId != null && priorConnId != connectionId) {
                satellite.get(priorConnId)?.detachSlot(slotId)
                typeStore.clear(priorConnId, slotId)
            }

            // Android HID Device profile allows only one active host; release prior slot first.
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

            if (!isBt) {
                typeStore.setTypeIfAbsent(connectionId, slotId, CONTROLLER_TYPE_XBOX)
            }

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
        }

        // Forget a remembered connection and its local state. Unbind its slots (which drops their
        // per-slot types), clear any orphaned type rows, then forget the backing host. Clearing the
        // whole connection here is what stops a dead connection id leaking controller-type entries;
        // it is done on forget, not on a transient disconnect, so a brief drop keeps the user's pick.
        fun forgetConnection(connectionId: String) {
            bindingStore.slotsFor(connectionId).toList().forEach(::unbind)
            typeStore.clearConnection(connectionId)
            if (store.rememberedBt().any { it.id == connectionId }) {
                store.forgetBt(connectionId)
            } else {
                satellite.forget(connectionId)
            }
        }

        fun migrateSlotBinding(
            fromSlotId: String,
            toSlotId: String,
        ) {
            if (fromSlotId == toSlotId) return
            val connId = bindingStore.connectionFor(fromSlotId) ?: return
            val type = typeStore.typeFor(connId, fromSlotId)
            bindingStore.unbind(fromSlotId)
            bindingStore.bind(toSlotId, connId)
            if (type != null) {
                typeStore.clear(connId, fromSlotId)
                typeStore.setType(connId, toSlotId, type)
            }
            val conn = satellite.get(connId) ?: return
            if (!conn.renameSlot(fromSlotId, toSlotId)) {
                scope.launch { conn.attachSlot(toSlotId, controllerType = type ?: CONTROLLER_TYPE_XBOX) }
            }
        }

        fun bindClaimedSynthetic(
            twinSlotId: String?,
            syntheticSlotId: String,
        ) {
            if (twinSlotId != null && bindingStore.connectionFor(twinSlotId) != null) {
                migrateSlotBinding(twinSlotId, syntheticSlotId)
                return
            }
            val target =
                connections.value.singleOrNull {
                    it.kind == ConnectionKind.SATELLITE && it.live == LinkState.Connected
                }
            if (target != null) bind(syntheticSlotId, target.id)
        }

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
        }

        fun boundConnection(slotId: String): ConnectionSummary? =
            bindingStore.connectionFor(slotId)?.let { id ->
                connections.value.firstOrNull { it.id == id }
            }

        fun autoReconnectAll() {
            for (remembered in store.remembered()) {
                val existing = satellite.get(remembered.id)
                if (existing?.state?.value != SatelliteSessionState.Live) {
                    satellite.connect(remembered.toDiscovered(), ConnectIntent.AUTO_RECONNECT)
                }
            }
            val btHosts = store.rememberedBt()
            // `autoReconnecting` guards an in-flight acquire from being torn down and restarted.
            if (btHosts.none {
                    val s = bt.state(it.id)
                    s.connected || s.registered || s.autoReconnecting
                }
            ) {
                btHosts.firstOrNull()?.let { bt.tryAutoReconnect(it.id) }
            }
        }
    }
