// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotCapabilitiesTest {
    private val everything = CapabilitySet.of(Feature.GAMEPAD, Feature.RUMBLE, Feature.MOTION)

    private fun slot(
        controller: CapabilitySet = everything,
        transport: CapabilitySet = everything,
        type: CapabilitySet = everything,
        host: CapabilitySet = everything,
        userEnabled: CapabilitySet = everything,
        runtimeDown: CapabilitySet = CapabilitySet.EMPTY,
    ) = SlotCapabilities(controller, transport, type, host, userEnabled, runtimeDown)

    @Test
    fun `available is what every inherent layer carries, ignoring the user's toggles`() {
        val s = slot(userEnabled = CapabilitySet.EMPTY)
        assertEquals(everything, s.available)
    }

    @Test
    fun `any one inherent layer missing a feature crosses it out of available`() {
        assertFalse(slot(controller = CapabilitySet.of(Feature.GAMEPAD)).isAvailable(Feature.RUMBLE))
        assertFalse(slot(transport = CapabilitySet.of(Feature.GAMEPAD)).isAvailable(Feature.RUMBLE))
        assertFalse(slot(type = CapabilitySet.of(Feature.GAMEPAD)).isAvailable(Feature.RUMBLE))
        assertFalse(slot(host = CapabilitySet.of(Feature.GAMEPAD)).isAvailable(Feature.RUMBLE))
    }

    @Test
    fun `enabled is available narrowed by the user's toggles`() {
        val s = slot(userEnabled = CapabilitySet.of(Feature.RUMBLE))
        assertEquals(CapabilitySet.of(Feature.RUMBLE), s.enabled)
        assertTrue(s.isEnabled(Feature.RUMBLE))
        assertFalse(s.isEnabled(Feature.MOTION))
    }

    @Test
    fun `a user toggle cannot enable what no layer carries`() {
        val s = slot(host = CapabilitySet.EMPTY, userEnabled = everything)
        assertEquals(CapabilitySet.EMPTY, s.enabled)
    }

    @Test
    fun `live is enabled minus whatever is down right now`() {
        val s = slot(runtimeDown = CapabilitySet.of(Feature.RUMBLE))
        assertEquals(CapabilitySet.of(Feature.GAMEPAD, Feature.MOTION), s.live)
    }

    @Test
    fun `a runtime outage does not change enabled, only live`() {
        val s = slot(runtimeDown = CapabilitySet.of(Feature.RUMBLE))
        assertTrue(s.isEnabled(Feature.RUMBLE))
        assertFalse(Feature.RUMBLE in s.live)
    }

    @Test
    fun `userWants reads the raw toggle, independent of whether the path can carry it`() {
        val s = slot(host = CapabilitySet.EMPTY, userEnabled = CapabilitySet.of(Feature.MOTION))
        assertTrue(s.userWants(Feature.MOTION))
        assertFalse(s.isAvailable(Feature.MOTION))
    }

    @Test
    fun `the column helpers each report one limiting layer`() {
        val s =
            slot(
                controller = CapabilitySet.of(Feature.GAMEPAD),
                transport = CapabilitySet.of(Feature.GAMEPAD, Feature.RUMBLE),
                type = CapabilitySet.of(Feature.RUMBLE),
                host = CapabilitySet.of(Feature.GAMEPAD, Feature.RUMBLE),
            )
        assertTrue(s.inputOk(Feature.GAMEPAD))
        assertFalse(s.inputOk(Feature.RUMBLE))
        assertTrue(s.typeOk(Feature.RUMBLE))
        assertFalse(s.typeOk(Feature.GAMEPAD))
        assertTrue(s.destinationOk(Feature.GAMEPAD))
    }

    @Test
    fun `destinationOk needs both the transport and the host`() {
        assertFalse(slot(transport = CapabilitySet.EMPTY).destinationOk(Feature.RUMBLE))
        assertFalse(slot(host = CapabilitySet.EMPTY).destinationOk(Feature.RUMBLE))
        assertTrue(slot().destinationOk(Feature.RUMBLE))
    }

    @Test
    fun `NONE carries nothing at any layer`() {
        assertEquals(CapabilitySet.EMPTY, SlotCapabilities.NONE.available)
        assertEquals(CapabilitySet.EMPTY, SlotCapabilities.NONE.enabled)
        assertEquals(CapabilitySet.EMPTY, SlotCapabilities.NONE.live)
    }

    @Test
    fun `sends and receives split a set by direction and lose nothing`() {
        val set = CapabilitySet.of(Feature.GAMEPAD, Feature.RUMBLE, Feature.MIC, Feature.SPEAKER)
        assertEquals(setOf(Feature.GAMEPAD, Feature.MIC), set.sends().toSet())
        assertEquals(setOf(Feature.RUMBLE, Feature.SPEAKER), set.receives().toSet())
        assertEquals(set.features, (set.sends() + set.receives()).toSet())
    }
}
