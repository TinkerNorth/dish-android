// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.repository.TouchpadModeValue
import org.junit.Assert.assertEquals
import org.junit.Test

class TouchpadRoutingTest {
    // ── sourceFor: who produces the slot's touch data ────────────────────────

    @Test
    fun `the virtual slot is always phone-sourced`() {
        assertEquals(
            TouchpadSource.PHONE,
            TouchpadRouting.sourceFor(isVirtual = true, padHasTouchpad = false, padCaptured = false),
        )
        // isVirtual wins even over nonsensical pad flags: there is no pad behind the slot.
        assertEquals(
            TouchpadSource.PHONE,
            TouchpadRouting.sourceFor(isVirtual = true, padHasTouchpad = true, padCaptured = true),
        )
    }

    @Test
    fun `a trackpad-less pad falls back to the phone screen`() {
        assertEquals(
            TouchpadSource.PHONE,
            TouchpadRouting.sourceFor(isVirtual = false, padHasTouchpad = false, padCaptured = false),
        )
        assertEquals(
            TouchpadSource.PHONE,
            TouchpadRouting.sourceFor(isVirtual = false, padHasTouchpad = false, padCaptured = true),
        )
    }

    @Test
    fun `a captured trackpad-bearing pad sources its own touch`() {
        assertEquals(
            TouchpadSource.PAD,
            TouchpadRouting.sourceFor(isVirtual = false, padHasTouchpad = true, padCaptured = true),
        )
    }

    @Test
    fun `a trackpad-bearing pad on an uncapturable path gets neither producer`() {
        assertEquals(
            TouchpadSource.NONE,
            TouchpadRouting.sourceFor(isVirtual = false, padHasTouchpad = true, padCaptured = false),
        )
    }

    // ── wireMode: the descriptor's derived touchpadMode for one slot ─────────

    private val none = CapabilitySet.EMPTY
    private val touch = CapabilitySet.of(Feature.TOUCHPAD)
    private val mouse = CapabilitySet.of(Feature.MOUSE)
    private val both = CapabilitySet.of(Feature.TOUCHPAD, Feature.MOUSE)

    @Test
    fun `the pad surface wins whenever the type carries one`() {
        assertEquals(TouchpadModeValue.DS4, TouchpadRouting.wireMode(false, touch, touch, none))
        assertEquals(TouchpadModeValue.DS4, TouchpadRouting.wireMode(false, both, both, both))
    }

    @Test
    fun `a pad-less route falls through to the host mouse`() {
        assertEquals(TouchpadModeValue.MOUSE, TouchpadRouting.wireMode(false, mouse, none, mouse))
        assertEquals(TouchpadModeValue.MOUSE, TouchpadRouting.wireMode(false, both, none, both))
    }

    @Test
    fun `ds4 needs a touch source and a type that advertises the mode`() {
        assertEquals(TouchpadModeValue.OFF, TouchpadRouting.wireMode(false, none, touch, none))
        assertEquals(TouchpadModeValue.OFF, TouchpadRouting.wireMode(false, touch, none, none))
    }

    @Test
    fun `mouse needs a touch source and a host that grants mouse control`() {
        assertEquals(TouchpadModeValue.OFF, TouchpadRouting.wireMode(false, none, none, mouse))
        assertEquals(TouchpadModeValue.OFF, TouchpadRouting.wireMode(false, mouse, none, none))
    }

    @Test
    fun `a route that can carry neither declares off`() {
        assertEquals(TouchpadModeValue.OFF, TouchpadRouting.wireMode(false, none, none, none))
        assertEquals(TouchpadModeValue.OFF, TouchpadRouting.wireMode(true, none, none, none))
    }

    @Test
    fun `an open mouse surface flips a pad-routable slot to mouse`() {
        assertEquals(TouchpadModeValue.MOUSE, TouchpadRouting.wireMode(true, both, both, both))
        assertEquals(TouchpadModeValue.MOUSE, TouchpadRouting.wireMode(true, both, none, both))
    }

    @Test
    fun `an open mouse surface without a mouse route keeps the pad routing`() {
        assertEquals(TouchpadModeValue.DS4, TouchpadRouting.wireMode(true, touch, touch, none))
        assertEquals(TouchpadModeValue.DS4, TouchpadRouting.wireMode(true, both, both, none))
    }
}
