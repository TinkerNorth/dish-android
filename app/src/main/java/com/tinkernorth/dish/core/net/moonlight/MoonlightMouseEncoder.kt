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
 */
fun mouseMoveRel(
    deltaX: Int,
    deltaY: Int,
): ByteArray {
    val buf = ByteBuffer.allocate(MOUSE_MOVE_REL_LEN).order(ByteOrder.LITTLE_ENDIAN)
    buf.putShort(CTRL_INPUT_DATA.toShort())
    buf.putShort((MOUSE_MOVE_REL_LEN - CONTROL_HEADER_LEN).toShort())
    putIntBE(buf, MOUSE_REL_DATA_SIZE)
    buf.putInt(INPUT_MOUSE_MOVE_REL)
    putShortBE(buf, deltaX)
    putShortBE(buf, deltaY)
    return buf.toByteArray()
}

/** MOUSE_BUTTON_DOWN/UP: one u8 button id after the wrapper (Wolf control.hpp). */
fun mouseButton(
    down: Boolean,
    button: Int,
): ByteArray {
    val buf = ByteBuffer.allocate(MOUSE_BUTTON_LEN).order(ByteOrder.LITTLE_ENDIAN)
    buf.putShort(CTRL_INPUT_DATA.toShort())
    buf.putShort((MOUSE_BUTTON_LEN - CONTROL_HEADER_LEN).toShort())
    putIntBE(buf, MOUSE_BUTTON_DATA_SIZE)
    buf.putInt(
        if (down) {
            INPUT_MOUSE_BUTTON_DOWN
        } else {
            INPUT_MOUSE_BUTTON_UP
        },
    )
    buf.put(button.toByte())
    return buf.toByteArray()
}

/**
 * MOUSE_SCROLL: big-endian scroll_amt1 duplicated as scroll_amt2 plus a zero
 * i16 (Wolf control.hpp MOUSE_SCROLL_PACKET). 120 per wheel notch, sign = up.
 */
fun mouseScroll(amount: Int): ByteArray {
    val buf = ByteBuffer.allocate(MOUSE_SCROLL_LEN).order(ByteOrder.LITTLE_ENDIAN)
    buf.putShort(CTRL_INPUT_DATA.toShort())
    buf.putShort((MOUSE_SCROLL_LEN - CONTROL_HEADER_LEN).toShort())
    putIntBE(buf, MOUSE_SCROLL_DATA_SIZE)
    buf.putInt(INPUT_MOUSE_SCROLL)
    putShortBE(buf, amount)
    putShortBE(buf, amount)
    putShortBE(buf, 0)
    return buf.toByteArray()
}
