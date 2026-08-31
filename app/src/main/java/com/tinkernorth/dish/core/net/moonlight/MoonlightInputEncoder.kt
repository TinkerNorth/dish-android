// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the DECRYPTED control-stream plaintext for the input messages the dish
 * sends to a Moonlight host. Layout comes from Wolf input-data.adoc (byte-exact
 * network fixtures) and control.hpp struct definitions.
 *
 * The plaintext always begins with the control header `[ptype u16 LE][plen u16
 * LE]`, then for INPUT_DATA the wrapper `[input size u32 BE][input type u32
 * LE]`, then the message body. This class only produces plaintext; sealing
 * (AES-GCM) and ENet framing happen in the transport.
 *
 * HOT PATH: [encodeControllerMulti] writes into a caller-owned, reused
 * [ByteBuffer] at fixed offsets with no allocation and no intermediate objects,
 * mirroring the repo's satellite_jni.cpp fixed-buffer discipline. Everything is
 * little-endian except the two big-endian fields the protocol mandates (INPUT
 * size and the mouse deltas).
 */
object MoonlightInputEncoder {
    // Full plaintext length of a CONTROLLER_MULTI message: 4 control header + 8
    // wrapper + 26 struct body.
    const val CONTROLLER_MULTI_LEN = 38

    // 4 control header + 8 wrapper + 8 arrival body. See [controllerArrival] for
    // why the body is 8 bytes and not the 7 its fields add up to.
    const val CONTROLLER_ARRIVAL_LEN = 20
    const val MOUSE_MOVE_REL_LEN = 16
    const val MOUSE_BUTTON_LEN = 13
    const val MOUSE_SCROLL_LEN = 18
    const val PERIODIC_PING_LEN = 8
    const val TERMINATION_LEN = 8

    // data_size (the INPUT wrapper's big-endian size) counts from the input-type
    // field to the end of the message.
    private const val MULTI_DATA_SIZE = 30
    private const val ARRIVAL_DATA_SIZE = 12
    private const val MOUSE_REL_DATA_SIZE = 8
    private const val MOUSE_BUTTON_DATA_SIZE = 5
    private const val MOUSE_SCROLL_DATA_SIZE = 10

    /**
     * Encode a CONTROLLER_MULTI packet into [dst] starting at position 0 and
     * leave the buffer positioned/limited to the 38-byte message. [dst] must be
     * little-endian and hold at least [CONTROLLER_MULTI_LEN] bytes; it is reused
     * across every input change with zero allocation.
     *
     * [buttons] is the full 32-bit button field; it is split into the low
     * `button_flags` and high `buttonFlags2` halves per input-data.adoc
     * (effective = flags | (flags2 << 16)). [activeMask] carries the present-
     * controller bitfield (dropping a bit signals an unplug).
     */
    @Suppress("LongParameterList")
    fun encodeControllerMulti(
        dst: ByteBuffer,
        controllerNumber: Int,
        activeMask: Int,
        buttons: Int,
        leftTrigger: Int,
        rightTrigger: Int,
        leftStickX: Int,
        leftStickY: Int,
        rightStickX: Int,
        rightStickY: Int,
    ) {
        dst.clear()
        dst.order(ByteOrder.LITTLE_ENDIAN)
        // Control header.
        dst.putShort(MoonlightControlProtocol.CTRL_INPUT_DATA.toShort())
        dst.putShort((CONTROLLER_MULTI_LEN - CONTROL_HEADER_LEN).toShort())
        // INPUT wrapper: size is BIG-endian, type is LITTLE-endian.
        putIntBE(dst, MULTI_DATA_SIZE)
        dst.putInt(MoonlightControlProtocol.INPUT_CONTROLLER_MULTI)
        // Struct body (all little-endian).
        dst.putShort(MoonlightControlProtocol.MULTI_HEADER_B.toShort())
        dst.putShort(controllerNumber.toShort())
        dst.putShort(activeMask.toShort())
        dst.putShort(MoonlightControlProtocol.MULTI_MID_B.toShort())
        dst.putShort((buttons and 0xFFFF).toShort())
        dst.put((leftTrigger and 0xFF).toByte())
        dst.put((rightTrigger and 0xFF).toByte())
        dst.putShort(leftStickX.toShort())
        dst.putShort(leftStickY.toShort())
        dst.putShort(rightStickX.toShort())
        dst.putShort(rightStickY.toShort())
        dst.putShort(MoonlightControlProtocol.MULTI_TAIL_A.toShort())
        dst.putShort((buttons ushr 16).toShort())
        dst.putShort(MoonlightControlProtocol.MULTI_TAIL_B.toShort())
        dst.flip()
    }

    /** Convenience allocating form for tests and the arrival/teardown paths. */
    @Suppress("LongParameterList")
    fun controllerMulti(
        controllerNumber: Int,
        activeMask: Int,
        buttons: Int,
        leftTrigger: Int,
        rightTrigger: Int,
        leftStickX: Int,
        leftStickY: Int,
        rightStickX: Int,
        rightStickY: Int,
    ): ByteArray {
        val buf = ByteBuffer.allocate(CONTROLLER_MULTI_LEN)
        encodeControllerMulti(
            buf,
            controllerNumber,
            activeMask,
            buttons,
            leftTrigger,
            rightTrigger,
            leftStickX,
            leftStickY,
            rightStickX,
            rightStickY,
        )
        return buf.toByteArray()
    }

