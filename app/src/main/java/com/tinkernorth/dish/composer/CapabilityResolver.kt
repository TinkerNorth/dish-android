// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.CatalogTypeDto
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.repository.TouchpadModeValue

// Reducer: pure layer math. The composer reads live state once and hands the four layers in here.
object CapabilityResolver {
    @Suppress("LongParameterList")
    fun resolve(
        controller: CapabilitySet,
        transport: CapabilitySet,
        type: CapabilitySet,
        host: CapabilitySet,
        userEnabled: CapabilitySet,
        runtimeDown: CapabilitySet,
    ): SlotCapabilities =
        SlotCapabilities(
            controller = controller,
            transport = transport,
            type = type,
            host = host,
            userEnabled = userEnabled,
            runtimeDown = runtimeDown,
        )

    fun typeCapabilities(catalogType: CatalogTypeDto): CapabilitySet {
        // GAMEPAD is intrinsic to every pad; MOUSE/KEYBOARD are host-injected so the
        // type layer passes them through (the host layer gates them). The rest come
        // from the catalog's per-type feature flags.
        val out = mutableSetOf(Feature.GAMEPAD, Feature.MOUSE, Feature.KEYBOARD)
        for (feature in Feature.entries) {
            val slug = feature.catalogSlug ?: continue
            if (catalogType.features[slug]?.supported == true) out += feature
        }
        return CapabilitySet(out)
    }

    fun userEnabledCapabilities(
        motionOn: Boolean,
        rumbleOn: Boolean,
        touchpadMode: String,
    ): CapabilitySet {
        val out = mutableSetOf(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS)
        if (motionOn) out += Feature.MOTION
        if (rumbleOn) out += Feature.RUMBLE
        if (touchpadMode == TouchpadModeValue.DS4) out += Feature.TOUCHPAD
        if (touchpadMode == TouchpadModeValue.MOUSE) out += Feature.MOUSE
        return CapabilitySet(out)
    }
}
