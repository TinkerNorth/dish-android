// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import com.tinkernorth.dish.core.net.bytesToHex
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Byte-exact against Wolf's input-data.adoc network fixtures and
 * testControl.cpp. These are the decrypted control-stream plaintexts the dish
 * sends; the transport seals and ENet-frames them.
 */
class MoonlightInputEncoderTest {
    @Test
    fun `CONTROLLER_MULTI matches Wolf's network fixture (button A pressed)`() {
        // Wolf testControl.cpp joypad packet: ctrl 0, active mask 1, A (0x1000).
        val bytes =
            MoonlightInputEncoder.controllerMulti(
                controllerNumber = 0,
                activeMask = 1,
                buttons = MoonlightControlProtocol.BTN_A,
                leftTrigger = 0,
                rightTrigger = 0,
                leftStickX = 0,
                leftStickY = 0,
                rightStickX = 0,
                rightStickY = 0,
            )
        assertEquals(
            "060222000000001e0c0000001a000000010014000010000000000000000000009c0000005500",
            bytesToHex(bytes),
        )
    }

    @Test
    fun `CONTROLLER_MULTI splits high buttons into buttonFlags2`() {
        val bytes =
            MoonlightInputEncoder.controllerMulti(
                controllerNumber = 1,
                activeMask = 0b11,
                buttons = MoonlightControlProtocol.BTN_A or MoonlightControlProtocol.BTN_PADDLE1,
                leftTrigger = 0xFF,
                rightTrigger = 0x80,
                leftStickX = 0x1234,
                leftStickY = -0x1234,
                rightStickX = 0x7FFF,
                rightStickY = -0x8000,
            )
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(20)
        assertEquals(MoonlightControlProtocol.BTN_A, buf.short.toInt() and 0xFFFF) // low flags
        assertEquals(0xFF, buf.get().toInt() and 0xFF) // LT
        assertEquals(0x80, buf.get().toInt() and 0xFF) // RT
        assertEquals(0x1234, buf.short.toInt())
        assertEquals(-0x1234, buf.short.toInt())
        assertEquals(0x7FFF, buf.short.toInt())
        assertEquals(-0x8000, buf.short.toInt())
        buf.short // tail_a
        // buttonFlags2 carries PADDLE1 (>> 16).
        assertEquals(MoonlightControlProtocol.BTN_PADDLE1 ushr 16, buf.short.toInt() and 0xFFFF)
    }

    @Test
    fun `hot-path encode into a reused buffer matches the allocating form`() {
        val reused = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        MoonlightInputEncoder.encodeControllerMulti(reused, 0, 1, MoonlightControlProtocol.BTN_B, 0, 0, 0, 0, 0, 0)
        val first = ByteArray(reused.remaining()).also { reused.get(it) }
        // Re-encode a different state into the SAME buffer with no reallocation.
        MoonlightInputEncoder.encodeControllerMulti(reused, 0, 1, MoonlightControlProtocol.BTN_A, 0, 0, 0, 0, 0, 0)
        val second = ByteArray(reused.remaining()).also { reused.get(it) }
        assertEquals(MoonlightInputEncoder.CONTROLLER_MULTI_LEN, first.size)
        assertEquals(
            bytesToHex(MoonlightInputEncoder.controllerMulti(0, 1, MoonlightControlProtocol.BTN_A, 0, 0, 0, 0, 0, 0)),
            bytesToHex(second),
        )
    }

    @Test
    fun `MOUSE_MOVE_REL matches the input-data adoc network fixture`() {
        // delta X = -1 (0xFFFF big-endian), delta Y = 0.
        val bytes = MoonlightInputEncoder.mouseMoveRel(deltaX = -1, deltaY = 0)
        assertEquals("0602" + "0c00" + "00000008" + "07000000" + "ffff" + "0000", bytesToHex(bytes))
    }

    @Test
    fun `MOUSE_BUTTON_DOWN carries the one-byte button id`() {
        val bytes = MoonlightInputEncoder.mouseButton(down = true, button = MoonlightControlProtocol.MOUSE_BUTTON_LEFT)
        assertEquals(MoonlightInputEncoder.MOUSE_BUTTON_LEN, bytes.size)
        assertEquals("0602" + "0900" + "00000005" + "08000000" + "01", bytesToHex(bytes))
    }

    @Test
    fun `MOUSE_BUTTON_UP flips only the wrapped input type`() {
        val bytes = MoonlightInputEncoder.mouseButton(down = false, button = MoonlightControlProtocol.MOUSE_BUTTON_RIGHT)
        assertEquals("0602" + "0900" + "00000005" + "09000000" + "03", bytesToHex(bytes))
    }

