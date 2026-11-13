// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import com.tinkernorth.dish.R
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

private const val EXT_BUTTONS_KNOWN_MASK = 0x1FFF
private const val WHEEL_NOTCH_DOWN = -120

// The layout gamepad_input_test pins in DeviceStateJson.SerializesEveryFieldInTheDocumentedLayout.
private val NATIVE_SNAPSHOT =
    """
    {"buttons":4112,"lt":12,"rt":255,"lx":-32768,"ly":32767,"rx":-1,"ry":1,
    "motionValid":true,"gx":10,"gy":-20,"gz":30,"ax":-40,"ay":50,"az":-60,
    "touchValid":true,"f0Active":true,"f0Id":7,"f0X":-100,"f0Y":200,
    "f1Active":false,"f1Id":8,"f1X":0,"f1Y":0,"click":true,
    "extButtons":18,"rightClick":true,"wheel":-120}
    """.trimIndent()

class InputSnapshotTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val context =
        mockk<Context> {
            every { getString(any()) } answers { "s${firstArg<Int>()}" }
            every { getString(R.string.inspector_pressed, *anyVararg()) } answers { "pressed ${secondArg<Array<Any?>>().first()}" }
            every { getString(R.string.inspector_wheel, *anyVararg()) } answers { "wheel ${secondArg<Array<Any?>>().first()}" }
        }

    @Test
    fun `a snapshot reads the extended-buttons word, the pointer's right button and its wheel`() {
        val snapshot = parseInputSnapshot(json, NATIVE_SNAPSHOT)
        val leftPaddleAndMisc = ExtendedButton.LEFT_PADDLE1.bit or ExtendedButton.MISC1.bit
        assertEquals(leftPaddleAndMisc, snapshot?.extButtons)
        assertEquals(true, snapshot?.rightClick)
        assertEquals(WHEEL_NOTCH_DOWN, snapshot?.wheel)
    }

    @Test
    fun `the extended buttons are the protocol 4 bits, one bit each`() {
        val bits = ExtendedButton.entries.map { it.bit }
        val everyBit = bits.reduce(Int::or)
        assertEquals(EXT_BUTTONS_KNOWN_MASK, everyBit)
        assertEquals(bits.size, Integer.bitCount(everyBit))
    }

    @Test
    fun `each extended button has its own name`() {
        val names = ExtendedButton.entries.map { it.label }.toSet()
        assertEquals(ExtendedButton.entries.size, names.size)
    }

    @Test
    fun `a pad holding nothing reads none`() {
        assertEquals("pressed s${R.string.inspector_none}", context.pressedText(InputSnapshot()))
    }

    @Test
    fun `the pressed readout names the extended buttons after the wire buttons`() {
        val snapshot =
            InputSnapshot(
                buttons = WireButton.A.bit,
                extButtons = ExtendedButton.LEFT_PADDLE1.bit or ExtendedButton.MISC1.bit,
            )
        val expected = "pressed A · s${R.string.inspector_ext_left_paddle_1} · s${R.string.inspector_ext_misc_1}"
        assertEquals(expected, context.pressedText(snapshot))
    }

    @Test
    fun `a path with no touch data says so under the pad`() {
        assertEquals("s${R.string.inspector_touch_missing}", context.touchHintText(InputSnapshot(touchValid = false)))
    }

    @Test
    fun `a touch frame with the pointer idle leaves the line under the pad empty`() {
        assertEquals("", context.touchHintText(InputSnapshot(touchValid = true)))
    }

    @Test
    fun `the pointer's right button and a wheel turn read under the pad`() {
        val snapshot = InputSnapshot(touchValid = true, rightClick = true, wheel = WHEEL_NOTCH_DOWN)
        assertEquals("s${R.string.inspector_right_click} · wheel $WHEEL_NOTCH_DOWN", context.touchHintText(snapshot))
    }
}
