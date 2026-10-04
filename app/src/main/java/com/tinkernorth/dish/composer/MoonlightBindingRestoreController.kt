// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import android.util.Log
import com.tinkernorth.dish.architecture.abstracts.AbstractController
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.repository.RememberedBinding
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

// One pad to put back on its host: the slot it holds now, and the binding it was remembered with.
data class BindingRestore(
    val slotId: String,
    val hostId: String,
    val controllerType: Int,
)

// Which present pads go back on a Moonlight host. A pad goes back when it is here with an identity,
// is bound to nothing, and was remembered for a host that is still remembered. A pad on its way out
// (disconnect countdown) or mid-claim is left to the change that follows it: a bind now would be
// undone by its departure, or moved by the claim. Pure, so the rule is testable without the stores.
fun planMoonlightBindingRestore(
    devices: Collection<PhysicalGamepadRegistry.Device>,
    bindings: Map<String, String>,
    remembered: List<RememberedBinding>,
    knownHostIds: Set<String>,
): List<BindingRestore> {
    val byDescriptor = remembered.filter { it.hostId in knownHostIds }.associateBy { it.descriptor }
    return devices
        .filter { it.descriptor.isNotEmpty() && it.disconnectingTimeLeftSec == null && !it.transitioning }
        .filter { it.id.toString() !in bindings }
        .sortedBy { it.id }
        .mapNotNull { device ->
            byDescriptor[device.descriptor]?.let { BindingRestore(device.id.toString(), it.hostId, it.controllerType) }
        }
}

/**
 * Puts a pad back on the Moonlight host it was bound to once it is here again: the app restarted
 * under it, or it reconnected under a new device id. Windows reattaches the same way when the pad
 * appears. The memory is written and dropped by [ConnectionCoordinator] through the manager, which
 * is what keeps a departure apart from the user's unbind. Runs on the process lifecycle like the
 * binding observer
 * and derives from the world on every (re)start, so a pad that arrived while the app was stopped
 * is bound on the next start.
 */
@Singleton
class MoonlightBindingRestoreController
    @Inject
    constructor(
        private val registry: PhysicalGamepadRegistry,
        private val hub: ConnectionCoordinator,
        private val moonlight: MoonlightConnectionManager,
        scope: CoroutineScope,
    ) : AbstractController<List<BindingRestore>>(scope) {
        override fun upstream(): Flow<List<BindingRestore>> =
            combine(registry.devices, hub.bindings, moonlight.remembered) { devices, bindings, hosts ->
                planMoonlightBindingRestore(devices.values, bindings, moonlight.rememberedBindings, hosts.mapTo(mutableSetOf()) { it.id })
            }

        override fun apply(value: List<BindingRestore>) {
            for (restore in value) {
                Log.i(TAG, "putting slot ${restore.slotId} back on ${restore.hostId}")
                hub.bind(restore.slotId, restore.hostId, restore.controllerType)
            }
        }

        private companion object {
            const val TAG = "MoonlightBindingRestore"
        }
    }
