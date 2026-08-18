// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDualPresenceGhostTest {
    private fun lunaOnPlugIn(
        frameworkId: Int? = null,
        hasPermission: Boolean = false,
        desired: PathChoice = PathChoice.Standard,
    ) = UsbController(
        vendorId = 0x1949,
        productId = 0x041A,
        name = "Amazon Luna Controller",
        phase = UsbPhase.Routed,
        usbPresent = true,
        frameworkId = frameworkId,
        hasPermission = hasPermission,
        desired = desired,
    )

    @Test
    fun `plugging in while bluetooth holds input parks the pad as a silent routed ghost`() {
        val r =
            reduce(
                lunaOnPlugIn(frameworkId = null, hasPermission = false),
                UsbEvent.Choose(PathChoice.Direct, userInitiated = false),
            )
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertEquals(PathChoice.Direct, r.next?.desired)
        assertNull(r.next?.frameworkId)
        assertNull(r.next?.syntheticId)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun `the ghost never asks for permission on its own`() {
        val r =
            reduce(
                lunaOnPlugIn(frameworkId = null, hasPermission = false),
                UsbEvent.Choose(PathChoice.Direct, userInitiated = false),
            )
        assertTrue(UsbEffect.RequestPermission !in r.effects)
        assertTrue(UsbEffect.Claim !in r.effects)
    }

    @Test
    fun `an unverified ghost parks on standard with nothing to wait for`() {
        val r =
            reduce(
                lunaOnPlugIn(frameworkId = null, hasPermission = false),
                UsbEvent.Choose(PathChoice.Standard, userInitiated = false),
            )
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertEquals(PathChoice.Standard, r.next?.desired)
        assertNull(r.next?.frameworkId)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun `a same-id bluetooth twin reads as framework presence and still claims nothing`() {
        val r = reduce(lunaOnPlugIn(frameworkId = null, desired = PathChoice.Direct), UsbEvent.FrameworkUp(42))
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertEquals(42, r.next?.frameworkId)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun `the ghost holds its direct intent without ever acting on it`() {
        var c = lunaOnPlugIn(frameworkId = null, hasPermission = false)
        c = reduce(c, UsbEvent.Choose(PathChoice.Direct, userInitiated = false)).next!!
        val again = reduce(c, UsbEvent.Choose(PathChoice.Direct, userInitiated = false))
        assertEquals(UsbPhase.Routed, again.next?.phase)
        assertEquals(PathChoice.Direct, again.next?.desired)
        assertTrue(again.effects.isEmpty())
    }

    @Test
    fun `only a user-initiated pick makes the ghost request permission`() {
        val r =
            reduce(
                lunaOnPlugIn(frameworkId = null, hasPermission = false, desired = PathChoice.Direct),
                UsbEvent.Choose(PathChoice.Direct, userInitiated = true),
            )
        assertEquals(UsbPhase.Routed, r.next?.phase)
        assertEquals(listOf<UsbEffect>(UsbEffect.RequestPermission), r.effects)
    }

    @Test
    fun `granting permission is what finally claims the ghost`() {
        val r =
            reduce(
                lunaOnPlugIn(frameworkId = null, hasPermission = false, desired = PathChoice.Direct),
                UsbEvent.PermissionGranted,
            )
        assertEquals(UsbPhase.Claiming, r.next?.phase)
        assertEquals(listOf(UsbEffect.ClearFailure, UsbEffect.BeginHold, UsbEffect.Claim), r.effects)
    }

    @Test
    fun `a user pick with prior permission claims without a prompt`() {
        val r =
            reduce(
                lunaOnPlugIn(frameworkId = null, hasPermission = true),
                UsbEvent.Choose(PathChoice.Direct, userInitiated = true),
            )
        assertEquals(UsbPhase.Claiming, r.next?.phase)
        assertEquals(listOf(UsbEffect.ClearFailure, UsbEffect.BeginHold, UsbEffect.Claim), r.effects)
    }

    @Test
    fun `unplugging the ghost forgets it without side effects`() {
        val r = reduce(lunaOnPlugIn(frameworkId = null, desired = PathChoice.Direct), UsbEvent.UsbUnplugged)
        assertNull(r.next)
        assertTrue(r.effects.isEmpty())
    }
}
