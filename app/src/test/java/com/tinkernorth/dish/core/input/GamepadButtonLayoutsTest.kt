// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.input

import com.tinkernorth.dish.ui.common.GamepadTouchView
import org.junit.Assert.assertEquals
import org.junit.Test

class GamepadButtonLayoutsTest {
    @Test fun `xusb A maps to HID BTN_A`() = assertHid(0x1000, GamepadTouchView.BTN_A, 0)

    @Test fun `xusb B maps to HID BTN_B`() = assertHid(0x2000, GamepadTouchView.BTN_B, 0)

    @Test fun `xusb X maps to HID BTN_X`() = assertHid(0x4000, GamepadTouchView.BTN_X, 0)

    @Test fun `xusb Y maps to HID BTN_Y`() = assertHid(0x8000, GamepadTouchView.BTN_Y, 0)

    @Test fun `xusb LB maps to HID BTN_LB`() = assertHid(0x0100, GamepadTouchView.BTN_LB, 0)

    @Test fun `xusb RB maps to HID BTN_RB`() = assertHid(0x0200, GamepadTouchView.BTN_RB, 0)

    @Test fun `xusb BACK maps to HID BTN_SELECT`() = assertHid(0x0020, GamepadTouchView.BTN_SELECT, 0)

    @Test fun `xusb START maps to HID BTN_START`() = assertHid(0x0010, GamepadTouchView.BTN_START, 0)

    @Test fun `xusb LEFT_THUMB maps to HID BTN_LS`() = assertHid(0x0040, GamepadTouchView.BTN_LS, 0)

    @Test fun `xusb RIGHT_THUMB maps to HID BTN_RS`() = assertHid(0x0080, GamepadTouchView.BTN_RS, 0)

    @Test fun `xusb GUIDE maps to HID BTN_HOME`() = assertHid(0x0400, GamepadTouchView.BTN_HOME, 0)

    @Test fun `xusb dpad neutral maps to hat 0`() = assertHid(0x0000, 0, GamepadTouchView.HAT_NONE)

    @Test fun `xusb dpad up maps to hat N`() = assertHid(0x0001, 0, GamepadTouchView.HAT_N)

    @Test fun `xusb dpad down maps to hat S`() = assertHid(0x0002, 0, GamepadTouchView.HAT_S)

    @Test fun `xusb dpad left maps to hat W`() = assertHid(0x0004, 0, GamepadTouchView.HAT_W)

    @Test fun `xusb dpad right maps to hat E`() = assertHid(0x0008, 0, GamepadTouchView.HAT_E)

    @Test fun `xusb dpad up+right maps to hat NE`() = assertHid(0x0001 or 0x0008, 0, GamepadTouchView.HAT_NE)

    @Test fun `xusb dpad down+right maps to hat SE`() = assertHid(0x0002 or 0x0008, 0, GamepadTouchView.HAT_SE)

    @Test fun `xusb dpad down+left maps to hat SW`() = assertHid(0x0002 or 0x0004, 0, GamepadTouchView.HAT_SW)

    @Test fun `xusb dpad up+left maps to hat NW`() = assertHid(0x0001 or 0x0004, 0, GamepadTouchView.HAT_NW)

    @Test
    fun `xusb with A plus B plus X plus Y sets all four HID face bits`() {
        val packed = xusbToHid(0x1000 or 0x2000 or 0x4000 or 0x8000)
        val expected =
            GamepadTouchView.BTN_A or GamepadTouchView.BTN_B or
                GamepadTouchView.BTN_X or GamepadTouchView.BTN_Y
        assertEquals(expected, hidButtonsOf(packed))
        assertEquals(GamepadTouchView.HAT_NONE, hidHatOf(packed))
    }

    @Test
    fun `xusb zero is identity`() {
        val packed = xusbToHid(0)
        assertEquals(0, hidButtonsOf(packed))
        assertEquals(0, hidHatOf(packed))
    }

    @Test
    fun `xusb unknown bits are dropped`() {
        // 0x0800 is reserved/unused in XUSB.
        assertEquals(0, hidButtonsOf(xusbToHid(0x0800)))
    }

    @Test fun `HID BTN_A maps to xusb A`() = assertXusb(GamepadTouchView.BTN_A, 0, 0x1000)

