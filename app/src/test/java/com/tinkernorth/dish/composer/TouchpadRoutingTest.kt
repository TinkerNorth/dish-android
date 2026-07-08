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

    // ── wireMode: the descriptor's touchpadMode for one slot ─────────────────

    private val none = CapabilitySet.EMPTY
    private val touch = CapabilitySet.of(Feature.TOUCHPAD)
    private val mouse = CapabilitySet.of(Feature.MOUSE)
    private val both = CapabilitySet.of(Feature.TOUCHPAD, Feature.MOUSE)

    @Test
    fun `no pick declares off`() {
        assertEquals(TouchpadModeValue.OFF, TouchpadRouting.wireMode(null, both, both, both))
    }

    @Test
    fun `an unknown pick declares off`() {
        assertEquals(TouchpadModeValue.OFF, TouchpadRouting.wireMode("warp-drive", both, both, both))
    }

    @Test
    fun `ds4 needs a touch source and a type that advertises the mode`() {
        assertEquals(TouchpadModeValue.DS4, TouchpadRouting.wireMode(TouchpadModeValue.DS4, touch, touch, none))
        // No touch source (trackpad-bearing pad on a framework path): off.
        assertEquals(TouchpadModeValue.OFF, TouchpadRouting.wireMode(TouchpadModeValue.DS4, none, touch, both))
        // Type without the ds4 mode (e.g. xbox360): off.
        assertEquals(TouchpadModeValue.OFF, TouchpadRouting.wireMode(TouchpadModeValue.DS4, touch, none, both))
        // The host layer never gates the pad routing (it is a per-type concern).
        assertEquals(TouchpadModeValue.DS4, TouchpadRouting.wireMode(TouchpadModeValue.DS4, both, both, none))
    }

    @Test
    fun `mouse needs a touch source and a host that grants mouse control`() {
        assertEquals(TouchpadModeValue.MOUSE, TouchpadRouting.wireMode(TouchpadModeValue.MOUSE, mouse, none, mouse))
        // Host without mouseControl: declaring the want would leave wants != granted forever.
        assertEquals(TouchpadModeValue.OFF, TouchpadRouting.wireMode(TouchpadModeValue.MOUSE, both, both, none))
        // No touch source: off.
        assertEquals(TouchpadModeValue.OFF, TouchpadRouting.wireMode(TouchpadModeValue.MOUSE, none, both, both))
        // The type layer never gates mouse (it is host-injected).
        assertEquals(TouchpadModeValue.MOUSE, TouchpadRouting.wireMode(TouchpadModeValue.MOUSE, both, none, both))
    }

    @Test
    fun `a pick never resolves to the other routing`() {
        // ds4 blocked by its gates must not fall back to mouse even when mouse could carry.
        assertEquals(TouchpadModeValue.OFF, TouchpadRouting.wireMode(TouchpadModeValue.DS4, mouse, none, mouse))
        assertEquals(TouchpadModeValue.OFF, TouchpadRouting.wireMode(TouchpadModeValue.MOUSE, touch, touch, none))
    }
}
