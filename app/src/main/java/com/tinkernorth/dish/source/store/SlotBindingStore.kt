// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SlotBindingStore
    @Inject
    constructor() : AbstractStateSource<Map<String, String>>(emptyMap()) {
        val bindings get() = state

        fun connectionFor(slotId: String): String? = state.value[slotId]

        fun slotsFor(connectionId: String): List<String> =
            state.value.entries
                .filter { it.value == connectionId }
                .map { it.key }

        // No side effects: caller drives detach/attach on the satellite connection.
        fun bind(
            slotId: String,
            connectionId: String,
        ) {
            setState { it + (slotId to connectionId) }
        }

        fun unbind(slotId: String) {
            setState { if (slotId in it) it - slotId else it }
        }

        // One emission: downstream composers must never observe both keys bound at once.
        fun migrate(
            fromSlotId: String,
            toSlotId: String,
        ) {
            setState { withSlotMigrated(it, fromSlotId, toSlotId) }
        }

        fun replace(
            slotId: String,
            connectionId: String,
        ): String? {
            val prior = getAndSetState { current -> current + (slotId to connectionId) }
            return prior[slotId]
        }
    }

// A slot with no binding hands back the same map, so there is nothing to publish.
internal fun withSlotMigrated(
    bindings: Map<String, String>,
    fromSlotId: String,
    toSlotId: String,
): Map<String, String> {
    val connectionId = bindings[fromSlotId] ?: return bindings
    val unbound = bindings - fromSlotId
    return unbound + (toSlotId to connectionId)
}
