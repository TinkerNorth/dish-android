// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature

// Offline fallback for the two slugs the app ships art for; unknown slugs fall through to the
// server catalog (CapabilityResolver.typeCapabilities), so this never masks a richer remote type.
object BundledCatalog {
    const val SLUG_XBOX360 = "xbox360"
    const val SLUG_DS4 = "ds4"

    fun typeCapabilities(slug: String): CapabilitySet? =
        when (slug) {
            SLUG_XBOX360 -> padType(Feature.RUMBLE)
            SLUG_DS4 -> padType(Feature.RUMBLE, Feature.MOTION, Feature.TOUCHPAD, Feature.LIGHTBAR)
            else -> null
        }

    // Every emulated pad carries the gamepad axes and analog triggers; MOUSE/KEYBOARD
    // are host-injected, so the type layer passes them through for the host to gate.
    private fun padType(vararg padFeatures: Feature): CapabilitySet =
        CapabilitySet(setOf(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.MOUSE, Feature.KEYBOARD) + padFeatures)

    fun typeCapabilitiesById(typeId: Int): CapabilitySet =
        if (typeId == CONTROLLER_TYPE_PLAYSTATION) {
            typeCapabilities(SLUG_DS4)!!
        } else {
            typeCapabilities(SLUG_XBOX360)!!
        }
}
