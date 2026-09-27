// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The overlay toolbar's readout and the link guard's copy.
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
    fun `a lost satellite off wifi blames the network`() {
        val copy = guardCopy(OverlayGuardUi(GuardKind.HOST_LOST, hostLabel = "PC", detail = GuardDetail.WIFI_DOWN))
        assertEquals(R.string.overlay_guard_wifi_detail, copy.detailRes)
        assertNull(copy.detailArg)
        assertEquals(R.string.binding_edge_host_lost_title, copy.titleRes)
        assertEquals(R.drawable.ic_error, copy.iconRes)
        assertEquals(R.color.colorError, copy.colorRes)
    }

    @Test
    fun `a lost host with nothing else to blame is named`() {
        val copy = guardCopy(OverlayGuardUi(GuardKind.HOST_LOST, hostLabel = "PC", detail = GuardDetail.GENERIC))
        assertEquals(R.string.binding_edge_host_lost_detail, copy.detailRes)
        assertEquals("PC", copy.detailArg)
    }

    @Test
    fun `a reconnecting link wears the refresh glyph and the host's own reason`() {
        val bt = guardCopy(OverlayGuardUi(GuardKind.RECONNECTING, hostLabel = "PC", detail = GuardDetail.BLUETOOTH_HOST))
        assertEquals(R.drawable.ic_refresh, bt.iconRes)
        assertEquals(R.color.colorPrimary, bt.colorRes)
        assertEquals(R.string.chip_status_connecting, bt.titleRes)
        assertEquals(R.string.overlay_guard_bt_detail, bt.detailRes)
        val ml = guardCopy(OverlayGuardUi(GuardKind.RECONNECTING, hostLabel = "PC", detail = GuardDetail.MOONLIGHT_SESSION))
        assertEquals(R.string.overlay_guard_ml_detail, ml.detailRes)
    }

    @Test
    fun `an unplugged pad asks for a replug and a departed one says goodbye`() {
        val unplugged = guardCopy(OverlayGuardUi(GuardKind.UNPLUGGED, hostLabel = "PC"))
        assertEquals(R.string.binding_edge_input_lost_title, unplugged.titleRes)
        assertEquals(R.string.overlay_guard_replug_detail, unplugged.detailRes)
        assertEquals(R.drawable.ic_gamepad, unplugged.iconRes)
        assertEquals(R.color.colorWarning, unplugged.colorRes)
        val departed = guardCopy(OverlayGuardUi(GuardKind.DEPARTED, hostLabel = "PC", autoClose = true))
        assertEquals(R.string.overlay_guard_departed_detail, departed.detailRes)
        assertNull(departed.detailArg)
    }

    @Test
    fun `an unbound slot names the host it left`() {
        val copy = guardCopy(OverlayGuardUi(GuardKind.UNBOUND, hostLabel = "PC", autoClose = true))
        assertEquals(R.drawable.ic_link_off, copy.iconRes)
        assertEquals(R.string.overlay_guard_unbound_title, copy.titleRes)
        assertEquals(R.string.overlay_guard_unbound_detail, copy.detailRes)
        assertEquals("PC", copy.detailArg)
    }

    @Test
    fun `a gone connection is the error card`() {
        val copy = guardCopy(OverlayGuardUi(GuardKind.GONE, autoClose = true))
        assertEquals(R.drawable.ic_error, copy.iconRes)
        assertEquals(R.string.overlay_guard_gone_title, copy.titleRes)
        assertEquals(R.string.overlay_guard_gone_detail, copy.detailRes)
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
