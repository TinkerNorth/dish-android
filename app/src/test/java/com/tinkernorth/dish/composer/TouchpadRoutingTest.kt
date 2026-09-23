// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_DS4
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_MOUSE
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_OFF
import org.junit.Assert.assertEquals
import org.junit.Test

class TouchpadRoutingTest {
    // ── sourceFor: who produces the slot's touch data ────────────────────────

    @Test
    fun `the virtual slot is always phone-sourced`() {
        assertEquals(
            TouchpadSource.PHONE,
            sourceFor(isVirtual = true, padHasTouchpad = false, padCaptured = false),
        )
        // isVirtual wins even over nonsensical pad flags: there is no pad behind the slot.
        assertEquals(
            TouchpadSource.PHONE,
            sourceFor(isVirtual = true, padHasTouchpad = true, padCaptured = true),
        )
    }

    @Test
    fun `a trackpad-less pad falls back to the phone screen`() {
        assertEquals(
            TouchpadSource.PHONE,
            sourceFor(isVirtual = false, padHasTouchpad = false, padCaptured = false),
        )
        assertEquals(
            TouchpadSource.PHONE,
            sourceFor(isVirtual = false, padHasTouchpad = false, padCaptured = true),
        )
    }

    @Test
    fun `a captured trackpad-bearing pad sources its own touch`() {
        assertEquals(
            TouchpadSource.PAD,
            sourceFor(isVirtual = false, padHasTouchpad = true, padCaptured = true),
        )
    }

    @Test
    fun `a trackpad-bearing pad on an uncapturable path gets neither producer`() {
        assertEquals(
            TouchpadSource.NONE,
            sourceFor(isVirtual = false, padHasTouchpad = true, padCaptured = false),
        )
    }

    // ── wireMode: the descriptor's derived touchpadMode for one slot ─────────

    private val none = CapabilitySet.EMPTY
    private val touch = CapabilitySet.of(Feature.TOUCHPAD)
    private val mouse = CapabilitySet.of(Feature.MOUSE)
    private val both = CapabilitySet.of(Feature.TOUCHPAD, Feature.MOUSE)

    @Test
    fun `the pad surface wins whenever the type carries one`() {
        assertEquals(TOUCHPAD_MODE_DS4, wireMode(false, touch, touch, none))
        assertEquals(TOUCHPAD_MODE_DS4, wireMode(false, both, both, both))
    }

    @Test
    fun `a pad-less route falls through to the host mouse`() {
        assertEquals(TOUCHPAD_MODE_MOUSE, wireMode(false, mouse, none, mouse))
        assertEquals(TOUCHPAD_MODE_MOUSE, wireMode(false, both, none, both))
    }

    @Test
    fun `ds4 needs a touch source and a type that advertises the mode`() {
        assertEquals(TOUCHPAD_MODE_OFF, wireMode(false, none, touch, none))
        assertEquals(TOUCHPAD_MODE_OFF, wireMode(false, touch, none, none))
    }

    @Test
    fun `mouse needs a touch source and a host that grants mouse control`() {
        assertEquals(TOUCHPAD_MODE_OFF, wireMode(false, none, none, mouse))
        assertEquals(TOUCHPAD_MODE_OFF, wireMode(false, mouse, none, none))
    }

    @Test
    fun `a route that can carry neither declares off`() {
        assertEquals(TOUCHPAD_MODE_OFF, wireMode(false, none, none, none))
        assertEquals(TOUCHPAD_MODE_OFF, wireMode(true, none, none, none))
    }

    @Test
    fun `an open mouse surface flips a pad-routable slot to mouse`() {
        assertEquals(TOUCHPAD_MODE_MOUSE, wireMode(true, both, both, both))
        assertEquals(TOUCHPAD_MODE_MOUSE, wireMode(true, both, none, both))
    }

    @Test
    fun `an open mouse surface without a mouse route keeps the pad routing`() {
        assertEquals(TOUCHPAD_MODE_DS4, wireMode(true, touch, touch, none))
        assertEquals(TOUCHPAD_MODE_DS4, wireMode(true, both, both, none))
    }
}
