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
        hasGyro: Boolean = true,
    ) = SetupCapability
        .rows(isPlayStation, destinationIsSatellite, hasGyro)
        .associateBy { it.kind }

    @Test
    fun `rows are returned in rumble, motion, touchpad order`() {
        val kinds = SetupCapability.rows(true, true, true).map { it.kind }
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
    fun `playStation over bluetooth host loses everything on the destination axis`() {
        // A Bluetooth host has no channel back to the phone, so it carries neither
        // rumble nor motion nor the touchpad, even as a PlayStation type.
        val r = rows(isPlayStation = true, destinationIsSatellite = false)
        assertFalse(r.getValue(SetupCapabilityKind.RUMBLE).available)

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
    fun `rumble rides a satellite regardless of input motor, but not a bluetooth host`() {
        // The phone vibrates as a universal fallback, so rumble is input-available
        // regardless of the controller's own motor; it just needs a Satellite link,
        // since a Bluetooth host has no path back to the phone.
        val onSatellite =
            rows(isPlayStation = false, destinationIsSatellite = true)
                .getValue(SetupCapabilityKind.RUMBLE)
        assertTrue(onSatellite.inputOk)
        assertTrue(onSatellite.available)

        val onBtHost =
            rows(isPlayStation = false, destinationIsSatellite = false)
                .getValue(SetupCapabilityKind.RUMBLE)
        assertFalse(onBtHost.available)
        assertFalse(onBtHost.destinationOk)
    }

    @Test
    fun `without a satellite destination nothing carries`() {
        val r = rows(isPlayStation = true, destinationIsSatellite = false)
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
            ).getValue(SetupCapabilityKind.TOUCHPAD)
        assertTrue(touchpad.inputOk)
        assertTrue(touchpad.available)
    }
}
