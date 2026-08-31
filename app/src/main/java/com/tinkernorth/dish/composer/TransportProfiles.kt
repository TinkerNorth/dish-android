// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature

object TransportProfiles {
    fun forKind(kind: ConnectionKind): CapabilitySet =
        when (kind) {
            ConnectionKind.SATELLITE -> CapabilitySet(Feature.entries.toSet())
            // The phone advertises a fixed HID gamepad with no return channel, so nothing else crosses.
            ConnectionKind.BLUETOOTH -> CapabilitySet.of(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS)
            // The Moonlight control stream carries the emulated pad whole: input out, and
            // rumble/trigger/motion/LED events back. Mouse rides the same stream natively
            // (MOUSE_MOVE_REL/BUTTON/SCROLL), no advertisement needed.
            ConnectionKind.MOONLIGHT ->
                CapabilitySet.of(
                    Feature.GAMEPAD,
                    Feature.ANALOG_TRIGGERS,
                    Feature.MOTION,
                    Feature.TOUCHPAD,
                    Feature.MOUSE,
                    Feature.RUMBLE,
                    Feature.LIGHTBAR,
                )
        }
}
