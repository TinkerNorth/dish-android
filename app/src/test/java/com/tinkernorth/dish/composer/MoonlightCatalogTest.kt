// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.net.moonlight.BTN_TOUCHPAD
import com.tinkernorth.dish.core.net.moonlight.CAP_ACCELEROMETER
import com.tinkernorth.dish.core.net.moonlight.CAP_ANALOG_TRIGGERS
import com.tinkernorth.dish.core.net.moonlight.CAP_BATTERY
import com.tinkernorth.dish.core.net.moonlight.CAP_GYRO
import com.tinkernorth.dish.core.net.moonlight.CAP_RUMBLE
import com.tinkernorth.dish.core.net.moonlight.CAP_TOUCHPAD
import com.tinkernorth.dish.core.net.moonlight.CAP_TRIGGER_RUMBLE
import com.tinkernorth.dish.core.net.moonlight.CONTROLLER_ARRIVAL_LEN
import com.tinkernorth.dish.core.net.moonlight.INPUT_CONTROLLER_ARRIVAL
import com.tinkernorth.dish.core.net.moonlight.NINTENDO
import com.tinkernorth.dish.core.net.moonlight.PLAYSTATION
import com.tinkernorth.dish.core.net.moonlight.XBOX
import com.tinkernorth.dish.core.net.moonlight.controllerArrival
import com.tinkernorth.dish.core.net.moonlight.supportedButtons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

// The hard-coded capability table. No Moonlight host reports what its emulated pads
// can do, so this is client-side knowledge derived from what the reference host
// actually builds per type, and the type cards render straight off it.
class MoonlightCatalogTest {
    private val everything =
        CapabilitySet.of(
            Feature.GAMEPAD,
            Feature.ANALOG_TRIGGERS,
            Feature.MOTION,
            Feature.TOUCHPAD,
            Feature.BATTERY,
            Feature.RUMBLE,
            Feature.TRIGGER_RUMBLE,
            Feature.LIGHTBAR,
        )

    private val baseBits =
        CAP_ANALOG_TRIGGERS or
            CAP_RUMBLE or
            CAP_TRIGGER_RUMBLE or
            CAP_BATTERY

    @Test
    fun `PlayStation is the only type with motion, touchpad and a lightbar`() {
        val ps = moonlightTypeCapabilities(PLAYSTATION)
        assertTrue(Feature.RUMBLE in ps)
        assertTrue(Feature.MOTION in ps)
        assertTrue(Feature.TOUCHPAD in ps)
        assertTrue(Feature.LIGHTBAR in ps)
    }

    @Test
    fun `Xbox carries rumble and nothing else beyond a pad`() {
        val xbox = moonlightTypeCapabilities(XBOX)
        assertTrue(Feature.GAMEPAD in xbox)
        assertTrue(Feature.ANALOG_TRIGGERS in xbox)
        assertTrue(Feature.RUMBLE in xbox)
        assertFalse(Feature.MOTION in xbox)
        assertFalse(Feature.TOUCHPAD in xbox)
    }

    // Not a copy of the satellite switchpro row: the reference host only routes motion
    // into a PlayStation pad, so a Nintendo type over Moonlight has no gyro at all.
    @Test
    fun `Nintendo has no motion over Moonlight, unlike the satellite switchpro type`() {
        val nintendo = moonlightTypeCapabilities(NINTENDO)
        assertTrue(Feature.RUMBLE in nintendo)
        assertFalse(Feature.MOTION in nintendo)
        assertFalse(Feature.TOUCHPAD in nintendo)
        assertEquals(
            moonlightTypeCapabilities(XBOX),
            nintendo,
        )
    }

    @Test
    fun `the host layer crosses nothing out, because no host reports its capabilities`() {
        listOf(
            Feature.GAMEPAD,
            Feature.ANALOG_TRIGGERS,
            Feature.MOTION,
            Feature.TOUCHPAD,
            Feature.MOUSE,
            Feature.RUMBLE,
            Feature.LIGHTBAR,
        ).forEach { assertTrue(it.name, it in HOST_LAYER) }
    }

