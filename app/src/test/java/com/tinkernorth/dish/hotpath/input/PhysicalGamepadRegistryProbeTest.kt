// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.hotpath.input

import android.os.Build
import android.view.InputDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The registry's two InputDevice-coupled decisions, lifted so every branch runs on the JVM, where
// Build.VERSION.SDK_INT is 0 and the real callbacks never get past the API gate.
class PhysicalGamepadRegistryProbeTest {
    private val padSources = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK

    private fun sibling(
        id: Int,
        sources: Int = InputDevice.SOURCE_MOUSE,
        isGamepad: Boolean = false,
        vid: Int = VID,
        pid: Int = PID,
    ) = SiblingDevice(id = id, sources = sources, isGamepad = isGamepad, vendorId = vid, productId = pid)

    private fun noSiblings(): List<SiblingDevice> = throw AssertionError("the siblings must not be enumerated")

    private fun resolve(
        sdkInt: Int = POINTER_CAPTURE_SDK,
        sources: Int = padSources,
        vid: Int = VID,
        pid: Int = PID,
        siblings: () -> List<SiblingDevice> = ::noSiblings,
    ) = resolveTouchpadSurface(sdkInt, PAD_ID, sources, vid, pid, siblings)

    @Test
    fun `below 26 there is no surface, and the siblings are never enumerated`() {
        assertNull(resolve(sdkInt = Build.VERSION_CODES.N_MR1, sources = padSources or InputDevice.SOURCE_MOUSE))
    }

    @Test
    fun `a merged pad is its own surface`() {
        assertEquals(PAD_ID, resolve(sources = padSources or InputDevice.SOURCE_MOUSE))
    }

    @Test
    fun `a pad with no identity has no surface`() {
        assertNull(resolve(vid = 0, pid = 0))
    }

    @Test
    fun `a pointer sibling of the same model is the surface`() {
        assertEquals(SURFACE_ID, resolve(siblings = { listOf(sibling(SURFACE_ID)) }))
    }

    @Test
    fun `a sibling that is itself a gamepad is not a surface`() {
        assertNull(resolve(siblings = { listOf(sibling(SURFACE_ID, sources = padSources or InputDevice.SOURCE_MOUSE, isGamepad = true)) }))
    }

    @Test
    fun `a sibling of another model is not a surface`() {
        assertNull(resolve(siblings = { listOf(sibling(SURFACE_ID, pid = OTHER_PID)) }))
    }

    @Test
    fun `a sibling without a pointer source is not a surface`() {
        assertNull(resolve(siblings = { listOf(sibling(SURFACE_ID, sources = InputDevice.SOURCE_KEYBOARD)) }))
    }

    @Test
    fun `the pad's own id is never taken as its sibling`() {
        assertNull(resolve(siblings = { listOf(sibling(PAD_ID)) }))
    }

    @Test
    fun `the first matching sibling wins`() {
        val siblings = listOf(sibling(SURFACE_ID, pid = OTHER_PID), sibling(SURFACE_ID + 1), sibling(SURFACE_ID + 2))
        assertEquals(SURFACE_ID + 1, resolve(siblings = { siblings }))
    }

    // ---- what a re-probe has to find before the card is republished ----

    private fun card(
        name: String = NAME,
        countingDown: Int? = null,
        hasGyro: Boolean = false,
        hasRumble: Boolean = false,
        hasLightbar: Boolean = false,
        surface: Int? = null,
    ) = PhysicalGamepadRegistry.Device(
        id = PAD_ID,
        name = name,
        disconnectingTimeLeftSec = countingDown,
        hasGyro = hasGyro,
        hasRumble = hasRumble,
        hasLightbar = hasLightbar,
        touchpadDeviceId = surface,
    )

    private fun probed(
        hasGyro: Boolean = false,
        hasRumble: Boolean = false,
        hasLightbar: Boolean = false,
        surface: Int? = null,
    ) = ProbedCapabilities(hasGyro = hasGyro, hasRumble = hasRumble, hasLightbar = hasLightbar, touchpadDeviceId = surface)

    @Test
    fun `a pad the registry does not hold yet has changed`() {
        assertTrue(frameworkPadChanged(current = null, name = NAME, probed = probed()))
    }

    @Test
    fun `a pad whose re-probe matches its card has not changed`() {
        assertFalse(frameworkPadChanged(card(), NAME, probed()))
    }

    @Test
    fun `a renamed pad has changed`() {
        assertTrue(frameworkPadChanged(card(), "Another name", probed()))
    }

    @Test
    fun `a pad counting down a disconnect has changed`() {
        assertTrue(frameworkPadChanged(card(countingDown = 3), NAME, probed()))
    }

    @Test
    fun `a gyro that enumerated late has changed`() {
        assertTrue(frameworkPadChanged(card(), NAME, probed(hasGyro = true)))
    }

    @Test
    fun `a rumble motor that enumerated late has changed`() {
        assertTrue(frameworkPadChanged(card(), NAME, probed(hasRumble = true)))
    }

    @Test
    fun `a light bar that enumerated late has changed`() {
        assertTrue(frameworkPadChanged(card(), NAME, probed(hasLightbar = true)))
    }

    @Test
    fun `a touch surface that enumerated late has changed`() {
        assertTrue(frameworkPadChanged(card(), NAME, probed(surface = SURFACE_ID)))
    }

    @Test
    fun `a touch surface that went away has changed`() {
        assertTrue(frameworkPadChanged(card(surface = SURFACE_ID), NAME, probed()))
    }

    private companion object {
        const val PAD_ID = 7
        const val SURFACE_ID = 31
        const val VID = 0x054C
        const val PID = 0x0CE6
        const val OTHER_PID = 0x09CC
        const val NAME = "Wireless Controller"
    }
}