    /**
     * CONTROLLER_ARRIVAL: which pad turned up, what it should be emulated as,
     * and what it can do.
     *
     * THE BODY IS EIGHT BYTES, NOT SEVEN. Its fields are a u8 number, a u8 type,
     * a u8 capabilities bitfield and a u32 button mask, which add up to seven;
     * but the host reads them out of a naturally aligned struct, so the u32
     * starts at offset 4 and there is a reserved byte at offset 3. Sending seven
     * shifts everything after the type by one: a live Sunshine host read our
     * capabilities 0x03 as 0xFF03, claiming a touchpad, gyro, accelerometer,
     * battery and RGB LED this pad does not have, and read our 0xFFFF button
     * mask as 0x000000FF. Its log said `capabilities [FF03] supportedButtonFlags
     * [000000FF]` and that is exactly the tell.
     */
    fun controllerArrival(
        controllerNumber: Int,
        controllerType: Int,
        capabilities: Int,
        supportedButtons: Int,
    ): ByteArray {
        val buf = ByteBuffer.allocate(CONTROLLER_ARRIVAL_LEN).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(MoonlightControlProtocol.CTRL_INPUT_DATA.toShort())
        buf.putShort((CONTROLLER_ARRIVAL_LEN - CONTROL_HEADER_LEN).toShort())
        putIntBE(buf, ARRIVAL_DATA_SIZE)
        buf.putInt(MoonlightControlProtocol.INPUT_CONTROLLER_ARRIVAL)
        buf.put((controllerNumber and 0xFF).toByte())
        buf.put((controllerType and 0xFF).toByte())
        buf.put((capabilities and 0xFF).toByte())
        buf.put(0) // reserved, and the struct's alignment padding
        // supportedButtons is little-endian in the arrival struct.
        buf.putInt(supportedButtons)
        return buf.toByteArray()
    }

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
        buf.putShort(MoonlightControlProtocol.CTRL_INPUT_DATA.toShort())
        buf.putShort((MOUSE_MOVE_REL_LEN - CONTROL_HEADER_LEN).toShort())
        putIntBE(buf, MOUSE_REL_DATA_SIZE)
        buf.putInt(MoonlightControlProtocol.INPUT_MOUSE_MOVE_REL)
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
        buf.putShort(MoonlightControlProtocol.CTRL_INPUT_DATA.toShort())
        buf.putShort((MOUSE_BUTTON_LEN - CONTROL_HEADER_LEN).toShort())
        putIntBE(buf, MOUSE_BUTTON_DATA_SIZE)
        buf.putInt(
            if (down) {
                MoonlightControlProtocol.INPUT_MOUSE_BUTTON_DOWN
            } else {
                MoonlightControlProtocol.INPUT_MOUSE_BUTTON_UP
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
        buf.putShort(MoonlightControlProtocol.CTRL_INPUT_DATA.toShort())
        buf.putShort((MOUSE_SCROLL_LEN - CONTROL_HEADER_LEN).toShort())
        putIntBE(buf, MOUSE_SCROLL_DATA_SIZE)
        buf.putInt(MoonlightControlProtocol.INPUT_MOUSE_SCROLL)
        putShortBE(buf, amount)
        putShortBE(buf, amount)
        putShortBE(buf, 0)
        return buf.toByteArray()
    }

    /** PERIODIC_PING keepalive (control-specs.adoc): header only, no body. */
    fun periodicPing(): ByteArray {
        val buf = ByteBuffer.allocate(PERIODIC_PING_LEN).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(MoonlightControlProtocol.CTRL_PERIODIC_PING.toShort())
        buf.putShort((PERIODIC_PING_LEN - CONTROL_HEADER_LEN).toShort())
        // Wolf's captured ping carries a 4-byte body of zeroes.
        buf.putInt(0)
        return buf.toByteArray()
    }

    /** TERMINATION on quit: reason is big-endian (Wolf ControlTerminatePacket). */
    fun termination(): ByteArray {
        val buf = ByteBuffer.allocate(TERMINATION_LEN).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(MoonlightControlProtocol.CTRL_TERMINATION.toShort())
        buf.putShort((TERMINATION_LEN - CONTROL_HEADER_LEN).toShort())
        putIntBE(buf, MoonlightControlProtocol.TERMINATE_REASON_GRACEFUL)
        return buf.toByteArray()
    }

    private const val CONTROL_HEADER_LEN = 4

    private fun putIntBE(
        buf: ByteBuffer,
        value: Int,
    ) {
        buf.put((value ushr 24).toByte())
        buf.put((value ushr 16).toByte())
        buf.put((value ushr 8).toByte())
        buf.put(value.toByte())
    }

    private fun putShortBE(
        buf: ByteBuffer,
        value: Int,
    ) {
        buf.put((value ushr 8).toByte())
        buf.put(value.toByte())
    }

    // Flip-then-copy for the allocating builders; encodeControllerMulti has
    // already flipped, so its remaining() is the message itself.
    private fun ByteBuffer.toByteArray(): ByteArray {
        if (position() != 0) flip()
        val out = ByteArray(remaining())
        get(out)
        return out
    }
}
