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
            setState { current ->
                val connId = current[fromSlotId] ?: return@setState current
                (current - fromSlotId) + (toSlotId to connId)
            }
        }

        fun replace(
            slotId: String,
            connectionId: String,
        ): String? {
            var prior: String? = null
            setState { current ->
                prior = current[slotId]
                current + (slotId to connectionId)
            }
            return prior
        }
    }
