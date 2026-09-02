// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

/**
 * What the app-wide mic surfaces (the floating chip on every screen, the streaming notification)
 * say about the microphone. Three states, because that is all a user needs to account for a
 * microphone: there isn't one, there is one and it is hot, there is one and it is silenced.
 */
enum class MicIndicatorState {
    /** No armed microphone anywhere: nothing to account for, so no surface shows at all. */
    HIDDEN,

    /** At least one armed slot is delivering: the microphone is hot right now. */
    LIVE,

    /** Armed but every armed slot is muted: a microphone exists and none of it is sent. */
    MUTED,
}

/**
 * The indicator rule and its one control, kept pure like [MicCapturePolicy] beside it.
 *
 * Both read only [MicCapturePlan]: the plan already folds [com.tinkernorth.dish.source.store.MicMuteStore]
 * into `delivering` (armed minus muted, per slot), so reading the store again here would just
 * race the composer that did the folding and could momentarily disagree with what the capture
 * engine is actually doing. The plan is the one truth both consumers follow.
 */
object MicIndicatorPolicy {
    fun of(plan: MicCapturePlan): MicIndicatorState =
        when {
            !plan.arming -> MicIndicatorState.HIDDEN
            plan.capturing -> MicIndicatorState.LIVE
            else -> MicIndicatorState.MUTED
        }

    /**
     * What one tap on an app-wide mic surface does: silence every armed slot, or bring every
     * armed slot back. All-or-nothing on purpose. The surfaces show ONE state for the whole
     * device, so their control must leave the device in one state: a tap on LIVE (even a mixed
     * live-and-muted set) mutes everything, and the next tap unmutes everything. Null when
     * there is nothing armed to act on.
     */
    fun toggleAll(plan: MicCapturePlan): MicMuteAllOrder? {
        if (!plan.arming) return null
        return MicMuteAllOrder(
            slotIds = plan.armed.mapTo(LinkedHashSet()) { it.slotId },
            muted = plan.capturing,
        )
    }
}

/** Every armed slot's mute, set to one value: what [MicIndicatorPolicy.toggleAll] decided. */
data class MicMuteAllOrder(
    val slotIds: Set<String>,
    val muted: Boolean,
)
