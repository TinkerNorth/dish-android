// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticTwinDedupTest {
    private fun routed(
        id: Int,
        vid: Int,
        pid: Int,
        disconnecting: Boolean = false,
    ) = Device(
        id = id,
        name = "routed-$id",
        disconnectingTimeLeftSec = if (disconnecting) 3 else null,
        isUsbSynthetic = false,
        vendorId = vid,
        productId = pid,
    )

    private fun synthetic(
        id: Int,
        vid: Int,
        pid: Int,
    ) = Device(
        id = id,
        name = "synthetic-$id",
        isUsbSynthetic = true,
        vendorId = vid,
        productId = pid,
    )

    @Test
    fun `empty input hides nothing`() {
        assertEquals(emptySet<Int>(), routedTwinIdsHiddenBySynthetics(emptyList()))
    }

    @Test
    fun `with no synthetics nothing is hidden`() {
        val devices = listOf(routed(1, 0x045E, 0x028E), routed(2, 0x054C, 0x05C4))
        assertEquals(emptySet<Int>(), routedTwinIdsHiddenBySynthetics(devices))
    }

    @Test
    fun `one synthetic hides its single same-model routed twin`() {
        val devices =
            listOf(
                synthetic(-1000, 0x045E, 0x028E),
                routed(7, 0x045E, 0x028E),
            )
        assertEquals(setOf(7), routedTwinIdsHiddenBySynthetics(devices))
    }

    @Test
    fun `a routed controller of a different model is left visible`() {
        val devices =
            listOf(
                synthetic(-1000, 0x045E, 0x028E),
                routed(7, 0x054C, 0x05C4),
            )
        assertEquals(emptySet<Int>(), routedTwinIdsHiddenBySynthetics(devices))
    }

    @Test
    fun `two synthetics of one model hide both same-model routed twins`() {
        val devices =
            listOf(
                synthetic(-1000, 0x045E, 0x028E),
                synthetic(-1001, 0x045E, 0x028E),
                routed(7, 0x045E, 0x028E),
                routed(8, 0x045E, 0x028E),
            )
        assertEquals(setOf(7, 8), routedTwinIdsHiddenBySynthetics(devices))
    }

    @Test
    fun `one synthetic with two routed twins hides the disconnecting one first`() {
        val devices =
            listOf(
                synthetic(-1000, 0x045E, 0x028E),
                routed(7, 0x045E, 0x028E, disconnecting = false),
                routed(8, 0x045E, 0x028E, disconnecting = true),
            )
        assertEquals(setOf(8), routedTwinIdsHiddenBySynthetics(devices))
    }

    @Test
    fun `one synthetic with two live routed twins hides exactly one`() {
        val devices =
            listOf(
                synthetic(-1000, 0x045E, 0x028E),
                routed(7, 0x045E, 0x028E),
                routed(8, 0x045E, 0x028E),
            )
        val hidden = routedTwinIdsHiddenBySynthetics(devices)
        assertEquals(1, hidden.size)
        assertTrue(hidden.single() in setOf(7, 8))
    }

    @Test
    fun `more synthetics than routed twins hides every available twin without error`() {
        val devices =
            listOf(
                synthetic(-1000, 0x045E, 0x028E),
                synthetic(-1001, 0x045E, 0x028E),
                routed(7, 0x045E, 0x028E),
            )
        assertEquals(setOf(7), routedTwinIdsHiddenBySynthetics(devices))
    }

    @Test
    fun `synthetic with missing vendor and product id does not hide anything`() {
        val devices =
            listOf(
                synthetic(-1000, 0, 0),
                routed(7, 0, 0),
            )
        assertEquals(emptySet<Int>(), routedTwinIdsHiddenBySynthetics(devices))
    }

    @Test
    fun `synthetics only hide twins of their own model`() {
        val devices =
            listOf(
                synthetic(-1000, 0x045E, 0x028E),
                routed(7, 0x045E, 0x028E),
                routed(8, 0x054C, 0x05C4),
            )
        assertEquals(setOf(7), routedTwinIdsHiddenBySynthetics(devices))
    }
}
