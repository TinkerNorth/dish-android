// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ControllerTypeStore
    @Inject
    constructor() : AbstractStateSource<Map<Pair<String, String>, Int>>(emptyMap()) {
        fun typeFor(
            connectionId: String,
            slotId: String,
        ): Int? = state.value[connectionId to slotId]

        fun setType(
            connectionId: String,
            slotId: String,
            type: Int,
        ) {
            setState { it + ((connectionId to slotId) to type) }
        }

        fun setTypeIfAbsent(
            connectionId: String,
            slotId: String,
            type: Int,
        ) {
            setState {
                val key = connectionId to slotId
                if (key in it) it else it + (key to type)
            }
        }

        fun clear(
            connectionId: String,
            slotId: String,
        ) {
            setState {
                val key = connectionId to slotId
                if (key in it) it - key else it
            }
        }

        fun slotTypesFor(
            connectionId: String,
            boundSlotIds: List<String>,
        ): Map<String, Int> {
            val snapshot = state.value
            val out = mutableMapOf<String, Int>()
            for (slotId in boundSlotIds) {
                val type = snapshot[connectionId to slotId] ?: continue
                out[slotId] = type
            }
            return out
        }
    }