    @Test fun `HID BTN_B maps to xusb B`() = assertXusb(GamepadTouchView.BTN_B, 0, 0x2000)

    @Test fun `HID BTN_X maps to xusb X`() = assertXusb(GamepadTouchView.BTN_X, 0, 0x4000)

    @Test fun `HID BTN_Y maps to xusb Y`() = assertXusb(GamepadTouchView.BTN_Y, 0, 0x8000)

    @Test fun `HID BTN_LB maps to xusb LB`() = assertXusb(GamepadTouchView.BTN_LB, 0, 0x0100)

    @Test fun `HID BTN_RB maps to xusb RB`() = assertXusb(GamepadTouchView.BTN_RB, 0, 0x0200)

    @Test fun `HID BTN_SELECT maps to xusb BACK`() = assertXusb(GamepadTouchView.BTN_SELECT, 0, 0x0020)

    @Test fun `HID BTN_START maps to xusb START`() = assertXusb(GamepadTouchView.BTN_START, 0, 0x0010)

    @Test fun `HID BTN_LS maps to xusb LEFT_THUMB`() = assertXusb(GamepadTouchView.BTN_LS, 0, 0x0040)

    @Test fun `HID BTN_RS maps to xusb RIGHT_THUMB`() = assertXusb(GamepadTouchView.BTN_RS, 0, 0x0080)

    @Test fun `HID BTN_HOME maps to xusb GUIDE`() = assertXusb(GamepadTouchView.BTN_HOME, 0, 0x0400)

    @Test fun `HID hat 0 maps to xusb dpad neutral`() = assertXusb(0, GamepadTouchView.HAT_NONE, 0x0000)

    @Test fun `HID hat N maps to xusb dpad up`() = assertXusb(0, GamepadTouchView.HAT_N, 0x0001)

    @Test fun `HID hat S maps to xusb dpad down`() = assertXusb(0, GamepadTouchView.HAT_S, 0x0002)

    @Test fun `HID hat W maps to xusb dpad left`() = assertXusb(0, GamepadTouchView.HAT_W, 0x0004)

    @Test fun `HID hat E maps to xusb dpad right`() = assertXusb(0, GamepadTouchView.HAT_E, 0x0008)

    @Test fun `HID hat NE maps to xusb dpad up+right`() = assertXusb(0, GamepadTouchView.HAT_NE, 0x0001 or 0x0008)

    @Test fun `HID hat SE maps to xusb dpad down+right`() = assertXusb(0, GamepadTouchView.HAT_SE, 0x0002 or 0x0008)

    @Test fun `HID hat SW maps to xusb dpad down+left`() = assertXusb(0, GamepadTouchView.HAT_SW, 0x0002 or 0x0004)

    @Test fun `HID hat NW maps to xusb dpad up+left`() = assertXusb(0, GamepadTouchView.HAT_NW, 0x0001 or 0x0004)

    @Test
    fun `xusbToHid then hidToXusb is identity for every canonical bit`() {
        val xusbBits =
            intArrayOf(
                0x0001,
                0x0002,
                0x0004,
                0x0008,
                0x0010,
                0x0020,
                0x0040,
                0x0080,
                0x0100,
                0x0200,
                0x0400,
                0x1000,
                0x2000,
                0x4000,
                0x8000,
            )
        for (bit in xusbBits) {
            val packed = xusbToHid(bit)
            assertEquals(
                "bit=0x${bit.toString(16)}",
                bit,
                hidToXusb(hidButtonsOf(packed), hidHatOf(packed)),
            )
        }
    }

    private fun assertHid(
        wButtons: Int,
        expectedHid: Int,
        expectedHat: Int,
    ) {
        val packed = xusbToHid(wButtons)
        assertEquals(
            "hid bits for wButtons=0x${wButtons.toString(16)}",
            expectedHid,
            hidButtonsOf(packed),
        )
        assertEquals(
            "hat for wButtons=0x${wButtons.toString(16)}",
            expectedHat,
            hidHatOf(packed),
        )
    }

    private fun assertXusb(
        hidButtons: Int,
        hat: Int,
        expected: Int,
    ) {
        assertEquals(expected, hidToXusb(hidButtons, hat))
    }
}
