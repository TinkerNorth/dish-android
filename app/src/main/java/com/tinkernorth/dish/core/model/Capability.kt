// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.model

import com.tinkernorth.dish.core.net.DishProtocol

// Phone's perspective: input rides out (SEND), feedback rides in (RECEIVE).
enum class Direction { SEND, RECEIVE }

enum class Feature(
    val direction: Direction,
    val catalogSlug: String?,
) {
    GAMEPAD(Direction.SEND, null),
    ANALOG_TRIGGERS(Direction.SEND, "analogTriggers"),
    MOTION(Direction.SEND, "motion"),
    TOUCHPAD(Direction.SEND, "touchpad"),

    // MOUSE and KEYBOARD are host-injected, not emulated-pad features, so they carry
    // no per-type catalog slug: the host layer (mouseControl / keyboardControl) is their
    // only gate.
    MOUSE(Direction.SEND, null),

    // Host-gated via hostFeatures.keyboardControl; modeled but never user-offered
    // (no phone-side source).
    KEYBOARD(Direction.SEND, null),

    // Battery reporting has no satellite catalog slug (it is always accepted there);
    // over Moonlight it maps to CAP_BATTERY in the arrival advertisement.
    BATTERY(Direction.SEND, null),
    RUMBLE(Direction.RECEIVE, "rumble"),

    // Xbox One impulse-trigger motors. Moonlight-only on the wire (RUMBLE_TRIGGERS);
    // the satellite protocol has no source for it, so its transport crosses it out.
    TRIGGER_RUMBLE(Direction.RECEIVE, null),

    // Actuated on a Direct-claimed DS4/DualSense; the framework exposes no controller
    // LED, so framework-path pads and the phone never produce it.
    LIGHTBAR(Direction.RECEIVE, "lightbar"),

    // DualSense adaptive-trigger effect blocks, replayed verbatim into a Direct-claimed
    // physical DualSense (satellite MSG_TRIGGER_EFFECTS only).
    TRIGGER_EFFECTS(Direction.RECEIVE, "triggerEffects"),

    // Player-indicator LEDs (DualSense bar, Switch Pro lights), Direct path only.
    PLAYER_LEDS(Direction.RECEIVE, "playerLeds"),

    // The emulated pad's OWN microphone endpoint, sourced by the phone mic (virtual pad)
    // or by the pad's own headset mic. It also gates the mic-mute lamp coming back
    // (MSG_MIC_LED): a lamp with no microphone behind it has nothing to report.
    MIC(Direction.SEND, "mic"),

    // The emulated pad's OWN speaker/headset endpoint, played out the phone or the pad.
    // Independent of MIC: neither direction implies the other.
    SPEAKER(Direction.RECEIVE, "speaker"),
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

data class SlotCapabilities(
    val controller: CapabilitySet,
    val transport: CapabilitySet,
    val type: CapabilitySet,
    val host: CapabilitySet,
    val userEnabled: CapabilitySet,
    val runtimeDown: CapabilitySet,
) {
    // Inherent: what every layer in the path can carry, before the user's toggles.
    val available: CapabilitySet get() = controller intersect transport intersect type intersect host
    val enabled: CapabilitySet get() = available intersect userEnabled
    val live: CapabilitySet get() = enabled - runtimeDown

    fun isAvailable(feature: Feature): Boolean = feature in available

    fun isEnabled(feature: Feature): Boolean = feature in enabled

    // The motion indicator needs the raw user toggle, independent of availability.
    fun userWants(feature: Feature): Boolean = feature in userEnabled

    // Column helpers: the report table breaks "available" into its limiting layers per feature.
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
    // Whether this host will actually materialize a pad carrying audio endpoints right
    // now. Read off the capabilities probe's per-backend `audio`, which is the host's
    // `controllerAudio` setting folded into the backend's own ability; opt-IN, so a
    // satellite predating controller audio (or one that switched it off) offers none.
    val controllerAudio: Boolean = false,
    // The protocol version the satellite advertised (catalog + capabilities documents);
    // 0 = never fetched. This is the verified truth behind the update chips and the
    // extended-mouse gate: only a version that decodes the v2 pointer frame reports 2+.
    val protocolVersion: Int = 0,
) {
    val extendedMouse: Boolean get() = mouseControl && protocolVersion >= DishProtocol.EXTENDED_MOUSE

    val compat: DishProtocol.Compat get() = DishProtocol.compatFor(protocolVersion.takeIf { it > 0 })

    fun toCapabilitySet(): CapabilitySet {
        // The per-type surfaces (lightbar/triggerEffects/playerLeds) are the type
        // layer's job; the host layer passes them so a capable type is not crossed
        // out by a host that gates them per backend.
        val out =
            mutableSetOf(
                Feature.GAMEPAD,
                Feature.ANALOG_TRIGGERS,
                Feature.MOTION,
                Feature.TOUCHPAD,
                Feature.BATTERY,
                Feature.LIGHTBAR,
                Feature.TRIGGER_EFFECTS,
                Feature.PLAYER_LEDS,
            )
        if (mouseControl) out += Feature.MOUSE
        if (keyboardControl) out += Feature.KEYBOARD
        if (rumbleReturn) out += Feature.RUMBLE
        // Audio is the exception to the pass-through above, because it is the one
        // host-wide RUNTIME switch: the catalog's per-type mic/speaker slugs say what the
        // backend could materialize, this says whether the host will. A client reading
        // only the type columns would offer a microphone on a host that has audio off.
        if (controllerAudio) {
            out += Feature.MIC
            out += Feature.SPEAKER
        }
        return CapabilitySet(out)
    }

    companion object {
        // Optimistic baseline for a satellite we have not fetched a catalog from: a
        // satellite has always accepted a mouse-control request and returned rumble,
        // so both are assumed until a fetched catalog refines them. Controller audio
        // gets the opposite treatment: no satellite carried it before the setting
        // existed, and offering a microphone that cannot land would cost the user a
        // permission prompt for nothing, so it waits for a capabilities probe.
        val SATELLITE_DEFAULT =
            HostFeatureSet(
                hasCatalog = false,
                mouseControl = true,
                keyboardControl = false,
                rumbleReturn = true,
                controllerAudio = false,
            )

        fun fromCatalog(catalog: CatalogDto): HostFeatureSet =
            HostFeatureSet(
                hasCatalog = true,
                mouseControl = catalog.hostFeatures["mouseControl"]?.supported == true,
                // Keyboard is opt-IN: offered only when the host advertises it. A catalog
                // without the slug (older satellite) leaves it unsupported, so keyboard
                // stays unoffered exactly as before.
                keyboardControl = catalog.hostFeatures["keyboardControl"]?.supported == true,
                // Rumble is opt-OUT for back-compat: a satellite predating the slug still
                // returns rumble, so an ABSENT field keeps the optimistic assumption;
                // a PRESENT field is honored (a host that can't return rumble hides it).
                rumbleReturn = catalog.hostFeatures["rumble"]?.supported ?: true,
                // controllerAudio is deliberately unset here: the catalog carries no
                // `audio` field at all (it is cached on server version + locale, so an
                // install-time switch must not move it). SatelliteHostFeaturesStore
                // carries the probed value across a catalog write instead.
                protocolVersion = catalog.protocolVersion,
            )

        // Pre-bind, pre-catalog host read (GET /api/server/capabilities). Caller must
        // gate on host.catalog.supported first: an older satellite omits the block, and
        // mapping its all-false default would wrongly report everything unsupported.
        fun fromServerCapabilities(caps: ServerCapabilitiesDto): HostFeatureSet =
            HostFeatureSet(
                hasCatalog = caps.host.catalog.supported,
                mouseControl = caps.host.mouseControl.supported,
                keyboardControl = caps.host.keyboardControl.supported,
                rumbleReturn = caps.host.rumble.supported,
                // `audio` rides the per-backend entries, not the host block: it is true
                // only where the backend has an audio-carrying type AND the host's
                // controllerAudio setting is on. Availability counts too, since an
                // unavailable backend materializes nothing. Older satellites send no
                // `backends` array at all, which reads as off, and is the truth there.
                controllerAudio = caps.backends.any { it.available && it.audio },
                protocolVersion = caps.protocolVersion,
            )
    }
}
