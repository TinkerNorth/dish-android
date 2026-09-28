// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The overlay toolbar's readout and when the link guard's scrim waits out a blip.
class RateReadoutTest {
    @Test
    fun `a motionless overlay shows only the touch rate`() {
        val readout = rateReadout(screenPeakHz = 120, gyroHz = 200, hasMotion = false, motionOn = true)
        assertEquals(RateReading.PeakHz(120), readout.touch)
        assertNull(readout.motion)
    }

    @Test
    fun `the touch rate is pending until the first sample`() {
        assertEquals(RateReading.Pending, rateReadout(0, 0, hasMotion = false, motionOn = false).touch)
    }

    @Test
    fun `the motion line reads off while motion is user-facing off`() {
        val readout = rateReadout(screenPeakHz = 0, gyroHz = 200, hasMotion = true, motionOn = false)
        assertEquals(RateReading.Off, readout.motion)
    }

    @Test
    fun `the motion line reads the live rate, or pending before one arrives`() {
        assertEquals(RateReading.LiveHz(200), rateReadout(0, 200, hasMotion = true, motionOn = true).motion)
        assertEquals(RateReading.Pending, rateReadout(0, 0, hasMotion = true, motionOn = true).motion)
    }

    @Test
    fun `a single sample is already a rate`() {
        val readout = rateReadout(screenPeakHz = 1, gyroHz = 1, hasMotion = true, motionOn = true)
        assertEquals(RateReading.PeakHz(1), readout.touch)
        assertEquals(RateReading.LiveHz(1), readout.motion)
    }

    @Test
    fun `only a link blip waits out the grace before the scrim`() {
        assertTrue(guardWaitsOutBlip(GuardKind.RECONNECTING))
        assertTrue(guardWaitsOutBlip(GuardKind.HOST_LOST))
        assertFalse(guardWaitsOutBlip(GuardKind.NONE))
        assertFalse(guardWaitsOutBlip(GuardKind.UNPLUGGED))
        assertFalse(guardWaitsOutBlip(GuardKind.DEPARTED))
        assertFalse(guardWaitsOutBlip(GuardKind.UNBOUND))
        assertFalse(guardWaitsOutBlip(GuardKind.GONE))
    }
}
