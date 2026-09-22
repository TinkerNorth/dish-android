// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.jni

/**
 * The native model tables: what a known vendor/product pair can do, keyed the way
 * usb_parsers.cpp classifies it.
 */
object ModelTableNative {
    init {
        System.loadLibrary("satellite")
    }

    external fun isKnownFastLaneModel(
        vendorId: Int,
        productId: Int,
    ): Boolean

    external fun modelHasImu(
        vendorId: Int,
        productId: Int,
    ): Boolean

    external fun modelHasRumble(
        vendorId: Int,
        productId: Int,
    ): Boolean

    external fun modelHasLightbar(
        vendorId: Int,
        productId: Int,
    ): Boolean

    external fun modelHasPlayerLeds(
        vendorId: Int,
        productId: Int,
    ): Boolean

    external fun modelHasTriggerEffects(
        vendorId: Int,
        productId: Int,
    ): Boolean

    external fun modelHasHapticLanes(
        vendorId: Int,
        productId: Int,
    ): Boolean

    external fun modelHasTriggerRumble(
        vendorId: Int,
        productId: Int,
    ): Boolean

    external fun modelFrameworkRumbleUnreliable(
        vendorId: Int,
        productId: Int,
    ): Boolean

    // Parser-level: true for the DS4/DualSense report families whose touch bytes the
    // USB-direct path parses and streams.
    external fun modelHasTouchpad(
        vendorId: Int,
        productId: Int,
    ): Boolean

    // False for models whose Standard identity is a keyboard/mouse (the Steam Controller): no
    // framework gamepad re-enumerates after a release, so nothing should wait for one.
    external fun modelExpectsFrameworkGamepad(
        vendorId: Int,
        productId: Int,
    ): Boolean

    external fun lookupKnownModelName(
        vendorId: Int,
        productId: Int,
    ): String
}
