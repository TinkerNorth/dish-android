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
)

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
    }
