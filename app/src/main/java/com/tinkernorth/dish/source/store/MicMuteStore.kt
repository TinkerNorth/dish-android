// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-slot mic mute, the client-side half of the privacy invariant: while a slot is muted no
 * MSG_MIC_AUDIO packet leaves the device at all. Muting stops the capture delivery outright rather
 * than sending silence, so "muted" is not a promise about the contents of a stream, it is the
 * absence of one.
 *
 * One holder for every slot, because both mute controls mean the same thing and a user should not
 * have to remember which one they used: the on-screen pad's mute button writes here, and so does a
 * Direct-claimed DualSense's own mute button (through [com.tinkernorth.dish.hotpath.input.MicMuteBridge],
 * where the report decoder owns the latch that folds the state onto the wire).
 *
 * Deliberately NOT persisted, unlike the mic toggle beside it. Mute is a live control over a
 * session the way it is on the hardware, where it clears when the pad powers down; the durable
 * "do not capture" answer is [MicEnabledStore], which is off by default and survives restarts. A
 * mute that outlived the process would also be invisible until the next session armed a
 * microphone (every mute surface, the app-wide chip included, only shows while one is armed),
 * which is the worst possible place to hide a microphone that looks broken.
 */
@Singleton
class MicMuteStore
    @Inject
    constructor() : AbstractStateSource<Map<String, Boolean>>(emptyMap()) {
        /** Absent means unmuted: nothing has asked this slot to stop capturing. */
        fun isMuted(slotId: String): Boolean = state.value[slotId] ?: DEFAULT_MUTED

        fun setMuted(
            slotId: String,
            muted: Boolean,
        ) {
            setState { if (it[slotId] == muted) it else it + (slotId to muted) }
        }

        /** What both mute buttons do: the state is a toggle, the control is a press. */
        fun toggle(slotId: String): Boolean {
            val next = !isMuted(slotId)
            setMuted(slotId, next)
            return next
        }

        /**
         * A Direct-claimed pad's own mute button flipped natively. The synthetic USB device id IS
         * the slot id for a physical pad, so the mapping is the id's string form and nothing more.
         */
        fun setPadMuted(
            deviceId: Int,
            muted: Boolean,
        ) = setMuted(deviceId.toString(), muted)

        /** A slot that is gone cannot be muted; drop it so a re-bound id starts live. */
        fun forget(slotId: String) {
            setState { it - slotId }
        }

        companion object {
            const val DEFAULT_MUTED: Boolean = false
        }
    }
