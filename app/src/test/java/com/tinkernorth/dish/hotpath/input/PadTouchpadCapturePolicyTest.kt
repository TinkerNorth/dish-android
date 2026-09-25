// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.composer.TouchpadSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PadTouchpadCapturePolicyTest {
    private fun pad(
        id: Int,
        surface: Int?,
        synthetic: Boolean = false,
    ) = PhysicalGamepadRegistry.Device(
        id = id,
        name = "Pad-$id",
        touchpadDeviceId = surface,
        isUsbSynthetic = synthetic,
        vendorId = 0x054C,
        productId = 0x09CC,
    )

    @Test
    fun `a routed framework pad with a surface maps that surface to its slot`() {
        val routes =
            routes(
                devices = mapOf(7 to pad(7, surface = 7), 8 to pad(8, surface = 31)),
                reachable = setOf("7", "8"),
                touchpadSource = { TouchpadSource.PAD },
            )
        assertEquals(mapOf(7 to "7", 31 to "8"), routes)
    }

    @Test
    fun `a pad without a surface, an unbound pad, and a Direct pad route nothing`() {
        val routes =
            routes(
                devices =
                    mapOf(
                        1 to pad(1, surface = null),
                        2 to pad(2, surface = 2),
                        -3 to pad(-3, surface = -3, synthetic = true),
                    ),
                reachable = setOf("1", "-3"),
                touchpadSource = { TouchpadSource.PAD },
            )
        assertTrue(routes.isEmpty())
    }

    @Test
    fun `the composer's source decides, so a pad the phone screen sources is not captured`() {
        val routes =
            routes(
                devices = mapOf(4 to pad(4, surface = 4)),
                reachable = setOf("4"),
                touchpadSource = { TouchpadSource.PHONE },
            )
        assertTrue(routes.isEmpty())
    }

    @Test
    fun `capture wants focus and at least one route`() {
        assertFalse(shouldCapture(emptyMap(), focused = true))
        assertFalse(shouldCapture(mapOf(1 to "1"), focused = false))
        assertTrue(shouldCapture(mapOf(1 to "1"), focused = true))
    }

    @Test
    fun `only a captured touchpad event for a routed device names a slot`() {
        val routes = mapOf(7 to "7")
        val touchpad = SOURCE_TOUCHPAD
        assertEquals("7", slotForEvent(routes, touchpad, 7))
        // The pad's joystick axes and its mouse-mode cursor moves keep their own path.
        assertEquals(null, slotForEvent(routes, 0x01000010, 7)) // SOURCE_JOYSTICK
        assertEquals(null, slotForEvent(routes, 0x00002002, 7)) // SOURCE_MOUSE
        // A surface the app does not route is not consumed either.
        assertEquals(null, slotForEvent(routes, touchpad, 8))
    }
}