    @Test
    fun `MOUSE_SCROLL duplicates the big-endian amount and pads a zero i16`() {
        val up = MoonlightInputEncoder.mouseScroll(120)
        assertEquals(MoonlightInputEncoder.MOUSE_SCROLL_LEN, up.size)
        assertEquals("0602" + "0e00" + "0000000a" + "0a000000" + "0078" + "0078" + "0000", bytesToHex(up))
        val down = MoonlightInputEncoder.mouseScroll(-120)
        assertEquals("0602" + "0e00" + "0000000a" + "0a000000" + "ff88" + "ff88" + "0000", bytesToHex(down))
    }

    @Test
    fun `CONTROLLER_ARRIVAL carries type and capabilities`() {
        val bytes =
            MoonlightInputEncoder.controllerArrival(
                controllerNumber = 0,
                controllerType = MoonlightControlProtocol.CONTROLLER_TYPE_XBOX,
                capabilities = MoonlightControlProtocol.CAP_ANALOG_TRIGGERS or MoonlightControlProtocol.CAP_RUMBLE,
                supportedButtons = 0xFFFF,
            )
        assertEquals(MoonlightInputEncoder.CONTROLLER_ARRIVAL_LEN, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(MoonlightControlProtocol.CTRL_INPUT_DATA, buf.short.toInt() and 0xFFFF)
        buf.short // plen
        // input size is big-endian and counts type + the 8-byte arrival body.
        assertEquals(12, ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.BIG_ENDIAN).int)
        buf.position(8)
        assertEquals(MoonlightControlProtocol.INPUT_CONTROLLER_ARRIVAL, buf.int)
        assertEquals(0, buf.get().toInt())
        assertEquals(MoonlightControlProtocol.CONTROLLER_TYPE_XBOX, buf.get().toInt())
        assertEquals(0x03, buf.get().toInt())
        // The reserved byte the host's struct alignment puts here. Omitting it
        // shifted the button mask a byte left and left the host reading our
        // capabilities as 0xFF03.
        assertEquals(0, buf.get().toInt())
        assertEquals(0xFFFF, buf.int)
    }

    @Test
    fun `CONTROLLER_TOUCH is byte-exact against the Wolf packed struct`() {
        // ctrl 1, DOWN, pointer 7, x 0.5 / y 0.25 as little-endian IEEE-754
        // netfloats, pressure 1.0. data_size (BE) counts type + 20-byte body.
        val bytes =
            MoonlightInputEncoder.controllerTouch(
                controllerNumber = 1,
                eventType = MoonlightControlProtocol.TOUCH_EVENT_DOWN,
                pointerId = 7,
                x = 0.5f,
                y = 0.25f,
                pressure = 1.0f,
            )
        assertEquals(MoonlightInputEncoder.CONTROLLER_TOUCH_LEN, bytes.size)
        assertEquals(
            "06021c000000001805000055" + "0101" + "0000" + "07000000" +
                "0000003f" + "0000803e" + "0000803f",
            bytesToHex(bytes),
        )
    }

    @Test
    fun `CONTROLLER_MOTION is byte-exact with gyro floats in deg per second`() {
        val bytes =
            MoonlightInputEncoder.controllerMotion(
                controllerNumber = 2,
                motionType = MoonlightControlProtocol.MOTION_TYPE_GYRO,
                x = 1.0f,
                y = -1.0f,
                z = 0.5f,
            )
        assertEquals(MoonlightInputEncoder.CONTROLLER_MOTION_LEN, bytes.size)
        assertEquals(
            "060218000000001406000055" + "0202" + "0000" +
                "0000803f" + "000080bf" + "0000003f",
            bytesToHex(bytes),
        )
    }

    @Test
    fun `CONTROLLER_BATTERY is byte-exact with the trailing alignment byte`() {
        val bytes =
            MoonlightInputEncoder.controllerBattery(
                controllerNumber = 0,
                batteryState = MoonlightControlProtocol.BATTERY_CHARGING,
                percentage = 88,
            )
        assertEquals(MoonlightInputEncoder.CONTROLLER_BATTERY_LEN, bytes.size)
        assertEquals("06020c000000000807000055" + "00035800", bytesToHex(bytes))
    }

    @Test
    fun `termination carries the graceful reason big-endian`() {
        val bytes = MoonlightInputEncoder.termination()
        assertEquals("00010400" + "80030023", bytesToHex(bytes))
    }

    @Test
    fun `periodic ping is header plus a zero body`() {
        assertEquals("00020400" + "00000000", bytesToHex(MoonlightInputEncoder.periodicPing()))
    }
}
