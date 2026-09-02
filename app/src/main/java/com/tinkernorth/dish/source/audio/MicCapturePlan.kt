// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

/** One emulated pad's microphone endpoint: the slot to capture for, on the session that carries it. */
data class MicCaptureTarget(
    val slotId: String,
    val connectionId: String,
)

/**
 * Everything the eligibility rule knows about one slot, flattened out of the capability model and
 * the connection hub so the rule itself stays pure.
 *
 * [micEnabled] is the composed answer, not the raw toggle: the whole path has to carry a
 * microphone (an audio-capable emulated type, on a host with controller audio on, behind an input
 * that can capture) AND the user has to have switched it on.
 */
data class MicSlotInput(
    val slotId: String,
    val connectionId: String?,
    val streaming: Boolean,
    val micEnabled: Boolean,
    val muted: Boolean,
)

/**
 * What the capture engine should be doing right now.
 *
 * Two sets, because the two consumers ask different questions. [armed] is "this session has a
 * microphone in it", which is what decides whether the foreground service claims its microphone
 * type; it deliberately ignores mute, since a mute is a moment-to-moment control and dropping the
 * service type on every toggle would risk not getting it back (Android 12+ only lets a
 * microphone-typed service start while the app is in the foreground). [delivering] is "capture and
 * send, now", which is [armed] minus everything the user muted.
 */
data class MicCapturePlan(
    val armed: Set<MicCaptureTarget>,
    val delivering: Set<MicCaptureTarget>,
) {
    /** There is a microphone in this session, muted or not: what the service type follows. */
    val arming: Boolean get() = armed.isNotEmpty()

    /** Capture and send, now: what the engine follows. */
    val capturing: Boolean get() = delivering.isNotEmpty()

    companion object {
        val IDLE = MicCapturePlan(armed = emptySet(), delivering = emptySet())
    }
}

/**
 * The mic eligibility rule, in one place and with nothing else in it.
 *
 * A slot captures only where ALL of these hold: it is bound to a live satellite session, the whole
 * capability path carries a microphone and the user switched it on, RECORD_AUDIO is granted, and
 * the slot is not muted. Each of the four can move independently at runtime, and any one of them
 * going false has to stop the microphone, which is why this is a rule and not four scattered
 * guards.
 *
 * The permission is checked here rather than only at the AudioRecord: a grant revoked in system
 * settings while the app is backgrounded would otherwise leave a capture running against a
 * recorder that silently yields nothing, and the app would report a live microphone it does not
 * have.
 */
object MicCapturePolicy {
    fun plan(
        slots: Collection<MicSlotInput>,
        permissionGranted: Boolean,
    ): MicCapturePlan {
        if (!permissionGranted) return MicCapturePlan.IDLE
        val armed = LinkedHashSet<MicCaptureTarget>()
        val delivering = LinkedHashSet<MicCaptureTarget>()
        for (slot in slots) {
            val connectionId = slot.connectionId ?: continue
            if (!slot.streaming || !slot.micEnabled) continue
            val target = MicCaptureTarget(slot.slotId, connectionId)
            armed += target
            if (!slot.muted) delivering += target
        }
        return MicCapturePlan(armed = armed, delivering = delivering)
    }
}
