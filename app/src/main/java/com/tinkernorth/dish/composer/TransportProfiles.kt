// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature

object TransportProfiles {
    fun forKind(kind: ConnectionKind): CapabilitySet =
        when (kind) {
            // Everything except trigger rumble, which has no satellite wire message
            // (no virtual-pad backend can source it).
            ConnectionKind.SATELLITE ->
                CapabilitySet(Feature.entries.toSet() - Feature.TRIGGER_RUMBLE)
            // The phone advertises a fixed HID gamepad with no return channel, so nothing else crosses.
            ConnectionKind.BLUETOOTH -> CapabilitySet.of(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS)
            // The Moonlight control stream carries the emulated pad whole: input, motion,
            // touch and battery out, and rumble/trigger-rumble/LED events back. Mouse
            // rides the same stream natively (MOUSE_MOVE_REL/BUTTON/SCROLL), no
            // advertisement needed. No adaptive-trigger, player-LED or controller-audio
            // events exist in the Moonlight control protocol (it has no microphone
            // channel at all), so those stay satellite-only.
            ConnectionKind.MOONLIGHT ->
                CapabilitySet.of(
                    Feature.GAMEPAD,
                    Feature.ANALOG_TRIGGERS,
                    Feature.MOTION,
                    Feature.TOUCHPAD,
                    Feature.MOUSE,
                    Feature.BATTERY,
                    Feature.RUMBLE,
                    Feature.TRIGGER_RUMBLE,
                    Feature.LIGHTBAR,
                )
        }
}
