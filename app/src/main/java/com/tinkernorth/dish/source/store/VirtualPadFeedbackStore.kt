// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Host-driven feedback for the on-screen virtual pad: the phone has no
 * lightbar or player-LED hardware, so the skin renders them instead. State is
 * last-known and deliberately survives the overlay closing — the host coalesces
 * these messages and will not resend an unchanged value, so forgetting it here
 * would blank the skin until the game next changes something.
 */
data class VirtualPadFeedback(
    // ARGB color, null until a host has set one.
    val lightbarColor: Int? = null,
    // Bit 0 = leftmost LED, mirroring the wire's ledMask.
    val playerLedMask: Int = 0,
    // Whether the game holds a non-neutral adaptive-trigger effect per trigger.
    val leftTriggerEffect: Boolean = false,
    val rightTriggerEffect: Boolean = false,
    // The HOST's mic lamp, in the wire's own states: 0 off, 1 on, 2 pulse (MSG_MIC_LED).
    // Deliberately the host's alone: the on-screen mute button draws the LOCAL mute state
    // (MicMuteStore) on its own face, so this lamp is secondary "what the host thinks" info
    // and a host repaint can never make a muted microphone look live. The two disagree
    // whenever host-side software, seeing the wire's held mute-state bit as a held button,
    // toggles its own mute out of phase with ours; the face tells the truth regardless.
    val micLedState: Int = MIC_LED_OFF,
)

const val MIC_LED_OFF = 0
const val MIC_LED_ON = 1
const val MIC_LED_PULSE = 2

@Singleton
class VirtualPadFeedbackStore
    @Inject
    constructor() {
        private val _state = MutableStateFlow(VirtualPadFeedback())
        val state: StateFlow<VirtualPadFeedback> = _state.asStateFlow()

        fun setLightbar(
            r: Int,
            g: Int,
            b: Int,
        ) {
            _state.value =
                _state.value.copy(
                    lightbarColor = 0xFF000000.toInt() or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF),
                )
        }

        fun setPlayerLeds(ledMask: Int) {
            _state.value = _state.value.copy(playerLedMask = ledMask and 0x1F)
        }

        fun setTriggerEffects(
            leftActive: Boolean,
            rightActive: Boolean,
        ) {
            _state.value = _state.value.copy(leftTriggerEffect = leftActive, rightTriggerEffect = rightActive)
        }

        /**
         * The host's lamp (MSG_MIC_LED), already validated natively to 0/1/2. The ONLY writer:
         * the local mute paints the mute button's own face straight from MicMuteStore instead
         * of writing here, so the lamp always means "the host said this" and nothing else.
         */
        fun setMicLed(state: Int) {
            _state.value = _state.value.copy(micLedState = state.coerceIn(MIC_LED_OFF, MIC_LED_PULSE))
        }
    }
