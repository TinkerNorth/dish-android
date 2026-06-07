// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RumbleRouterTest {
    private fun slot(index: Int) = SatelliteConnection.SlotBinding(controllerIndex = index, controllerType = 0, registered = true)

    @Test
    fun `resolveSlotId returns the slot whose controller index matches`() {
        val slots = mapOf(VIRTUAL_SLOT_ID to slot(0), "1234" to slot(1), "-1000" to slot(2))
        assertEquals(VIRTUAL_SLOT_ID, resolveSlotId(slots, 0))
        assertEquals("1234", resolveSlotId(slots, 1))
        assertEquals("-1000", resolveSlotId(slots, 2))
    }

    @Test
    fun `resolveSlotId returns null when no slot matches or map is empty`() {
        assertNull(resolveSlotId(mapOf(VIRTUAL_SLOT_ID to slot(0)), 3))
        assertNull(resolveSlotId(emptyMap(), 0))
    }

    @Test
    fun `classifyTarget routes the virtual slot to the phone`() {
        assertEquals(RumbleTarget.Phone, classifyTarget(VIRTUAL_SLOT_ID))
    }

    @Test
    fun `classifyTarget routes a framework device id to its own actuator`() {
        assertEquals(RumbleTarget.Framework(1234), classifyTarget("1234"))
        assertEquals(RumbleTarget.Framework(0), classifyTarget("0"))
    }

    @Test
    fun `classifyTarget routes a negative synthetic id to the USB-direct path`() {
        assertEquals(RumbleTarget.DirectUsb(-1000), classifyTarget("-1000"))
        assertEquals(RumbleTarget.DirectUsb(-1), classifyTarget("-1"))
    }

    @Test
    fun `classifyTarget yields None for an unparseable slot id`() {
        assertEquals(RumbleTarget.None, classifyTarget("not-an-int"))
        assertEquals(RumbleTarget.None, classifyTarget(""))
    }
}
