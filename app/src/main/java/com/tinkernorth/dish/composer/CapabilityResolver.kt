// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.CatalogFeatureDto
import com.tinkernorth.dish.core.model.CatalogTypeDto
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.net.ControllerDescriptor
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
        // type layer passes them through (the host layer gates them). BATTERY and
        // TRIGGER_RUMBLE have no catalog slug (battery is always accepted, trigger
        // rumble is transport-gated), so they pass here too. The rest come
        // from the catalog's per-type feature flags.
        val out =
            mutableSetOf(
                Feature.GAMEPAD,
                Feature.MOUSE,
                Feature.KEYBOARD,
                Feature.BATTERY,
                Feature.TRIGGER_RUMBLE,
            )
        for (feature in Feature.entries) {
            val slug = feature.catalogSlug ?: continue
            val dto = catalogType.features[slug]
            if (dto?.supported == true && typeOffersFeature(feature, dto)) out += feature
        }
        return CapabilitySet(out)
    }

    // Feature.TOUCHPAD is the DS4 PAD mode specifically: offer it only when the type
    // advertises that mode, so a touchpad-bearing type with no "ds4" lineage (mouse-only,
    // or a future pad with a different mode) gates the pad off. A pre-modes catalog omits
    // `modes` → empty means pad-capable (back-compat). Every other feature has no
    // sub-mode gate, so it passes once `supported`.
    private fun typeOffersFeature(
        feature: Feature,
        dto: CatalogFeatureDto,
    ): Boolean = feature != Feature.TOUCHPAD || dto.modes.isEmpty() || TouchpadModeValue.DS4 in dto.modes

    // The wire describes the EMULATED pad the satellite must plug: a type-driven base
    // (analog triggers + rumble, always) plus motion gated on the phone/controller gyro
    // and the user toggle. This is a DIFFERENT projection from `available`: the descriptor
    // must keep advertising motion across a link drop (recovery without re-handshake).
    // The feedback caps (lightbar/triggerEffects/playerLeds) advertise the ACTUATOR:
    // they ride only when the bound input can land them (a Direct-claimed pad with the
    // hardware), independent of the type layer, since a satellite that cannot source
    // them simply never sends the message.
    fun wireCaps(slot: SlotCapabilities): Int {
        var caps = ControllerDescriptor.CAP_ANALOG_TRIGGERS or ControllerDescriptor.CAP_RUMBLE
        if (Feature.MOTION in slot.controller && Feature.MOTION in slot.userEnabled) {
            caps = caps or ControllerDescriptor.CAP_MOTION
        }
        if (Feature.LIGHTBAR in slot.controller) caps = caps or ControllerDescriptor.CAP_LIGHTBAR
        if (Feature.TRIGGER_EFFECTS in slot.controller) {
            caps = caps or ControllerDescriptor.CAP_TRIGGER_EFFECTS
        }
        if (Feature.PLAYER_LEDS in slot.controller) {
            caps = caps or ControllerDescriptor.CAP_PLAYER_LEDS
        }
        // The audio caps advertise the client's own source/actuator like the feedback
        // caps, but they follow their toggles the way motion does: capture and playback
        // are things the user switches off, and an advertised cap the client will not
        // honor would have the host stream audio into nothing (and, for `mic`, send a
        // mute lamp for a microphone that is not running).
        if (Feature.MIC in slot.controller && Feature.MIC in slot.userEnabled) {
            caps = caps or ControllerDescriptor.CAP_MIC
        }
        if (Feature.SPEAKER in slot.controller && Feature.SPEAKER in slot.userEnabled) {
            caps = caps or ControllerDescriptor.CAP_SPEAKER
        }
        return caps
    }

    // Touch, mouse, battery and the LED/trigger-effect feedback have no user toggle:
    // whatever the path can carry is simply on, so they ride here unconditionally and
    // the layer intersection does the gating. Trigger rumble is rumble, so it follows
    // the rumble toggle. Mic and speaker have toggles of their own: audio is the one
    // surface a user turns off for reasons the capability layers cannot see.
    fun userEnabledCapabilities(
        motionOn: Boolean,
        rumbleOn: Boolean,
        micOn: Boolean,
        speakerOn: Boolean,
    ): CapabilitySet {
        val out =
            mutableSetOf(
                Feature.GAMEPAD,
                Feature.ANALOG_TRIGGERS,
                Feature.TOUCHPAD,
                Feature.MOUSE,
                Feature.BATTERY,
                Feature.LIGHTBAR,
                Feature.TRIGGER_EFFECTS,
                Feature.PLAYER_LEDS,
            )
        if (motionOn) out += Feature.MOTION
        if (rumbleOn) {
            out += Feature.RUMBLE
            out += Feature.TRIGGER_RUMBLE
        }
        if (micOn) out += Feature.MIC
        if (speakerOn) out += Feature.SPEAKER
        return CapabilitySet(out)
    }
}
