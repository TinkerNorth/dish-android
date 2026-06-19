// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityTest {
    @Test
    fun `contains checks membership`() {
        val set = CapabilitySet.of(Feature.GAMEPAD, Feature.MOTION)
        assertTrue(Feature.MOTION in set)
        assertFalse(Feature.RUMBLE in set)
    }

    @Test
    fun `intersect keeps only shared features`() {
        val a = CapabilitySet.of(Feature.GAMEPAD, Feature.MOTION, Feature.RUMBLE)
        val b = CapabilitySet.of(Feature.MOTION, Feature.RUMBLE, Feature.TOUCHPAD)
        assertEquals(CapabilitySet.of(Feature.MOTION, Feature.RUMBLE), a intersect b)
    }

    @Test
    fun `minus removes the right-hand features`() {
        val a = CapabilitySet.of(Feature.GAMEPAD, Feature.MOTION, Feature.RUMBLE)
        val b = CapabilitySet.of(Feature.MOTION)
        assertEquals(CapabilitySet.of(Feature.GAMEPAD, Feature.RUMBLE), a - b)
    }

    @Test
    fun `EMPTY contains nothing`() {
        assertFalse(Feature.GAMEPAD in CapabilitySet.EMPTY)
        assertTrue(CapabilitySet.EMPTY.features.isEmpty())
    }

    @Test
    fun `toCapabilitySet carries the satellite baseline and gates optional features`() {
        val baseline =
            HostFeatureSet(
                hasCatalog = true,
                mouseControl = false,
                keyboardControl = false,
                rumbleReturn = true,
                touchpadModes = emptySet(),
            ).toCapabilitySet()
        assertTrue(Feature.GAMEPAD in baseline)
        assertTrue(Feature.ANALOG_TRIGGERS in baseline)
        assertTrue(Feature.MOTION in baseline)
        assertTrue(Feature.TOUCHPAD in baseline)
        assertTrue(Feature.LIGHTBAR in baseline)
        assertTrue(Feature.RUMBLE in baseline)
        assertFalse(Feature.MOUSE in baseline)
        assertFalse(Feature.KEYBOARD in baseline)
    }

    @Test
    fun `toCapabilitySet adds MOUSE when mouseControl is granted`() {
        val withMouse =
            HostFeatureSet(
                hasCatalog = true,
                mouseControl = true,
                keyboardControl = false,
                rumbleReturn = true,
                touchpadModes = emptySet(),
            ).toCapabilitySet()
        assertTrue(Feature.MOUSE in withMouse)
    }

    @Test
    fun `toCapabilitySet drops RUMBLE when the host has no return channel`() {
        val noRumble =
            HostFeatureSet(
                hasCatalog = true,
                mouseControl = false,
                keyboardControl = false,
                rumbleReturn = false,
                touchpadModes = emptySet(),
            ).toCapabilitySet()
        assertFalse(Feature.RUMBLE in noRumble)
    }

    @Test
    fun `SATELLITE_DEFAULT is the optimistic not-yet-fetched baseline`() {
        val default = HostFeatureSet.SATELLITE_DEFAULT
        assertFalse(default.hasCatalog)
        // A satellite has always accepted mouse control and returned rumble, so both are
        // assumed until a fetched catalog refines them.
        assertTrue(default.mouseControl)
        assertTrue(default.rumbleReturn)
        val caps = default.toCapabilitySet()
        assertTrue(Feature.MOTION in caps)
        assertTrue(Feature.RUMBLE in caps)
        assertTrue(Feature.MOUSE in caps)
    }

    @Test
    fun `fromCatalog parses mouseControl supported and modes`() {
        val catalog =
            CatalogDto(
                hostFeatures =
                    mapOf(
                        "mouseControl" to CatalogHostFeatureDto(supported = true, modes = listOf("absolute", "relative")),
                    ),
            )
        val features = HostFeatureSet.fromCatalog(catalog)
        assertTrue(features.hasCatalog)
        assertTrue(features.mouseControl)
        assertEquals(setOf("absolute", "relative"), features.touchpadModes)
        assertTrue(Feature.MOUSE in features.toCapabilitySet())
    }

    @Test
    fun `fromCatalog treats a missing mouseControl as unsupported`() {
        val features = HostFeatureSet.fromCatalog(CatalogDto())
        assertTrue(features.hasCatalog)
        assertFalse(features.mouseControl)
        assertFalse(Feature.MOUSE in features.toCapabilitySet())
    }
}
