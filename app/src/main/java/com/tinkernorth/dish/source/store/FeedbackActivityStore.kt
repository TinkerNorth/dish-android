// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class FeedbackKind { RUMBLE, TRIGGER_RUMBLE, LIGHTBAR, PLAYER_LEDS, TRIGGER_EFFECTS, MIC_LED }

data class FeedbackActivity(
    val lastKind: FeedbackKind,
    val atMs: Long,
    val count: Long,
)

// Plain map, not a StateFlow: the receive thread notes every host packet and nothing needs to
// react per packet; the diagnostics tick reads a snapshot.
@Singleton
class FeedbackActivityStore
    @Inject
    constructor() {
        private val bySlot = ConcurrentHashMap<String, FeedbackActivity>()

        fun note(
            slotId: String,
            kind: FeedbackKind,
            nowMs: Long = System.currentTimeMillis(),
        ) {
            if (slotId.isEmpty()) return
            bySlot.compute(slotId) { _, prev -> FeedbackActivity(kind, nowMs, (prev?.count ?: 0L) + 1) }
        }

        fun snapshot(): Map<String, FeedbackActivity> = HashMap(bySlot)

        fun forget(slotId: String) {
            bySlot.remove(slotId)
        }
    }
