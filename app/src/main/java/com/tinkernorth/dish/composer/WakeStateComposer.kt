// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.abstracts.AbstractComposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Derived snapshot of the "is anything streaming?" state for the wake-lock + screen-on
 * machinery.
 *
 *   - [streamingSlotCount] — number of slots currently routed to a live connection
 *   - [shouldKeepScreenOn] — true iff [streamingSlotCount] > 0
 *
 * Both are pure functions of [com.tinkernorth.dish.composer.ConnectionHub]'s
 * `bindings` and `connections` flows. The Composer pattern fits exactly: no own
 * lifecycle, no events — just `combine(...)` into a `StateFlow<WakeState>`.
 *
 * The side effect (acquiring the actual [android.os.PowerManager.PARTIAL_WAKE_LOCK])
 * lives in [WakeStateController], which is a `AbstractStateSource` that subscribes to
 * this composer's [state] and toggles the lock based on it. That separation keeps
 * the derivation testable in pure-JVM unit tests (no `PowerManager` mock needed)
 * and the side-effect runner narrow.
 */
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
        private val hub: ConnectionHub,
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
            }
    }
