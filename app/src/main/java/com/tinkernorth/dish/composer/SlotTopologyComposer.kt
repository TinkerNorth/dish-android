// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.abstracts.AbstractComposer
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.store.ControllerTypeStore
import com.tinkernorth.dish.source.store.SlotBindingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

// connectionId -> (slotId -> controller type). The single desired-topology truth every
// SatelliteConnection is converged to; Bluetooth bindings carry no satellite descriptor.
typealias SlotTopology = Map<String, Map<String, Int>>

fun composeSlotTopology(
    bindings: Map<String, String>,
    types: Map<Pair<String, String>, Int>,
): SlotTopology {
    val out = mutableMapOf<String, MutableMap<String, Int>>()
    for ((slotId, connId) in bindings) {
        if (!connId.startsWith(SatelliteConnection.ID_PREFIX)) continue
        out.getOrPut(connId) { mutableMapOf() }[slotId] = types[connId to slotId] ?: CONTROLLER_TYPE_XBOX
    }
    return out
}

@Singleton
class SlotTopologyComposer
    @Inject
    constructor(
        bindingStore: SlotBindingStore,
        typeStore: ControllerTypeStore,
        scope: CoroutineScope,
    ) : AbstractComposer<SlotTopology>(scope, emptyMap()) {
        private val bindings: StateFlow<Map<String, String>> = bindingStore.state
        private val types: StateFlow<Map<Pair<String, String>, Int>> = typeStore.state

        override fun upstream(): Flow<SlotTopology> = combine(bindings, types, ::composeSlotTopology).distinctUntilChanged()
    }
