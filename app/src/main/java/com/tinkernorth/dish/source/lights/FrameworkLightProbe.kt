// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.lights

import android.hardware.lights.Light
import android.os.Build
import android.view.InputDevice
import androidx.annotation.RequiresApi

// The names the framework gives the light bar it composes from a driver's LEDs. hid-sony builds one
// RGB light out of a DualShock 4's red/green/blue nodes and hardcodes the name "RGB"; hid-playstation
// exposes the DualSense (and, from kernel 6.2, DualShock 4) light bar as one multicolor LED whose
// node is `rgb:indicator`, which the framework trims to "indicator".
internal const val COMPOSED_RGB_LIGHT_NAME = "RGB"
internal const val MULTICOLOR_INDICATOR_LIGHT_NAME = "indicator"

/**
 * Which of a pad's lights is its light bar: an INPUT-class RGB light, or none.
 *
 * hasRgbControl is only trustworthy on Android 14+, where LIGHT_CAPABILITY_COLOR_RGB is a real bit;
 * on 31..33 the RGB capability flag is 0, so hasRgbControl answers true for every light including
 * mono ones, and it cannot be used to pick the bar. There we fall back to the driver's own naming:
 * the composed "RGB" light or the "indicator" multicolor light, else the only INPUT light if a pad
 * exposes exactly one. A DualSense's five player LEDs come through as extra mono INPUT lights (the
 * framework's player-id grouping rejects their hyphenated `player-N` names), which is why the sole
 * fallback is not enough on its own and the name match runs first.
 */
fun lightbarLight(
    lights: List<FrameworkLight>,
    sdkInt: Int,
): FrameworkLight? {
    if (sdkInt < Build.VERSION_CODES.S) return null
    val inputs = lights.filter { it.input }
    if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return inputs.firstOrNull { it.rgb }
    }
    inputs
        .firstOrNull { it.name == COMPOSED_RGB_LIGHT_NAME || it.name == MULTICOLOR_INDICATOR_LIGHT_NAME }
        ?.let { return it }
    return inputs.singleOrNull()
}

/**
 * Resolves a framework [InputDevice] to its light-bar [Light], if it exposes one. The Android side
 * of [lightbarLight]: it reads the device's lights over binder and applies the same rule, so the
 * registry's capability probe and the gateway's session both answer "does this pad have a light bar
 * the app can drive" identically.
 *
 * Only `InputDevice.getLightsManager()` is used (API 31, no permission); the system LightsManager
 * service is a different, permission-gated flavor and is never touched.
 */
fun hasLightbar(device: InputDevice): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    return lightbarOf(device) != null
}

fun lightbarOf(device: InputDevice): Light? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    return resolve(device, Build.VERSION.SDK_INT)
}

@RequiresApi(Build.VERSION_CODES.S)
private fun resolve(
    device: InputDevice,
    sdkInt: Int,
): Light? {
    // A binder call into the input service; a device that has gone away lists nothing.
    val lights = runCatching { device.lightsManager.lights }.getOrNull().orEmpty()
    val picked = lightbarLight(lights.map { it.facts() }, sdkInt) ?: return null
    return lights.firstOrNull { it.id == picked.id }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun Light.facts(): FrameworkLight =
    FrameworkLight(
        id = id,
        name = name.orEmpty(),
        input = type == Light.LIGHT_TYPE_INPUT,
        rgb = hasRgbControl(),
    )
