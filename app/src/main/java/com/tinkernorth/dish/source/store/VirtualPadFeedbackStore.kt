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
    // The mic-mute lamp, in the wire's own states: 0 off, 1 on, 2 pulse (MSG_MIC_LED).
    // LAST WRITER WINS between two sources, which is the DualSense's own behaviour: muting
    // locally lights it immediately so the button never looks dead, and a game that drives the
    // lamp afterwards owns it from then on. It is therefore the LAMP and not the mute state:
    // what actually gates capture is MicMuteStore.
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

        /** The host's lamp (MSG_MIC_LED), already validated natively to 0/1/2. */
        fun setMicLed(state: Int) {
            _state.value = _state.value.copy(micLedState = state.coerceIn(MIC_LED_OFF, MIC_LED_PULSE))
        }

        /**
         * The local mute state, painted immediately so the on-screen mute button never looks dead
         * while a host that may never send MSG_MIC_LED decides what to do. A later host lamp
         * overrides it, and the mute itself is unaffected either way.
         */
        fun setLocalMicMute(muted: Boolean) {
            setMicLed(if (muted) MIC_LED_ON else MIC_LED_OFF)
        }
    }
