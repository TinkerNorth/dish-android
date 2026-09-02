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
                SetupCapabilityKind.MICROPHONE,
                SetupCapabilityKind.SPEAKER,
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

    @Test
    fun `the audio rows appear only where some layer carries them`() {
        // A plain Xbox-over-Bluetooth card must not grow two crossed-out audio rows.
        assertTrue(capabilityRows(SlotCapabilities.NONE).none { it.kind == SetupCapabilityKind.MICROPHONE })
        assertTrue(capabilityRows(SlotCapabilities.NONE).none { it.kind == SetupCapabilityKind.SPEAKER })

        // A host that carries audio surfaces both rows even before an input can source
        // them, so the limitation reads as a crossed column rather than a missing row.
        val hostOnly =
            SlotCapabilities(
                controller = CapabilitySet.EMPTY,
                transport = CapabilitySet.of(Feature.MIC, Feature.SPEAKER),
                type = CapabilitySet.of(Feature.MIC, Feature.SPEAKER),
                host = CapabilitySet.of(Feature.MIC, Feature.SPEAKER),
                userEnabled = CapabilitySet.EMPTY,
                runtimeDown = CapabilitySet.EMPTY,
            )
        val rows = capabilityRows(hostOnly).associateBy { it.kind }
        val mic = rows.getValue(SetupCapabilityKind.MICROPHONE)
        assertFalse(mic.inputOk)
        assertTrue(mic.destinationOk)
        assertTrue(mic.typeOk)
        assertFalse(mic.available)
        assertTrue(SetupCapabilityKind.SPEAKER in rows.keys)
    }

    @Test
    fun `the two audio rows are independent of each other`() {
        // A pad that plays but has no headset mic shows one row, not two.
        val speakerOnly =
            SlotCapabilities(
                controller = CapabilitySet.of(Feature.SPEAKER),
                transport = CapabilitySet.of(Feature.SPEAKER),
                type = CapabilitySet.of(Feature.SPEAKER),
                host = CapabilitySet.of(Feature.SPEAKER),
                userEnabled = CapabilitySet.EMPTY,
                runtimeDown = CapabilitySet.EMPTY,
            )
        val rows = capabilityRows(speakerOnly).associateBy { it.kind }
        assertTrue(SetupCapabilityKind.SPEAKER in rows.keys)
        assertFalse(SetupCapabilityKind.MICROPHONE in rows.keys)
        assertTrue(rows.getValue(SetupCapabilityKind.SPEAKER).available)
    }

    @Test
    fun `inputUnknown rides every row and reads unknown only where the rest of the path carries`() {
        val everything = CapabilitySet(Feature.entries.toSet())
        val caps =
            SlotCapabilities(
                controller = CapabilitySet.EMPTY,
                transport = everything,
                type = CapabilitySet.of(Feature.RUMBLE, Feature.MOTION),
                host = everything,
                userEnabled = CapabilitySet.EMPTY,
                runtimeDown = CapabilitySet.EMPTY,
            )
        val rows = capabilityRows(caps, inputUnknown = true).associateBy { it.kind }

        val rumble = rows.getValue(SetupCapabilityKind.RUMBLE)
        assertTrue(rumble.inputUnknown)
        assertTrue(rumble.unknown)
        assertFalse(rumble.available)

        val touchpad = rows.getValue(SetupCapabilityKind.TOUCHPAD)
        assertTrue(touchpad.inputUnknown)
        assertFalse(touchpad.unknown)

        assertTrue(capabilityRows(caps).none { it.inputUnknown })
    }
}
