// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.model.capabilitySetOf
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_DS4
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_OFF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The on-screen pad's gates: when the phone IMU spins, when the trackpad zone shows, and
// when a held mute button counts.
class GamepadOverlayPolicyTest {
    private var wireModeReads = 0

    private fun countedWireMode(): String {
        wireModeReads++
        return TOUCHPAD_MODE_DS4
    }

    private fun summary(
        kind: ConnectionKind,
        live: LinkState = LinkState.Connected,
    ) = ConnectionSummary(id = "host", kind = kind, label = "PC", detail = "", live = live, boundSlotIds = emptyList())

    private fun caps(
        input: Boolean = true,
        userWants: Boolean = true,
    ): SlotCapabilities {
        fun motion(present: Boolean) = if (present) capabilitySetOf(Feature.MOTION) else CapabilitySet.EMPTY
        return SlotCapabilities(
            controller = motion(input),
            transport = motion(true),
            type = motion(true),
            host = motion(true),
            userEnabled = motion(userWants),
            runtimeDown = CapabilitySet.EMPTY,
        )
    }

    @Test
    fun `the imu spins for a live satellite the user wants motion on`() {
        assertTrue(motionGateOpen(caps(), summary(ConnectionKind.SATELLITE)))
        assertTrue(motionGateOpen(caps(), summary(ConnectionKind.SATELLITE, LinkState.Unstable)))
    }

    @Test
    fun `the imu spins for a live moonlight host too`() {
        assertTrue(motionGateOpen(caps(), summary(ConnectionKind.MOONLIGHT)))
    }

    @Test
    fun `a bluetooth bound slot never spins the phone imu`() {
        assertFalse(motionGateOpen(caps(), summary(ConnectionKind.BLUETOOTH)))
    }

    @Test
    fun `a dead link or no summary keeps the imu off`() {
        assertFalse(motionGateOpen(caps(), summary(ConnectionKind.SATELLITE, LinkState.Connecting)))
        assertFalse(motionGateOpen(caps(), summary(ConnectionKind.SATELLITE, LinkState.Saved)))
        assertFalse(motionGateOpen(caps(), null))
    }

    @Test
    fun `the input and the user both have to allow motion`() {
        assertFalse(motionGateOpen(caps(input = false), summary(ConnectionKind.SATELLITE)))
        assertFalse(motionGateOpen(caps(userWants = false), summary(ConnectionKind.SATELLITE)))
    }

    @Test
    fun `a type without a trackpad shows none`() {
        assertFalse(trackpadShownFor(ConnectionKind.SATELLITE, typeHasTouchpad = false) { TOUCHPAD_MODE_DS4 })
        assertFalse(trackpadShownFor(ConnectionKind.MOONLIGHT, typeHasTouchpad = false) { TOUCHPAD_MODE_DS4 })
    }

    @Test
    fun `a satellite streams touch once the descriptor declares a mode`() {
        assertTrue(trackpadShownFor(ConnectionKind.SATELLITE, typeHasTouchpad = true) { TOUCHPAD_MODE_DS4 })
    }

    @Test
    fun `a satellite with touchpad mode off hides the trackpad`() {
        assertFalse(trackpadShownFor(ConnectionKind.SATELLITE, typeHasTouchpad = true) { TOUCHPAD_MODE_OFF })
    }

    @Test
    fun `a moonlight host always gets the touch stream`() {
        assertTrue(trackpadShownFor(ConnectionKind.MOONLIGHT, typeHasTouchpad = true) { TOUCHPAD_MODE_OFF })
    }

    @Test
    fun `bluetooth and an unresolved connection hide the trackpad`() {
        assertFalse(trackpadShownFor(ConnectionKind.BLUETOOTH, typeHasTouchpad = true) { TOUCHPAD_MODE_DS4 })
        assertFalse(trackpadShownFor(null, typeHasTouchpad = true) { TOUCHPAD_MODE_DS4 })
    }

    @Test
    fun `a type without a trackpad never derives the wire mode`() {
        trackpadShownFor(ConnectionKind.SATELLITE, typeHasTouchpad = false) { countedWireMode() }
        assertEquals(0, wireModeReads)
    }

    @Test
    fun `a satellite with a trackpad derives the wire mode once`() {
        trackpadShownFor(ConnectionKind.SATELLITE, typeHasTouchpad = true) { countedWireMode() }
        assertEquals(1, wireModeReads)
    }

    @Test
    fun `a moonlight host never derives the wire mode`() {
        trackpadShownFor(ConnectionKind.MOONLIGHT, typeHasTouchpad = true) { countedWireMode() }
        assertEquals(0, wireModeReads)
    }

    @Test
    fun `bluetooth and an unresolved connection never derive the wire mode`() {
        trackpadShownFor(ConnectionKind.BLUETOOTH, typeHasTouchpad = true) { countedWireMode() }
        trackpadShownFor(null, typeHasTouchpad = true) { countedWireMode() }
        assertEquals(0, wireModeReads)
    }

    @Test
    fun `a held mute press toggles only once`() {
        val frames = listOf(false, true, true, true, false, false)
        val edges = frames.zipWithNext { wasDown, isDown -> pressEdge(wasDown, isDown) }
        assertEquals(listOf(PressEdge.DOWN, PressEdge.NONE, PressEdge.NONE, PressEdge.UP, PressEdge.NONE), edges)
        assertEquals(1, edges.count { it == PressEdge.DOWN })
    }
}
