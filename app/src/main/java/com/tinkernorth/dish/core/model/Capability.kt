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
    // no per-type catalog slug: the host layer (mouseControl) is their only gate.
    MOUSE(Direction.SEND, null),

    // TODO(capability-contract): keyboard emulation is not in the satellite contract yet.
    KEYBOARD(Direction.SEND, null),
    RUMBLE(Direction.RECEIVE, "rumble"),

    // TODO(capability-contract): no Android LED sink exists; lightbar is host-driven only for now.
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
                // TODO(capability-contract): no keyboardControl host-feature slug yet; assume unsupported.
                keyboardControl = false,
                // TODO(capability-contract): no rumble host-feature slug yet; the baseline advertises rumble.
                rumbleReturn = true,
                touchpadModes = mouse?.modes?.toSet() ?: emptySet(),
            )
        }
    }
}
