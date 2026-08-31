// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRowsTest {
    @Test
    fun `rows come back in rumble, motion, touchpad order`() {
        val rows = capabilityRows(SlotCapabilities.NONE)
        assertEquals(
            listOf(
                SetupCapabilityKind.RUMBLE,
                SetupCapabilityKind.MOTION,
                SetupCapabilityKind.TOUCHPAD,
            ),
            rows.map { it.kind },
        )
    }

    @Test
    fun `the extended rows appear only when some layer can carry them`() {
        // A DualSense-typed slot on a Direct DualSense: every surface has a lane.
        val everything = CapabilitySet(Feature.entries.toSet())
        val full =
            SlotCapabilities(
                controller = everything,
                transport = everything,
                type = everything,
                host = everything,
                userEnabled = CapabilitySet.EMPTY,
                runtimeDown = CapabilitySet.EMPTY,
            )
        assertEquals(
            listOf(
                SetupCapabilityKind.RUMBLE,
                SetupCapabilityKind.MOTION,
                SetupCapabilityKind.TOUCHPAD,
                SetupCapabilityKind.BATTERY,
                SetupCapabilityKind.LIGHTBAR,
                SetupCapabilityKind.TRIGGER_RUMBLE,
                SetupCapabilityKind.TRIGGER_EFFECTS,
                SetupCapabilityKind.PLAYER_LEDS,
            ),
            capabilityRows(full).map { it.kind },
        )

        // A bare pad on a plain path: the card stays the classic three rows.
        assertEquals(3, capabilityRows(SlotCapabilities.NONE).size)

        // One live layer is enough to surface a row, so a limitation is shown
        // as its crossed columns rather than hidden.
        val batteryOnlyInput =
            SlotCapabilities(
                controller = CapabilitySet.of(Feature.BATTERY),
                transport = CapabilitySet.EMPTY,
                type = CapabilitySet.EMPTY,
                host = CapabilitySet.EMPTY,
                userEnabled = CapabilitySet.EMPTY,
                runtimeDown = CapabilitySet.EMPTY,
            )
        val rows = capabilityRows(batteryOnlyInput)
        assertEquals(4, rows.size)
        val battery = rows.last()
        assertEquals(SetupCapabilityKind.BATTERY, battery.kind)
        assertTrue(battery.inputOk)
        assertFalse(battery.destinationOk)
        assertFalse(battery.available)
    }

    @Test
    fun `each row pulls its columns from the matching feature's layers`() {
        // RUMBLE: limited at the input (controller missing it).
        // MOTION: limited at the destination (host missing it).
        // TOUCHPAD: limited at the type.
        val caps =
            SlotCapabilities(
                controller = CapabilitySet.of(Feature.MOTION, Feature.TOUCHPAD),
                transport = CapabilitySet.of(Feature.RUMBLE, Feature.MOTION, Feature.TOUCHPAD),
                type = CapabilitySet.of(Feature.RUMBLE, Feature.MOTION),
                host = CapabilitySet.of(Feature.RUMBLE, Feature.TOUCHPAD),
                userEnabled = CapabilitySet.EMPTY,
                runtimeDown = CapabilitySet.EMPTY,
            )
        val rows = capabilityRows(caps).associateBy { it.kind }

        val rumble = rows.getValue(SetupCapabilityKind.RUMBLE)
        assertFalse(rumble.inputOk)
        assertTrue(rumble.destinationOk)
        assertTrue(rumble.typeOk)

        val motion = rows.getValue(SetupCapabilityKind.MOTION)
        assertTrue(motion.inputOk)
        assertFalse(motion.destinationOk)
        assertTrue(motion.typeOk)

        val touchpad = rows.getValue(SetupCapabilityKind.TOUCHPAD)
        assertTrue(touchpad.inputOk)
        assertTrue(touchpad.destinationOk)
        assertFalse(touchpad.typeOk)
    }

    @Test
    fun `available is the conjunction of all three columns`() {
        val caps =
            SlotCapabilities(
                controller = CapabilitySet.of(Feature.RUMBLE),
                transport = CapabilitySet.of(Feature.RUMBLE),
                type = CapabilitySet.of(Feature.RUMBLE),
                host = CapabilitySet.of(Feature.RUMBLE),
                userEnabled = CapabilitySet.EMPTY,
                runtimeDown = CapabilitySet.EMPTY,
            )
        val rows = capabilityRows(caps).associateBy { it.kind }

        val rumble = rows.getValue(SetupCapabilityKind.RUMBLE)
        assertTrue(rumble.available)
        assertEquals(rumble.inputOk && rumble.destinationOk && rumble.typeOk, rumble.available)

        val motion = rows.getValue(SetupCapabilityKind.MOTION)
        assertFalse(motion.available)
        assertEquals(motion.inputOk && motion.destinationOk && motion.typeOk, motion.available)
    }
}
