// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.source.audio.MicIndicatorPolicy
import com.tinkernorth.dish.source.audio.MicIndicatorState
import com.tinkernorth.dish.source.store.MicMuteStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place the app-wide mic surfaces read their state and land their taps. The floating
 * chip on every screen and the streaming notification's mute action both come here, so a tap on
 * either does exactly the same thing and the two can never show different truths.
 *
 * A thin fold, not a composer of its own: [MicCaptureComposer] already holds the settled plan,
 * and [MicIndicatorPolicy] holds the rule. What this adds is the write half — applying a
 * toggle-all order to [MicMuteStore], whose per-slot state then flows back through the plan and
 * repaints every surface, the mute pill on the virtual pad included.
 */
@Singleton
class MicIndicatorCoordinator
    @Inject
    constructor(
        private val micCapture: MicCaptureComposer,
        private val micMute: MicMuteStore,
    ) {
        val state: Flow<MicIndicatorState> =
            micCapture.state.map(MicIndicatorPolicy::of).distinctUntilChanged()

        /**
         * Mute every armed slot, or unmute every armed slot — whichever the current plan says
         * (LIVE mutes, MUTED unmutes). A no-op with nothing armed: the surfaces are hidden then,
         * but a stale tap (a notification action racing a session teardown) must not write mutes
         * for slots that no longer capture.
         */
        fun toggleAll() {
            val order = MicIndicatorPolicy.toggleAll(micCapture.state.value) ?: return
            for (slotId in order.slotIds) micMute.setMuted(slotId, order.muted)
        }
    }
