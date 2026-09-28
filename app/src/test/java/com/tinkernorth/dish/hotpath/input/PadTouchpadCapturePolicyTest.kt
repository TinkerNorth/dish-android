// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.composer.TouchpadSource
import com.tinkernorth.dish.architecture.testing.allocatedBytesDuring
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
        val surfaces = capturedSurfaceTableOf(mapOf(7 to "7"))
        val touchpad = SOURCE_TOUCHPAD
        assertEquals("7", slotForEvent(surfaces, touchpad, 7))
        // The pad's joystick axes and its mouse-mode cursor moves keep their own path.
        assertEquals(null, slotForEvent(surfaces, 0x01000010, 7)) // SOURCE_JOYSTICK
        assertEquals(null, slotForEvent(surfaces, 0x00002002, 7)) // SOURCE_MOUSE
        // A surface the app does not route is not consumed either.
        assertEquals(null, slotForEvent(surfaces, touchpad, 8))
    }

    @Test
    fun `every routed surface names its own slot`() {
        val surfaces = capturedSurfaceTableOf(mapOf(7 to "7", 31 to "8", WIDE_SURFACE to "9"))
        assertEquals("7", surfaces.slotFor(7))
        assertEquals("8", surfaces.slotFor(31))
        assertEquals("9", surfaces.slotFor(WIDE_SURFACE))
    }

    @Test
    fun `no routes name no slot`() {
        assertEquals(null, capturedSurfaceTableOf(emptyMap()).slotFor(7))
    }

    private var kept: String? = null

    private fun lookUpWideSurface(surfaces: CapturedSurfaceTable) {
        repeat(MEASURED_LOOKUPS) { kept = slotForEvent(surfaces, SOURCE_TOUCHPAD, WIDE_SURFACE) }
    }

    // A device id past the boxed-integer cache would cost an Integer per event as a map key.
    @Test
    fun `looking up a captured event's slot boxes no device id`() {
        val surfaces = capturedSurfaceTableOf(mapOf(7 to "7", WIDE_SURFACE to "9"))
        lookUpWideSurface(surfaces)

        val allocated = allocatedBytesDuring { lookUpWideSurface(surfaces) }

        assertEquals("9", kept)
        assertTrue("$allocated bytes over $MEASURED_LOOKUPS lookups", allocated < MEASURED_LOOKUPS * BYTES_PER_LOOKUP_BOUND)
    }

    private companion object {
        const val WIDE_SURFACE = 1000
        const val MEASURED_LOOKUPS = 1000

        // Half the smallest object: a boxed id costs 16 bytes or more every lookup.
        const val BYTES_PER_LOOKUP_BOUND = 8
    }
}
