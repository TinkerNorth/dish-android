// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.composer.TouchpadSource

// Pure: when the app should hold pointer capture, and which captured surface feeds which slot.
// Pointer capture is Android's one door to a touchpad's own finger positions; without it the
// framework turns a DualShock 4 or DualSense's surface into a system mouse and hands the app
// nothing. Holding it costs the user the cursor while the app is focused, so it is held only
// while a framework pad is both routed (bound to a live link) and sourcing its own touch: a pad
// lying around unbound keeps moving the cursor. Capture needs the window focused, the same
// condition under which framework gamepad input reaches the app at all, so an unfocused app
// forwards no pad input of any kind.

/**
 * Captured-surface device id -> slot id, for every framework pad whose own touch surface the
 * app reads. [reachable] is the slot set with a live telemetry sink; [touchpadSource] is the
 * composer's answer for a slot, which already folds the model's trackpad and the path.
 */
fun routes(
    devices: Map<Int, PhysicalGamepadRegistry.Device>,
    reachable: Set<String>,
    touchpadSource: (String) -> TouchpadSource,
): Map<Int, String> {
    val out = mutableMapOf<Int, String>()
    for ((id, device) in devices) {
        val surface = capturedSurface(device, id.toString(), reachable, touchpadSource) ?: continue
        out[surface] = id.toString()
    }
    return out
}

private fun capturedSurface(
    device: PhysicalGamepadRegistry.Device,
    slotId: String,
    reachable: Set<String>,
    touchpadSource: (String) -> TouchpadSource,
): Int? {
    if (device.isUsbSynthetic) return null
    val surface = device.touchpadDeviceId ?: return null
    val routed = slotId in reachable && touchpadSource(slotId) == TouchpadSource.PAD
    return if (routed) surface else null
}

fun shouldCapture(
    routes: Map<Int, String>,
    focused: Boolean,
): Boolean = focused && routes.isNotEmpty()

/**
 * The slot a motion event feeds, or null when it is not a captured touchpad event for a
 * routed pad. Only the touchpad source counts: a captured surface reports SOURCE_TOUCHPAD,
 * while the same device's joystick axes and its uncaptured mouse-mode cursor moves carry
 * other sources and must keep going where they went.
 */
fun slotForEvent(
    routes: Map<Int, String>,
    source: Int,
    deviceId: Int,
): String? {
    if ((source and SOURCE_TOUCHPAD) != SOURCE_TOUCHPAD) return null
    return routes[deviceId]
}

// android.view.InputDevice.SOURCE_TOUCHPAD, restated so the policy stays JVM-testable.
const val SOURCE_TOUCHPAD = 0x00100008
