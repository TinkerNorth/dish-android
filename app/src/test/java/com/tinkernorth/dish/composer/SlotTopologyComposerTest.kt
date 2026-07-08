// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import org.junit.Assert.assertEquals
import org.junit.Test

class SlotTopologyComposerTest {
    @Test
    fun `a satellite binding projects with its stored type`() {
        val topology =
            composeSlotTopology(
                bindings = mapOf("5" to "satellite:m1"),
                types = mapOf(("satellite:m1" to "5") to CONTROLLER_TYPE_PLAYSTATION),
            )
        assertEquals(mapOf("satellite:m1" to mapOf("5" to CONTROLLER_TYPE_PLAYSTATION)), topology)
    }

    @Test
    fun `a binding without a stored type defaults to xbox`() {
        val topology = composeSlotTopology(bindings = mapOf("5" to "satellite:m1"), types = emptyMap())
        assertEquals(mapOf("satellite:m1" to mapOf("5" to CONTROLLER_TYPE_XBOX)), topology)
    }

    @Test
    fun `bluetooth bindings carry no satellite descriptor`() {
        val topology = composeSlotTopology(bindings = mapOf("5" to "bt:aa:bb"), types = emptyMap())
        assertEquals(emptyMap<String, Map<String, Int>>(), topology)
    }

    @Test
    fun `slots group under their own connection`() {
        val topology =
            composeSlotTopology(
                bindings = mapOf("5" to "satellite:m1", "7" to "satellite:m2", "virtual" to "satellite:m1"),
                types = mapOf(("satellite:m1" to "virtual") to CONTROLLER_TYPE_PLAYSTATION),
            )
        assertEquals(
            mapOf(
                "satellite:m1" to
                    mapOf("5" to CONTROLLER_TYPE_XBOX, "virtual" to CONTROLLER_TYPE_PLAYSTATION),
                "satellite:m2" to mapOf("7" to CONTROLLER_TYPE_XBOX),
            ),
            topology,
        )
    }

    @Test
    fun `a type stored for another connection does not leak into the projection`() {
        val topology =
            composeSlotTopology(
                bindings = mapOf("5" to "satellite:m1"),
                types = mapOf(("satellite:m2" to "5") to CONTROLLER_TYPE_PLAYSTATION),
            )
        assertEquals(mapOf("satellite:m1" to mapOf("5" to CONTROLLER_TYPE_XBOX)), topology)
    }

    @Test
    fun `no bindings compose an empty topology`() {
        assertEquals(emptyMap<String, Map<String, Int>>(), composeSlotTopology(emptyMap(), emptyMap()))
    }
}
