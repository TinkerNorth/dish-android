// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.model

import com.tinkernorth.dish.core.net.DISH_PROTOCOL_EXTENDED_MOUSE
import com.tinkernorth.dish.core.net.DishProtocolCompat
import com.tinkernorth.dish.core.net.dishProtocolCompatFor

enum class Direction { SEND, RECEIVE }

enum class Feature(
    val direction: Direction,
    val catalogSlug: String?,
) {
    GAMEPAD(Direction.SEND, null),
    ANALOG_TRIGGERS(Direction.SEND, "analogTriggers"),
    MOTION(Direction.SEND, "motion"),
    TOUCHPAD(Direction.SEND, "touchpad"),

    MOUSE(Direction.SEND, null),

    // Modelled but never user-offered: there is no phone-side keyboard source.
    KEYBOARD(Direction.SEND, null),

    // Over Moonlight this maps to CAP_BATTERY in the arrival advertisement.
    BATTERY(Direction.SEND, null),
    RUMBLE(Direction.RECEIVE, "rumble"),

    // Xbox One impulse-trigger motors; Moonlight-only on the wire (RUMBLE_TRIGGERS).
    TRIGGER_RUMBLE(Direction.RECEIVE, null),

    // Actuated on a Direct-claimed DS4/DualSense, and on a Bluetooth one through the Android
    // lights API (API 31+); a USB framework pad's bar is unreachable there and comes from Direct.
    LIGHTBAR(Direction.RECEIVE, "lightbar"),

    // Replayed verbatim into a Direct-claimed DualSense (satellite MSG_TRIGGER_EFFECTS only).
    TRIGGER_EFFECTS(Direction.RECEIVE, "triggerEffects"),

    // Player-indicator LEDs (DualSense bar, Switch Pro lights), Direct path only.
    PLAYER_LEDS(Direction.RECEIVE, "playerLeds"),

    // Also gates the mic-mute lamp coming back (MSG_MIC_LED).
    MIC(Direction.SEND, "mic"),

    SPEAKER(Direction.RECEIVE, "speaker"),

    // Protocol 3: channels 3/4 of the DualSense's audio OUT stream. Advertised only where
    // that 4-channel endpoint is reachable at full width; the host reduces the lanes to
    // RUMBLE everywhere else. Rides the speaker toggle, being the same endpoint.
    HAPTIC_AUDIO(Direction.RECEIVE, "hapticAudio"),
}

@JvmInline
value class CapabilitySet(
    val features: Set<Feature>,
) {
    operator fun contains(feature: Feature): Boolean = feature in features

    infix fun intersect(other: CapabilitySet): CapabilitySet = CapabilitySet(features intersect other.features)

    operator fun minus(other: CapabilitySet): CapabilitySet = CapabilitySet(features - other.features)

    fun sends(): List<Feature> = features.filter { it.direction == Direction.SEND }

    fun receives(): List<Feature> = features.filter { it.direction == Direction.RECEIVE }

    companion object {
        val EMPTY = CapabilitySet(emptySet())

        fun of(vararg features: Feature): CapabilitySet = CapabilitySet(features.toSet())
    }
}

// Every host advertises these; the per-type surfaces are the type layer's job and the host layer
// passes them through. Only the flags below toCapabilitySet vary per host.
private val HOST_WIDE_FEATURES =
    setOf(
        Feature.GAMEPAD,
        Feature.ANALOG_TRIGGERS,
        Feature.MOTION,
        Feature.TOUCHPAD,
        Feature.BATTERY,
        Feature.LIGHTBAR,
        Feature.TRIGGER_EFFECTS,
        Feature.PLAYER_LEDS,
    )

