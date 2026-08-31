// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationPotentialTest {
    private val all = CapabilitySet(Feature.entries.toSet())

    private fun candidate(
        controller: CapabilitySet = all,
        transport: CapabilitySet = all,
        type: CapabilitySet,
        host: CapabilitySet = all,
    ) = SlotCapabilities(
        controller = controller,
        transport = transport,
        type = type,
        host = host,
        userEnabled = all,
        runtimeDown = CapabilitySet.EMPTY,
    )

    @Test
    fun `unions the flows across candidate types, so one type's gap hides nothing`() {
        val xboxLike = candidate(type = CapabilitySet.of(Feature.GAMEPAD, Feature.RUMBLE))
        val psLike =
            candidate(type = CapabilitySet.of(Feature.GAMEPAD, Feature.MOTION, Feature.TOUCHPAD, Feature.RUMBLE))

        val potential = destinationPotential(listOf(xboxLike, psLike))
        assertTrue(Feature.MOTION in potential)
        assertTrue(Feature.TOUCHPAD in potential)
        assertTrue(Feature.RUMBLE in potential)
    }

    @Test
    fun `the input's controller layer never gates the destination card`() {
        val gyrolessPad =
            candidate(
                controller = CapabilitySet.of(Feature.GAMEPAD),
                type = CapabilitySet.of(Feature.GAMEPAD, Feature.MOTION),
            )
        assertTrue(Feature.MOTION in destinationPotential(listOf(gyrolessPad)))
    }

    @Test
    fun `transport and host still gate what the destination can actually carry`() {
        val btLike =
            candidate(
                transport = CapabilitySet.of(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS),
                type = CapabilitySet.of(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.RUMBLE, Feature.MOTION),
            )
        val potential = destinationPotential(listOf(btLike))
        assertFalse(Feature.RUMBLE in potential)
        assertFalse(Feature.MOTION in potential)

        val rumblelessHost =
            candidate(
                type = CapabilitySet.of(Feature.GAMEPAD, Feature.RUMBLE),
                host = CapabilitySet.of(Feature.GAMEPAD),
            )
        assertFalse(Feature.RUMBLE in destinationPotential(listOf(rumblelessHost)))
    }

    @Test
    fun `no candidates means no potential`() {
        assertEquals(CapabilitySet.EMPTY, destinationPotential(emptyList()))
    }
}
