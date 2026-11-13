// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature

// Offline fallback for the slugs the app ships art for; unknown slugs fall through to the
// server catalog (typeCapabilities), so this never masks a richer remote type.
const val SLUG_XBOX360 = "xbox360"
const val SLUG_DS4 = "ds4"
const val SLUG_DUALSENSE = "dualsense"
const val SLUG_SWITCHPRO = "switchpro"
const val SLUG_DUALSENSE_EDGE = "dualsenseedge"

// Audio rides the two Sony types only: they are the pads that carry real speaker and
// microphone endpoints, so they are the only identities a host can materialize with
// any. Offering them here cannot outrun the host, which gates audio on its own
// runtime `audio` switch, and a satellite old enough to serve no catalog reports no
// switch at all.
fun bundledTypeCapabilities(slug: String): CapabilitySet? =
    when (slug) {
        SLUG_XBOX360 -> padType(setOf(Feature.RUMBLE))
        SLUG_DS4 ->
            padType(
                setOf(
                    Feature.RUMBLE,
                    Feature.MOTION,
                    Feature.TOUCHPAD,
                    Feature.LIGHTBAR,
                    Feature.MIC,
                    Feature.SPEAKER,
                ),
            )
        SLUG_DUALSENSE -> padType(DUALSENSE_FEATURES)
        // Protocol 4: the Edge is the DualSense with the four paddles rendered as its back and
        // Fn buttons. Catalogs list it last, so it is never the type a client defaults to.
        SLUG_DUALSENSE_EDGE -> padType(DUALSENSE_FEATURES + Feature.EXTENDED_BUTTONS)
        SLUG_SWITCHPRO -> padType(setOf(Feature.RUMBLE, Feature.MOTION))
        else -> null
    }

// The haptic lanes ride the DualSense's endpoint alone; the DualShock 4 v2 audio function is
// headset-only.
private val DUALSENSE_FEATURES =
    setOf(
        Feature.RUMBLE,
        Feature.MOTION,
        Feature.TOUCHPAD,
        Feature.LIGHTBAR,
        Feature.MIC,
        Feature.SPEAKER,
        Feature.HAPTIC_AUDIO,
    )

// Every emulated pad carries the gamepad axes and analog triggers; MOUSE/KEYBOARD
// are host-injected and BATTERY/TRIGGER_RUMBLE have no catalog slug, so the type
// layer passes all four through for the host/transport layers to gate. The
// triggerEffects/playerLeds surfaces are deliberately absent: a satellite old
// enough to serve no catalog predates the messages that carry them.
private fun padType(padFeatures: Set<Feature>): CapabilitySet =
    CapabilitySet(
        setOf(
            Feature.GAMEPAD,
            Feature.ANALOG_TRIGGERS,
            Feature.MOUSE,
            Feature.KEYBOARD,
            Feature.BATTERY,
            Feature.TRIGGER_RUMBLE,
        ) + padFeatures,
    )

fun typeCapabilitiesById(typeId: Int): CapabilitySet =
    when (typeId) {
        CONTROLLER_TYPE_PLAYSTATION -> bundledTypeCapabilities(SLUG_DS4)!!
        CONTROLLER_TYPE_DUALSENSE -> bundledTypeCapabilities(SLUG_DUALSENSE)!!
        CONTROLLER_TYPE_SWITCHPRO -> bundledTypeCapabilities(SLUG_SWITCHPRO)!!
        CONTROLLER_TYPE_DUALSENSE_EDGE -> bundledTypeCapabilities(SLUG_DUALSENSE_EDGE)!!
        else -> bundledTypeCapabilities(SLUG_XBOX360)!!
    }
