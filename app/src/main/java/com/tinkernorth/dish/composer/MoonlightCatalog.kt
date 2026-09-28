// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.capabilitySetOf
import com.tinkernorth.dish.core.net.moonlight.CAP_ACCELEROMETER
import com.tinkernorth.dish.core.net.moonlight.CAP_ANALOG_TRIGGERS
import com.tinkernorth.dish.core.net.moonlight.CAP_BATTERY
import com.tinkernorth.dish.core.net.moonlight.CAP_GYRO
import com.tinkernorth.dish.core.net.moonlight.CAP_RGB_LED
import com.tinkernorth.dish.core.net.moonlight.CAP_RUMBLE
import com.tinkernorth.dish.core.net.moonlight.CAP_TOUCHPAD
import com.tinkernorth.dish.core.net.moonlight.CAP_TRIGGER_RUMBLE
import com.tinkernorth.dish.core.net.moonlight.PLAYSTATION
import com.tinkernorth.dish.core.net.moonlight.capabilityBits

// The Moonlight side of the capability layering, sibling of [BundledCatalog]. Hard-coded
// because there is nothing to fetch: the capability byte travels client to host inside
// CONTROLLER_ARRIVAL and no host endpoint reports back, so this is a declaration.
// A Moonlight host never says what it cannot do, so its host layer crosses nothing out.
// The type ceiling and what the local input can actually feed are what narrow the set.
// Mouse is native to the control stream (no advertisement), so it always passes.
internal val HOST_LAYER =
    capabilitySetOf(
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

// PlayStation is the only type the host emulator gives a gyro, a touchpad and an LED to,
// which is why Auto reaches for it whenever the source has motion. Nintendo is not the
// satellite's switchpro: over Moonlight it carries no motion, so it sits on the Xbox base.
// Trigger rumble and battery describe the physical pad, so every type passes them.
internal fun moonlightTypeCapabilities(type: Int): CapabilitySet =
    when (type) {
        PLAYSTATION ->
            padType(Feature.RUMBLE, Feature.MOTION, Feature.TOUCHPAD, Feature.LIGHTBAR)
        else -> padType(Feature.RUMBLE)
    }

// The wire bits each feature is declared by in CONTROLLER_ARRIVAL. One motion switch means the
// accelerometer and the gyro; trigger rumble is its own bit, claimed only when the pad has the
// motors (a claimed-but-dropped cap would make a host waste RUMBLE_TRIGGERS events on a pad that
// eats them). A feature with no entry has no bit, and the arrival says nothing about it.
private val WIRE_BITS: Map<Feature, Int> =
    mapOf(
        Feature.ANALOG_TRIGGERS to CAP_ANALOG_TRIGGERS,
        Feature.RUMBLE to CAP_RUMBLE,
        Feature.TRIGGER_RUMBLE to CAP_TRIGGER_RUMBLE,
        Feature.TOUCHPAD to CAP_TOUCHPAD,
        Feature.MOTION to (CAP_ACCELEROMETER or CAP_GYRO),
        Feature.BATTERY to CAP_BATTERY,
        Feature.LIGHTBAR to CAP_RGB_LED,
    )

// What the local input can actually feed or actuate, in the wire's own bits.
internal fun sourceBits(caps: CapabilitySet): Int =
    WIRE_BITS
        .filterKeys { it in caps }
        .values
        .fold(0, Int::or)

// The features a pad announced with [bits] was declared with: every feature with a bit set, and
// every feature the arrival carries no bit for. Either motion bit declares motion, since the host
// asks for motion events on either.
internal fun announcedFeatures(bits: Int): CapabilitySet =
    CapabilitySet(Feature.entries.filterTo(mutableSetOf()) { feature -> isDeclaredBy(bits, feature) })

private fun isDeclaredBy(
    bits: Int,
    feature: Feature,
): Boolean {
    val featureBits = WIRE_BITS[feature] ?: return true
    return bits and featureBits != 0
}

internal fun capabilityBits(
    type: Int,
    caps: CapabilitySet,
): Int = capabilityBits(type, sourceBits(caps))

// Every emulated pad carries the gamepad axes, analog triggers, trigger rumble and
// battery (the latter two are physical-pad surfaces the type never gates). Mouse is
// not a pad property: it rides the control stream beside the pad, so the type layer
// passes it through. Keyboard stays absent until the client has code for it.
private fun padType(vararg padFeatures: Feature): CapabilitySet =
    CapabilitySet(
        setOf(
            Feature.GAMEPAD,
            Feature.ANALOG_TRIGGERS,
            Feature.MOUSE,
            Feature.TRIGGER_RUMBLE,
            Feature.BATTERY,
        ) + padFeatures,
    )
