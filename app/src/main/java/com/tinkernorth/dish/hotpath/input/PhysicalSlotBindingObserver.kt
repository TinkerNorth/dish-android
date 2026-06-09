// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.composer.ConnectionHub
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.jni.SatelliteNative
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

// The ordered native/hub effects reconcileSlots resolves from one snapshot. Kept a pure value type so
// the routing decision (which physical device id lands on which server-side controller) is testable
// without the JNI side effects; getting it wrong routes input to the wrong controller.
sealed interface BindOp {
    data class BindSatellite(
        val deviceId: Int,
        val handle: Int,
        val controllerIndex: Int,
    ) : BindOp

    data class BindBluetooth(
        val deviceId: Int,
        val connectionId: String,
    ) : BindOp

    data class Unbind(
        val deviceId: Int,
    ) : BindOp

    data class Forget(
        val deviceId: Int,
    ) : BindOp

    // The departed-id path also drops the hub binding for the slot, so a re-added id re-binds cleanly.
    data class ReleaseHubBinding(
        val deviceId: Int,
    ) : BindOp
}

// Flat, immutable view of one satellite connection captured once per push so reconcileSlots stays pure.
// Absence of a key means the connection is unknown locally (the old `satellite.get(cid) == null`).
data class SatelliteSlotSnapshot(
    val handle: Int,
    val slots: Map<String, SatelliteConnection.SlotBinding>,
)

// Pure reconciler. Ordering guarantee: every departed-id op (Unbind, Forget for a framework id, then
// ReleaseHubBinding) precedes every present-id bind, so a re-added device id cannot bind onto a
// controller index a stale entry still owns. A present device only emits BindSatellite when its
// session is known with a live handle and its slot is registered, and BindBluetooth only when the
// connection is actually connected now; otherwise it emits Unbind.
fun reconcileSlots(
    present: Set<Int>,
    lastBound: Set<Int>,
    bindings: Map<String, String>,
    summaries: List<ConnectionSummary>,
    perConnectionSlotInfo: Map<String, SatelliteSlotSnapshot>,
    btConnectedIds: Set<String>,
): List<BindOp> {
    val ops = mutableListOf<BindOp>()
    for (id in lastBound - present) {
        ops += BindOp.Unbind(id)
        // A claimed USB synthetic (negative id) is freed by detachUsbDevice, not forgetPhysicalDevice.
        if (id >= 0) ops += BindOp.Forget(id)
        ops += BindOp.ReleaseHubBinding(id)
    }
    for (id in present) {
        val slotId = id.toString()
        val cid = bindings[slotId]
        val summary = cid?.let { lookup -> summaries.firstOrNull { it.id == lookup } }
        if (cid == null || summary == null || summary.live != LinkState.Connected) {
            ops += BindOp.Unbind(id)
            continue
        }
        when (summary.kind) {
            ConnectionKind.SATELLITE -> {
                val sat = perConnectionSlotInfo[cid]
                val info = sat?.slots?.get(slotId)
                if (sat == null || sat.handle < 0 || info == null || !info.registered) {
                    ops += BindOp.Unbind(id)
                } else {
                    ops += BindOp.BindSatellite(id, sat.handle, info.controllerIndex)
                }
            }
            ConnectionKind.BLUETOOTH ->
                // The summary's Connected is a composer-snapshot read; re-check the registry's live
                // connected state (as the satellite branch re-checks handle/registered) before binding.
                if (cid in btConnectedIds) {
                    ops += BindOp.BindBluetooth(id, cid)
                } else {
                    ops += BindOp.Unbind(id)
                }
        }
    }
    return ops
}

// Process-scoped (not activity-scoped) so bindings survive MainActivity → GamepadOverlayActivity hand-off.
@Singleton
class PhysicalSlotBindingObserver
    @Inject
    constructor(
        private val registry: PhysicalGamepadRegistry,
        private val hub: ConnectionHub,
        private val satellite: SatelliteConnectionManager,
        private val bt: BluetoothGamepadRegistry,
        private val scope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        private data class BindingState(
            val devices: Map<Int, PhysicalGamepadRegistry.Device>,
            val bindings: Map<String, String>,
            val summaries: List<ConnectionSummary>,
        )

        private var job: Job? = null
        private var lastBoundDeviceIds: Set<Int> = emptySet()

        override fun onStart(owner: LifecycleOwner) {
            if (job != null) return
            job = stream().onEach(::push).launchIn(scope)
        }

        override fun onStop(owner: LifecycleOwner) {
            job?.cancel()
            job = null
            SatelliteNative.clearAllPhysicalSlots()
            lastBoundDeviceIds = emptySet()
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun stream(): Flow<BindingState> =
            satellite.connections.flatMapLatest { conns ->
                // Outer Map only re-emits on session add/remove; slotsTrigger re-pushes when a session's slot flips `registered`.
                val slotFlows = conns.values.map { it.slots }
                val slotsTrigger: Flow<Unit> =
                    if (slotFlows.isEmpty()) flowOf(Unit) else combine(slotFlows) { Unit }
                combine(
                    registry.devices,
                    hub.bindings,
                    hub.connections,
                    slotsTrigger,
                ) { devs, bindings, summaries, _ -> BindingState(devs, bindings, summaries) }
            }

        private fun push(state: BindingState) {
            val present = state.devices.keys
            // Read each live source once into a flat snapshot so reconcileSlots is pure; the resolved
            // ops are then executed against the native/hub side effects exactly as before.
            val referencedConnIds = state.bindings.values.toSet()
            val slotInfo =
                referencedConnIds
                    .mapNotNull { cid ->
                        satellite.get(cid)?.let { conn -> cid to SatelliteSlotSnapshot(conn.handle, conn.slots.value) }
                    }.toMap()
            val btConnectedIds = referencedConnIds.filterTo(mutableSetOf()) { bt.isConnected(it) }
            val ops =
                reconcileSlots(
                    present = present,
                    lastBound = lastBoundDeviceIds,
                    bindings = state.bindings,
                    summaries = state.summaries,
                    perConnectionSlotInfo = slotInfo,
                    btConnectedIds = btConnectedIds,
                )
            for (op in ops) execute(op)
            lastBoundDeviceIds = present
        }

        private fun execute(op: BindOp) {
            when (op) {
                is BindOp.Unbind -> SatelliteNative.unbindPhysicalSlot(op.deviceId)
                is BindOp.Forget -> SatelliteNative.forgetPhysicalDevice(op.deviceId)
                is BindOp.ReleaseHubBinding -> hub.unbind(op.deviceId.toString())
                is BindOp.BindSatellite ->
                    SatelliteNative.bindPhysicalSlotSatellite(op.deviceId, op.handle, op.controllerIndex)
                is BindOp.BindBluetooth -> SatelliteNative.bindPhysicalSlotBluetooth(op.deviceId, op.connectionId)
            }
        }
    }