data class SlotCapabilities(
    val controller: CapabilitySet,
    val transport: CapabilitySet,
    val type: CapabilitySet,
    val host: CapabilitySet,
    val userEnabled: CapabilitySet,
    val runtimeDown: CapabilitySet,
) {
    val available: CapabilitySet get() = controller intersect transport intersect type intersect host
    val enabled: CapabilitySet get() = available intersect userEnabled
    val live: CapabilitySet get() = enabled - runtimeDown

    fun isAvailable(feature: Feature): Boolean = feature in available

    fun isEnabled(feature: Feature): Boolean = feature in enabled

    fun userWants(feature: Feature): Boolean = feature in userEnabled

    fun inputOk(feature: Feature): Boolean = feature in controller

    fun destinationOk(feature: Feature): Boolean = feature in transport && feature in host

    fun typeOk(feature: Feature): Boolean = feature in type

    companion object {
        val NONE =
            SlotCapabilities(
                controller = CapabilitySet.EMPTY,
                transport = CapabilitySet.EMPTY,
                type = CapabilitySet.EMPTY,
                host = CapabilitySet.EMPTY,
                userEnabled = CapabilitySet.EMPTY,
                runtimeDown = CapabilitySet.EMPTY,
            )
    }
}

data class HostFeatureSet(
    val hasCatalog: Boolean,
    val mouseControl: Boolean,
    val keyboardControl: Boolean,
    val rumbleReturn: Boolean,
    // Two fields and not one verdict: the host moves the directions independently, and a
    // single boolean would offer a microphone whose frames a speaker-only host drops.
    val controllerMic: Boolean = false,
    val controllerSpeaker: Boolean = false,
    val controllerHapticAudio: Boolean = false,
    // The version the satellite advertised; 0 = never fetched.
    val protocolVersion: Int = 0,
) {
    val extendedMouse: Boolean get() = mouseControl && protocolVersion >= DISH_PROTOCOL_EXTENDED_MOUSE

    val compat: DishProtocolCompat get() = dishProtocolCompatFor(protocolVersion.takeIf { it > 0 })

    fun toCapabilitySet(): CapabilitySet {
        val out = HOST_WIDE_FEATURES.toMutableSet()
        if (mouseControl) out += Feature.MOUSE
        if (keyboardControl) out += Feature.KEYBOARD
        if (rumbleReturn) out += Feature.RUMBLE
        // Audio is the exception: it is the one host-wide runtime switch.
        if (controllerMic) out += Feature.MIC
        if (controllerSpeaker) out += Feature.SPEAKER
        if (controllerHapticAudio) out += Feature.HAPTIC_AUDIO
        return CapabilitySet(out)
    }

    companion object {
        // Audio waits for a probe rather than being assumed: offering a microphone that
        // cannot land would cost the user a permission prompt for nothing.
        val SATELLITE_DEFAULT =
            HostFeatureSet(
                hasCatalog = false,
                mouseControl = true,
                keyboardControl = false,
                rumbleReturn = true,
                controllerMic = false,
                controllerSpeaker = false,
            )

        fun fromCatalog(catalog: CatalogDto): HostFeatureSet =
            HostFeatureSet(
                hasCatalog = true,
                mouseControl = catalog.hostFeatures["mouseControl"]?.supported == true,
                keyboardControl = catalog.hostFeatures["keyboardControl"]?.supported == true,
                rumbleReturn = catalog.hostFeatures["rumble"]?.supported ?: true,
                // No audio here: the catalog is cached on server version + locale, so an
                // install-time switch must not move it. SatelliteHostFeaturesStore carries
                // the probed directions across a catalog write instead.
                protocolVersion = catalog.protocolVersion,
            )

        // Pre-bind, pre-catalog host read (GET /api/server/capabilities). Caller must
        // gate on host.catalog.supported first: an older satellite omits the block, and
        // mapping its all-false default would wrongly report everything unsupported.
        fun fromServerCapabilities(caps: ServerCapabilitiesDto): HostFeatureSet {
            // The fallback for a satellite that carries audio but predates the host block.
            val perBackend = caps.backends.any { it.available && it.audio }
            // Nullable because an ABSENT block is unknown, not off.
            val block = caps.controllerAudio
            return HostFeatureSet(
                hasCatalog = caps.host.catalog.supported,
                mouseControl = caps.host.mouseControl.supported,
                keyboardControl = caps.host.keyboardControl.supported,
                rumbleReturn = caps.host.rumble.supported,
                controllerMic = block?.let { it.enabled && it.mic } ?: perBackend,
                controllerSpeaker = block?.let { it.enabled && it.speaker } ?: perBackend,
                controllerHapticAudio = block?.let { it.enabled && it.hapticAudio } ?: false,
                protocolVersion = caps.protocolVersion,
            )
        }
    }
}
