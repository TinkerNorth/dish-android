// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.net.moonlight.MoonlightControlProtocol
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType

object MoonlightCatalog {
    val HOST_LAYER =
        CapabilitySet.of(
            Feature.GAMEPAD,
            Feature.ANALOG_TRIGGERS,
            Feature.MOTION,
            Feature.TOUCHPAD,
            Feature.RUMBLE,
            Feature.LIGHTBAR,
        )

    fun typeCapabilities(type: Int): CapabilitySet =
        when (type) {
            MoonlightEmulatedType.PLAYSTATION ->
                padType(Feature.RUMBLE, Feature.MOTION, Feature.TOUCHPAD, Feature.LIGHTBAR)
            else -> padType(Feature.RUMBLE)
        }

    fun sourceBits(caps: CapabilitySet): Int {
        var bits = 0
        if (Feature.ANALOG_TRIGGERS in caps) bits = bits or MoonlightControlProtocol.CAP_ANALOG_TRIGGERS
        if (Feature.RUMBLE in caps) {
            bits = bits or MoonlightControlProtocol.CAP_RUMBLE or MoonlightControlProtocol.CAP_TRIGGER_RUMBLE
        }
        if (Feature.TOUCHPAD in caps) bits = bits or MoonlightControlProtocol.CAP_TOUCHPAD
        if (Feature.MOTION in caps) {
            bits = bits or MoonlightControlProtocol.CAP_ACCELEROMETER or MoonlightControlProtocol.CAP_GYRO
        }
        if (Feature.LIGHTBAR in caps) bits = bits or MoonlightControlProtocol.CAP_RGB_LED
        return bits
    }

    fun capabilityBits(
        type: Int,
        caps: CapabilitySet,
    ): Int = MoonlightEmulatedType.capabilityBits(type, sourceBits(caps))

    private fun padType(vararg padFeatures: Feature): CapabilitySet =
        CapabilitySet(setOf(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS) + padFeatures)
}