    @Test
    fun `mouse rides the control stream on every type, keyboard stays out until implemented`() {
        listOf(XBOX, PLAYSTATION, NINTENDO)
            .forEach { type -> assertTrue(Feature.MOUSE in moonlightTypeCapabilities(type)) }
        assertFalse(Feature.KEYBOARD in HOST_LAYER)
    }

    @Test
    fun `source bits claim a battery only when the source reports one`() {
        assertEquals(
            CAP_BATTERY,
            sourceBits(everything) and CAP_BATTERY,
        )
        val noBattery = everything - CapabilitySet.of(Feature.BATTERY)
        assertEquals(0, sourceBits(noBattery) and CAP_BATTERY)
    }

    @Test
    fun `a fully capable source declares the base bits for Xbox and Nintendo and 0xFF for PlayStation`() {
        assertEquals(baseBits, capabilityBits(XBOX, everything))
        assertEquals(baseBits, capabilityBits(NINTENDO, everything))
        assertEquals(0xFF, capabilityBits(PLAYSTATION, everything))
    }

    @Test
    fun `trigger rumble and battery ride only when the source really has them`() {
        val noExtras =
            CapabilitySet.of(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.RUMBLE)
        val bits = capabilityBits(XBOX, noExtras)
        // Rumble no longer drags CAP_TRIGGER_RUMBLE along: a pad without the
        // trigger motors must not invite RUMBLE_TRIGGERS events it would eat.
        assertEquals(0, bits and CAP_TRIGGER_RUMBLE)
        assertEquals(0, bits and CAP_BATTERY)
        val withExtras =
            CapabilitySet.of(
                Feature.GAMEPAD,
                Feature.ANALOG_TRIGGERS,
                Feature.RUMBLE,
                Feature.TRIGGER_RUMBLE,
                Feature.BATTERY,
            )
        val bits2 = capabilityBits(XBOX, withExtras)
        assertEquals(CAP_TRIGGER_RUMBLE, bits2 and CAP_TRIGGER_RUMBLE)
        assertEquals(CAP_BATTERY, bits2 and CAP_BATTERY)
    }

    @Test
    fun `a source without motion does not let a PlayStation type ask for gyro reports`() {
        val noMotion = CapabilitySet.of(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.RUMBLE)
        val bits = capabilityBits(PLAYSTATION, noMotion)
        assertEquals(0, bits and CAP_GYRO)
        assertEquals(0, bits and CAP_ACCELEROMETER)
        assertEquals(0, bits and CAP_TOUCHPAD)
    }

    // The whole chain, byte for byte: catalog -> declared bits -> the packet the host reads
    // out of its naturally aligned struct. A live Sunshine host logs these as
    // `capabilities [0003] supportedButtonFlags [0000FFFF]` for the Xbox case.
    @Test
    fun `each type produces its own byte-exact CONTROLLER_ARRIVAL`() {
        assertArrival(XBOX, expectedCaps = 0x47, expectedButtons = 0xFFFF)
        assertArrival(NINTENDO, expectedCaps = 0x47, expectedButtons = 0xFFFF)
        assertArrival(
            PLAYSTATION,
            expectedCaps = 0xFF,
            expectedButtons = 0xFFFF or BTN_TOUCHPAD,
        )
    }

    private fun assertArrival(
        type: Int,
        expectedCaps: Int,
        expectedButtons: Int,
    ) {
        val caps = capabilityBits(type, everything)
        val buttons = supportedButtons(caps)
        assertEquals("capabilities for type $type", expectedCaps, caps)
        assertEquals("buttons for type $type", expectedButtons, buttons)

        val bytes =
            controllerArrival(
                controllerNumber = 0,
                controllerType = type,
                capabilities = caps,
                supportedButtons = buttons,
            )
        assertEquals(CONTROLLER_ARRIVAL_LEN, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(8)
        assertEquals(INPUT_CONTROLLER_ARRIVAL, buf.int)
        assertEquals(0, buf.get().toInt())
        assertEquals(type, buf.get().toInt() and 0xFF)
        assertEquals(expectedCaps, buf.get().toInt() and 0xFF)
        assertEquals(0, buf.get().toInt())
        assertEquals(expectedButtons, buf.int)
    }
}
