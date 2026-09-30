// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val MOUSE_REL_DATA_SIZE = 8
private const val MOUSE_BUTTON_DATA_SIZE = 5
private const val MOUSE_SCROLL_DATA_SIZE = 10

/**
 * MOUSE_MOVE_REL: deltas are BIG-endian (input-data.adoc note). Included
 * because the repo already streams a virtual mouse (mouseControl); cheap to
 * carry so the Moonlight path reaches parity there.
 *
 * The write* forms encode into a caller-owned buffer of at least the message's
 * length, from position 0, and leave it flipped to the message, so the send path
 * seals from one reused scratch; the allocating forms wrap them.
 */
internal fun writeMouseMoveRel(
    dst: ByteBuffer,
    deltaX: Int,
    deltaY: Int,
) {
    beginInput(dst, MOUSE_MOVE_REL_LEN, MOUSE_REL_DATA_SIZE, INPUT_MOUSE_MOVE_REL)
    putShortBE(dst, deltaX)
    putShortBE(dst, deltaY)
    dst.flip()
}

fun mouseMoveRel(
    deltaX: Int,
    deltaY: Int,
): ByteArray {
    val buf = ByteBuffer.allocate(MOUSE_MOVE_REL_LEN)
    writeMouseMoveRel(buf, deltaX, deltaY)
    return buf.toByteArray()
}

/** MOUSE_BUTTON_DOWN/UP: one u8 button id after the wrapper (Wolf control.hpp). */
internal fun writeMouseButton(
    dst: ByteBuffer,
    down: Boolean,
    button: Int,
) {
    val inputType =
        if (down) {
            INPUT_MOUSE_BUTTON_DOWN
        } else {
            INPUT_MOUSE_BUTTON_UP
        }
    beginInput(dst, MOUSE_BUTTON_LEN, MOUSE_BUTTON_DATA_SIZE, inputType)
    dst.put(button.toByte())
    dst.flip()
}

fun mouseButton(
    down: Boolean,
    button: Int,
): ByteArray {
    val buf = ByteBuffer.allocate(MOUSE_BUTTON_LEN)
    writeMouseButton(buf, down, button)
    return buf.toByteArray()
}

/**
 * MOUSE_SCROLL: big-endian scroll_amt1 duplicated as scroll_amt2 plus a zero
 * i16 (Wolf control.hpp MOUSE_SCROLL_PACKET). 120 per wheel notch, sign = up.
 */
internal fun writeMouseScroll(
    dst: ByteBuffer,
    amount: Int,
) {
    beginInput(dst, MOUSE_SCROLL_LEN, MOUSE_SCROLL_DATA_SIZE, INPUT_MOUSE_SCROLL)
    putShortBE(dst, amount)
    putShortBE(dst, amount)
    putShortBE(dst, 0)
    dst.flip()
}

fun mouseScroll(amount: Int): ByteArray {
    val buf = ByteBuffer.allocate(MOUSE_SCROLL_LEN)
    writeMouseScroll(buf, amount)
    return buf.toByteArray()
}

// The control header and the INPUT wrapper every mouse message starts with: little-endian but
// for the wrapper's big-endian size.
private fun beginInput(
    dst: ByteBuffer,
    messageLen: Int,
    dataSize: Int,
    inputType: Int,
) {
    dst.clear()
    dst.order(ByteOrder.LITTLE_ENDIAN)
    dst.putShort(CTRL_INPUT_DATA.toShort())
    dst.putShort((messageLen - CONTROL_HEADER_LEN).toShort())
    putIntBE(dst, dataSize)
    dst.putInt(inputType)
}
