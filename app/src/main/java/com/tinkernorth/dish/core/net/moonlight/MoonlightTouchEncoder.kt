// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val CONTROLLER_TOUCH_DATA_SIZE = 24

/**
 * CONTROLLER_TOUCH: one pointer event on the emulated pad's touch surface.
 * [x]/[y] are normalized 0..1 across the pad (the host multiplies by its
 * emulated touchpad's resolution); netfloats are little-endian IEEE-754
 * (Wolf utils::from_netfloat). [pressure] is 1.0 for a solid contact.
 *
 * The write form encodes into a caller-owned buffer of at least
 * CONTROLLER_TOUCH_LEN, from position 0, and leaves it flipped to the message,
 * so the send path seals from the hot sealer's reused scratch; the allocating
 * form wraps it.
 */
internal fun writeControllerTouch(
    dst: ByteBuffer,
    controllerNumber: Int,
    eventType: Int,
    pointerId: Int,
    x: Float,
    y: Float,
    pressure: Float,
) {
    dst.clear()
    dst.order(ByteOrder.LITTLE_ENDIAN)
    dst.putShort(CTRL_INPUT_DATA.toShort())
    dst.putShort((CONTROLLER_TOUCH_LEN - CONTROL_HEADER_LEN).toShort())
    putIntBE(dst, CONTROLLER_TOUCH_DATA_SIZE)
    dst.putInt(INPUT_CONTROLLER_TOUCH)
    dst.put((controllerNumber and 0xFF).toByte())
    dst.put((eventType and 0xFF).toByte())
    dst.putShort(0) // reserved/alignment
    dst.putInt(pointerId)
    dst.putFloat(x)
    dst.putFloat(y)
    dst.putFloat(pressure)
    dst.flip()
}

fun controllerTouch(
    controllerNumber: Int,
    eventType: Int,
    pointerId: Int,
    x: Float,
    y: Float,
    pressure: Float,
): ByteArray {
    val buf = ByteBuffer.allocate(CONTROLLER_TOUCH_LEN)
    writeControllerTouch(buf, controllerNumber, eventType, pointerId, x, y, pressure)
    return buf.toByteArray()
}
