// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.abstracts.AbstractComposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

data class WakeState(
    val streamingSlotCount: Int,
    val shouldKeepScreenOn: Boolean,
) {
    companion object {
        val Idle = WakeState(streamingSlotCount = 0, shouldKeepScreenOn = false)
    }
}

@Singleton
class WakeStateComposer
    @Inject
    constructor(
        private val hub: ConnectionCoordinator,
        scope: CoroutineScope,
    ) : AbstractComposer<WakeState>(scope, WakeState.Idle) {
        override fun upstream(): Flow<WakeState> =
            combine(hub.bindings, hub.connections) { bindings, conns ->
                val byId = conns.associateBy { it.id }
                val count =
                    bindings.values.count { cid ->
                        byId[cid]?.live == LinkState.Connected
                    }
                WakeState(streamingSlotCount = count, shouldKeepScreenOn = count > 0)
            }.distinctUntilChanged()
    }
