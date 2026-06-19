// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.connection.ConnectIntent
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.source.store.ControllerTypeStore
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import com.tinkernorth.dish.source.store.SlotBindingStore
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectionKind { SATELLITE, BLUETOOTH }

enum class LinkState { Found, Stale, Saved, Ready, Connecting, Connected, Unstable }

// Catalog ids (wire enum values): the picker renders from GET /api/catalog,
// these constants only name the two types this app has bundled art for.
const val CONTROLLER_TYPE_XBOX = 0
const val CONTROLLER_TYPE_PLAYSTATION = 1

// Descriptor touchpadMode protocol constant (contract: off | ds4 | mouse).
const val TOUCHPAD_MODE_OFF = "off"

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
        private val hostFeaturesStore: SatelliteHostFeaturesStore,
        private val composer: ConnectionsComposer,
        private val gamepadRegistry: PhysicalGamepadRegistry,
    ) {
        val bindings: StateFlow<Map<String, String>> = bindingStore.state
        val satTypes: StateFlow<Map<Pair<String, String>, Int>> = typeStore.state

        val connections: StateFlow<List<ConnectionSummary>> = composer.state

        fun summary(id: String): ConnectionSummary? = connections.value.firstOrNull { it.id == id }

        // A bindable slot is a logical slot (non-numeric ids like the on-screen
        // controller) or a device the registry currently knows. Binding an id
        // the registry already dropped (e.g. a framework id superseded by a
        // USB-direct synthetic) would register a zombie pad that streams
        // nothing yet occupies a satellite slot.
        private fun slotExists(slotId: String): Boolean {
            val deviceId = slotId.toIntOrNull() ?: return true
            return gamepadRegistry.devices.value.containsKey(deviceId)
        }

        /**
         * Bind [slotId] to [connectionId] with its FINAL descriptor: type and
         * touchpad routing travel with the bind, so the satellite plugs the
         * right virtual device on the first try (no default-then-correct phase
         * anywhere). Returns false (refusing the bind) for a slot the registry
         * no longer knows.
         */
        fun bind(
            slotId: String,
            connectionId: String,
            controllerType: Int,
            touchpadMode: String = TOUCHPAD_MODE_OFF,
        ): Boolean {
            if (!slotExists(slotId)) return false
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
                typeStore.setType(connectionId, slotId, controllerType)
                satellite.get(connectionId)?.declareSlot(slotId, controllerType, touchpadMode)
            }
            return true
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
            hostFeaturesStore.clearConnection(connectionId)
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
                conn.attachSlot(toSlotId, controllerType = type ?: CONTROLLER_TYPE_XBOX)
            }
        }

        /**
         * USB-direct claim replaced a framework device with [syntheticSlotId].
         * The user's type choice travels with it: a bound twin migrates whole
         * (binding + type), an unbound claim adopts the type remembered for the
         * twin, Xbox only when there has never been a choice to preserve.
         */
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
            if (target != null) {
                val rememberedType =
                    twinSlotId?.let { typeStore.typeFor(target.id, it) } ?: CONTROLLER_TYPE_XBOX
                bind(syntheticSlotId, target.id, rememberedType)
            }
        }

        fun setSatelliteControllerType(
            connectionId: String,
            slotId: String,
            type: Int,
        ) {
            if (typeStore.typeFor(connectionId, slotId) == type) return
            typeStore.setType(connectionId, slotId, type)
            satellite.get(connectionId)?.setControllerType(slotId, type)
        }

        /** Per-slot touchpad routing. Rides the descriptor (client-owned, single writer). */
        fun setSatelliteTouchpadMode(
            connectionId: String,
            slotId: String,
            mode: String,
        ) {
            satellite.get(connectionId)?.setTouchpadMode(slotId, mode)
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
