// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupCapabilityTest {
    private fun rows(
        isPlayStation: Boolean,
        destinationIsSatellite: Boolean,
        hasDestination: Boolean = true,
        hasGyro: Boolean = true,
        hasRumble: Boolean = true,
    ) = SetupCapability
        .rows(isPlayStation, destinationIsSatellite, hasDestination, hasGyro, hasRumble)
        .associateBy { it.kind }

    @Test
    fun `rows are returned in rumble, motion, touchpad order`() {
        val kinds = SetupCapability.rows(true, true, true, true, true).map { it.kind }
        assertEquals(
            listOf(SetupCapabilityKind.RUMBLE, SetupCapabilityKind.MOTION, SetupCapabilityKind.TOUCHPAD),
            kinds,
        )
    }

    @Test
    fun `playStation on satellite with full input carries everything`() {
        val r = rows(isPlayStation = true, destinationIsSatellite = true)
        assertTrue(r.getValue(SetupCapabilityKind.RUMBLE).available)
        assertTrue(r.getValue(SetupCapabilityKind.MOTION).available)
        assertTrue(r.getValue(SetupCapabilityKind.TOUCHPAD).available)
    }

    @Test
    fun `xbox on satellite blocks motion and touchpad on the type axis only`() {
        val r = rows(isPlayStation = false, destinationIsSatellite = true)
        assertTrue(r.getValue(SetupCapabilityKind.RUMBLE).available)

        val motion = r.getValue(SetupCapabilityKind.MOTION)
        assertFalse(motion.available)
        assertTrue(motion.inputOk)
        assertTrue(motion.destinationOk)
        assertFalse(motion.typeOk)

        val touchpad = r.getValue(SetupCapabilityKind.TOUCHPAD)
        assertFalse(touchpad.available)
        assertTrue(touchpad.inputOk)
        assertTrue(touchpad.destinationOk)
        assertFalse(touchpad.typeOk)
    }

    @Test
    fun `playStation over bluetooth host loses motion and touchpad on the destination axis`() {
        // Bluetooth-host can't carry motion or the touchpad, even as a PlayStation type.
        val r = rows(isPlayStation = true, destinationIsSatellite = false, hasDestination = true)
        assertTrue(r.getValue(SetupCapabilityKind.RUMBLE).available)

        val motion = r.getValue(SetupCapabilityKind.MOTION)
        assertFalse(motion.available)
        assertTrue(motion.typeOk)
        assertFalse(motion.destinationOk)

        assertFalse(r.getValue(SetupCapabilityKind.TOUCHPAD).available)
    }

    @Test
    fun `a controller without a gyro never gets motion even on the right path and type`() {
        val r = rows(isPlayStation = true, destinationIsSatellite = true, hasGyro = false)
        val motion = r.getValue(SetupCapabilityKind.MOTION)
        assertFalse(motion.available)
        assertFalse(motion.inputOk)
        // The touchpad doesn't depend on the gyro, so it stays available.
        assertTrue(r.getValue(SetupCapabilityKind.TOUCHPAD).available)
    }

    @Test
    fun `a controller without rumble does not advertise rumble`() {
        val rumble =
            rows(isPlayStation = true, destinationIsSatellite = true, hasRumble = false)
                .getValue(SetupCapabilityKind.RUMBLE)
        assertFalse(rumble.available)
        assertFalse(rumble.inputOk)
    }

    @Test
    fun `with no destination chosen nothing carries`() {
        val r = rows(isPlayStation = true, destinationIsSatellite = false, hasDestination = false)
        assertFalse(r.getValue(SetupCapabilityKind.RUMBLE).available)
        assertFalse(r.getValue(SetupCapabilityKind.MOTION).available)
        assertFalse(r.getValue(SetupCapabilityKind.TOUCHPAD).available)
    }

    @Test
    fun `touchpad input is always satisfied since it is the phone screen`() {
        // Even an input with no gyro and no rumble still supplies the touchpad surface.
        val touchpad =
            rows(
                isPlayStation = true,
                destinationIsSatellite = true,
                hasGyro = false,
                hasRumble = false,
            ).getValue(SetupCapabilityKind.TOUCHPAD)
        assertTrue(touchpad.inputOk)
        assertTrue(touchpad.available)
    }
}
