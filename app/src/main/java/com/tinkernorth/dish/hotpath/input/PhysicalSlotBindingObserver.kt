// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.jni.PhysicalSlotNative
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightSessionState
import com.tinkernorth.dish.source.lights.FrameworkLightGateway
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

    data class BindMoonlight(
        val deviceId: Int,
        val connectionId: String,
        val controllerNumber: Int,
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
// Departed = ids that vanished since the last pass PLUS any numeric binding whose device the
// registry no longer knows: a device that left while the observer was stopped is in neither
// `present` nor `lastBound`, and without the sweep its slot would be re-declared to the satellite
// on every reconnect forever. Non-numeric slot ids (the on-screen controller) are never swept.
fun reconcileSlots(
    present: Set<Int>,
    lastBound: Set<Int>,
    bindings: Map<String, String>,
    summaries: List<ConnectionSummary>,
    perConnectionSlotInfo: Map<String, SatelliteSlotSnapshot>,
    btConnectedIds: Set<String>,
    moonlightLiveIds: Set<String> = emptySet(),
    moonlightPadNumbers: Map<String, Int> = emptyMap(),
): List<BindOp> {
    val ops = mutableListOf<BindOp>()
    val staleBound = bindings.keys.mapNotNull { it.toIntOrNull() }.filter { it !in present }
    for (id in (lastBound - present) + staleBound) {
        ops += BindOp.Unbind(id)
        // A claimed USB synthetic (negative id) is freed by detachUsbDevice, not forgetPhysicalDevice.
        if (id >= 0) ops += BindOp.Forget(id)
        ops += BindOp.ReleaseHubBinding(id)
    }
    val live =
        LiveLinks(
            summaries = summaries,
            satellites = perConnectionSlotInfo,
            btConnectedIds = btConnectedIds,
            moonlightLiveIds = moonlightLiveIds,
            moonlightPadNumbers = moonlightPadNumbers,
        )
    for (id in present) {
        ops += bindOpFor(id, bindings[id.toString()], live)
    }
    return ops
}

// What is live right now, as reconcileSlots was handed it: the composer's summaries plus each
// manager's own re-checkable state.
private class LiveLinks(
    val summaries: List<ConnectionSummary>,
    val satellites: Map<String, SatelliteSlotSnapshot>,
    val btConnectedIds: Set<String>,
    val moonlightLiveIds: Set<String>,
    val moonlightPadNumbers: Map<String, Int>,
)

// The one op a present device gets: a bind when its connection streams and the manager behind it
// confirms the slot, an unbind otherwise.
private fun bindOpFor(
    id: Int,
    cid: String?,
    live: LiveLinks,
): BindOp {
    val summary = cid?.let { lookup -> live.summaries.firstOrNull { it.id == lookup } }
    val linkStreams = summary?.live == LinkState.Connected || summary?.live == LinkState.Unstable
    if (cid == null || summary == null || !linkStreams) return BindOp.Unbind(id)
    val slotId = id.toString()
    return when (summary.kind) {
        ConnectionKind.SATELLITE -> satelliteBindOp(id, live.satellites[cid]?.let { it to it.slots[slotId] })
        // The summary's Connected is a composer-snapshot read; re-check the registry's live
        // connected state (as the satellite branch re-checks handle/registered) before binding.
        ConnectionKind.BLUETOOTH -> if (cid in live.btConnectedIds) BindOp.BindBluetooth(id, cid) else BindOp.Unbind(id)
        // Same live re-check discipline as the Bluetooth branch: the summary's Connected is
        // a composer-snapshot read, so re-check the manager's live session before binding.
        // The pad number comes with it: one session carries four controllers, and a report
        // that cannot name which one belongs to nobody.
        ConnectionKind.MOONLIGHT -> {
            val pad = live.moonlightPadNumbers[slotId]
            if (cid in live.moonlightLiveIds && pad != null) BindOp.BindMoonlight(id, cid, pad) else BindOp.Unbind(id)
        }
    }
}

// A satellite bind needs a session with a live handle and a slot the satellite has registered.
private fun satelliteBindOp(
    id: Int,
    session: Pair<SatelliteSlotSnapshot, SatelliteConnection.SlotBinding?>?,
): BindOp {
    val (sat, info) = session ?: return BindOp.Unbind(id)
    if (sat.handle < 0 || info == null || !info.registered) return BindOp.Unbind(id)
    return BindOp.BindSatellite(id, sat.handle, info.controllerIndex)
}

// The ops reconcileSlots wants applied, paired with the bind-per-device map after applying them, so
// the next pass can tell what is already live on the native side.
data class DedupedBindOps(
    val ops: List<BindOp>,
    val applied: Map<Int, BindOp>,
)

// Drops a satellite/bluetooth bind byte-identical to the one already applied for that device, since
// re-applying it costs a destructive syncSlotBaseline (held inputs flicker to neutral). A changed
// bind (new handle/controller index/connection) still goes through, and unbinds are always kept --
// they are idempotent on the native side and carry no neutral publish.
fun dedupeBindOps(
    ops: List<BindOp>,
    lastApplied: Map<Int, BindOp>,
): DedupedBindOps {
    val applied = lastApplied.toMutableMap()
    val out = mutableListOf<BindOp>()
    for (op in ops) {
        when (op) {
            is BindOp.BindSatellite -> {
                if (applied[op.deviceId] == op) continue
                applied[op.deviceId] = op
                out += op
            }
            is BindOp.BindBluetooth -> {
                if (applied[op.deviceId] == op) continue
                applied[op.deviceId] = op
                out += op
            }
            is BindOp.BindMoonlight -> {
                if (applied[op.deviceId] == op) continue
                applied[op.deviceId] = op
                out += op
            }
            is BindOp.Unbind -> {
                applied.remove(op.deviceId)
                out += op
            }
            is BindOp.Forget -> {
                applied.remove(op.deviceId)
                out += op
            }
            is BindOp.ReleaseHubBinding -> out += op
        }
    }
    return DedupedBindOps(out, applied)
}

// Process-scoped (not activity-scoped) so bindings survive MainActivity → GamepadOverlayActivity hand-off.
@Singleton
class PhysicalSlotBindingObserver
    @Inject
    constructor(
        private val registry: PhysicalGamepadRegistry,
        private val hub: ConnectionCoordinator,
        private val satellite: SatelliteConnectionManager,
        private val bt: BluetoothGamepadRegistry,
        private val moonlight: com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager,
        private val frameworkLights: FrameworkLightGateway,
        private val scope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        private data class BindingState(
            val devices: Map<Int, PhysicalGamepadRegistry.Device>,
            val bindings: Map<String, String>,
            val summaries: List<ConnectionSummary>,
        )

        private var job: Job? = null
        private var lastBoundDeviceIds: Set<Int> = emptySet()
        private var lastAppliedBinds: Map<Int, BindOp> = emptyMap()

        override fun onStart(owner: LifecycleOwner) {
            if (job != null) return
            job = stream().onEach(::push).launchIn(scope)
        }

        override fun onStop(owner: LifecycleOwner) {
            job?.cancel()
            job = null
            PhysicalSlotNative.clearAllPhysicalSlots()
            // A framework light bar the app opened is given back here too: physical-slot streaming
            // ends when the last activity stops, so a bar left mid-color would otherwise hold the
            // last game color with nothing driving it.
            frameworkLights.releaseAll()
            lastBoundDeviceIds = emptySet()
            lastAppliedBinds = emptyMap()
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun stream(): Flow<BindingState> =
            satellite.connections.flatMapLatest { conns ->
                // Outer Map only re-emits on session add/remove; slotsTrigger re-pushes when a session's slot flips `registered`.
                val slotFlows = conns.values.map { it.slots }
                val slotsTrigger: Flow<Unit> =
                    if (slotFlows.isEmpty()) flowOf(Unit) else combine(slotFlows) { }
                combine(
                    registry.devices,
                    hub.bindings,
                    hub.connections,
                    slotsTrigger,
                ) { devs, bindings, summaries, _ -> BindingState(devs, bindings, summaries) }
            }

        // Read each live source once into a flat snapshot so reconcileSlots is pure; the resolved
        // ops are then executed against the native/hub side effects exactly as before.
        private fun satelliteSlotSnapshots(connIds: Set<String>): Map<String, SatelliteSlotSnapshot> =
            connIds
                .mapNotNull { cid ->
                    satellite.get(cid)?.let { conn -> cid to SatelliteSlotSnapshot(conn.handle, conn.slots.value) }
                }.toMap()

        private fun moonlightLiveIds(connIds: Set<String>): MutableSet<String> = connIds.filterTo(mutableSetOf()) { isMoonlightLive(it) }

        private fun isMoonlightLive(connId: String): Boolean = moonlight.get(connId)?.state?.value == MoonlightSessionState.Live

        private fun moonlightPadNumbers(liveIds: Set<String>): Map<String, Int> =
            liveIds
                .flatMap { cid ->
                    moonlight
                        .get(cid)
                        ?.pads
                        ?.value
                        .orEmpty()
                        .values
                }.associate { it.slotId to it.number }

        private fun push(state: BindingState) {
            val ops = reconcile(state)
            // A satellite re-bind is not idempotent on the native side: bindPhysicalSlotSatellite
            // re-runs syncSlotBaseline, which resets the device to neutral and publishes it,
            // briefly releasing every held button and trigger until the next physical report.
            // push() fires on every upstream re-emit (a few Hz), so replaying an unchanged bind
            // makes held inputs flicker. Binds identical to the one already applied are dropped;
            // changed binds go through.
            val deduped = dedupeBindOps(ops, lastAppliedBinds)
            for (op in deduped.ops) execute(op)
            lastAppliedBinds = deduped.applied
            lastBoundDeviceIds = state.devices.keys
        }

        private fun reconcile(state: BindingState): List<BindOp> {
            val referencedConnIds = state.bindings.values.toSet()
            val liveIds = moonlightLiveIds(referencedConnIds)
            return reconcileSlots(
                present = state.devices.keys,
                lastBound = lastBoundDeviceIds,
                bindings = state.bindings,
                summaries = state.summaries,
                perConnectionSlotInfo = satelliteSlotSnapshots(referencedConnIds),
                btConnectedIds = referencedConnIds.filterTo(mutableSetOf()) { bt.isConnected(it) },
                moonlightLiveIds = liveIds,
                moonlightPadNumbers = moonlightPadNumbers(liveIds),
            )
        }

        private fun execute(op: BindOp) {
            when (op) {
                is BindOp.Unbind -> {
                    PhysicalSlotNative.unbindPhysicalSlot(op.deviceId)
                    // The slot stopped streaming (unbound, host gone, or the device departed): give
                    // any framework light bar back. A no-op for a device the gateway never lit.
                    frameworkLights.release(op.deviceId)
                }
                is BindOp.Forget -> PhysicalSlotNative.forgetPhysicalDevice(op.deviceId)
                is BindOp.ReleaseHubBinding -> hub.unbind(op.deviceId.toString())
                is BindOp.BindSatellite ->
                    PhysicalSlotNative.bindPhysicalSlotSatellite(op.deviceId, op.handle, op.controllerIndex)
                is BindOp.BindBluetooth -> PhysicalSlotNative.bindPhysicalSlotBluetooth(op.deviceId, op.connectionId)
                is BindOp.BindMoonlight ->
                    PhysicalSlotNative.bindPhysicalSlotMoonlight(op.deviceId, op.connectionId, op.controllerNumber)
            }
        }
    }
