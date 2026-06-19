// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.model

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

    // Host-gated via hostFeatures.keyboardControl (no longer hardwired false). Stays
    // unoffered until both a host injection backend and a phone-side source exist.
    KEYBOARD(Direction.SEND, null),
    RUMBLE(Direction.RECEIVE, "rumble"),

    // Modeled for a complete type/host view, but Android exposes no LED sink, so the
    // controller layer never produces it and it is never offered locally.
    LIGHTBAR(Direction.RECEIVE, "lightbar"),
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
    val touchpadModes: Set<String>,
) {
    fun toCapabilitySet(): CapabilitySet {
        val out =
            mutableSetOf(
                Feature.GAMEPAD,
                Feature.ANALOG_TRIGGERS,
                Feature.MOTION,
                Feature.TOUCHPAD,
                Feature.LIGHTBAR,
            )
        if (mouseControl) out += Feature.MOUSE
        if (keyboardControl) out += Feature.KEYBOARD
        if (rumbleReturn) out += Feature.RUMBLE
        return CapabilitySet(out)
    }

    companion object {
        // Optimistic baseline for a satellite we have not fetched a catalog from: a
        // satellite has always accepted a mouse-control request and returned rumble,
        // so both are assumed until a fetched catalog refines them.
        val SATELLITE_DEFAULT =
            HostFeatureSet(
                hasCatalog = false,
                mouseControl = true,
                keyboardControl = false,
                rumbleReturn = true,
                touchpadModes = emptySet(),
            )

        fun fromCatalog(catalog: CatalogDto): HostFeatureSet {
            val mouse = catalog.hostFeatures["mouseControl"]
            return HostFeatureSet(
                hasCatalog = true,
                mouseControl = mouse?.supported == true,
                // Keyboard is opt-IN: offered only when the host advertises it. A catalog
                // without the slug (older satellite) leaves it unsupported, so keyboard
                // stays unoffered exactly as before.
                keyboardControl = catalog.hostFeatures["keyboardControl"]?.supported == true,
                // Rumble is opt-OUT for back-compat: a satellite predating the slug still
                // returns rumble, so an ABSENT field keeps the optimistic assumption;
                // a PRESENT field is honored (a host that can't return rumble hides it).
                rumbleReturn = catalog.hostFeatures["rumble"]?.supported ?: true,
                touchpadModes = mouse?.modes?.toSet() ?: emptySet(),
            )
        }

        // Pre-bind, pre-catalog host read (GET /api/server/capabilities). Caller must
        // gate on host.catalog.supported first: an older satellite omits the block, and
        // mapping its all-false default would wrongly report everything unsupported.
        fun fromServerCapabilities(caps: ServerCapabilitiesDto): HostFeatureSet =
            HostFeatureSet(
                hasCatalog = caps.host.catalog.supported,
                mouseControl = caps.host.mouseControl.supported,
                keyboardControl = caps.host.keyboardControl.supported,
                rumbleReturn = caps.host.rumble.supported,
                touchpadModes = emptySet(), // modes are a per-type catalog concern
            )
    }
}
