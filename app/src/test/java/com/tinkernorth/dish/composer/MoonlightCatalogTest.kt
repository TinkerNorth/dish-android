// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.net.moonlight.MoonlightControlProtocol
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType
import com.tinkernorth.dish.core.net.moonlight.MoonlightInputEncoder
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
        MoonlightControlProtocol.CAP_ANALOG_TRIGGERS or
            MoonlightControlProtocol.CAP_RUMBLE or
            MoonlightControlProtocol.CAP_TRIGGER_RUMBLE or
            MoonlightControlProtocol.CAP_BATTERY

    @Test
    fun `PlayStation is the only type with motion, touchpad and a lightbar`() {
        val ps = MoonlightCatalog.typeCapabilities(MoonlightEmulatedType.PLAYSTATION)
        assertTrue(Feature.RUMBLE in ps)
        assertTrue(Feature.MOTION in ps)
        assertTrue(Feature.TOUCHPAD in ps)
        assertTrue(Feature.LIGHTBAR in ps)
    }

    @Test
    fun `Xbox carries rumble and nothing else beyond a pad`() {
        val xbox = MoonlightCatalog.typeCapabilities(MoonlightEmulatedType.XBOX)
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
        val nintendo = MoonlightCatalog.typeCapabilities(MoonlightEmulatedType.NINTENDO)
        assertTrue(Feature.RUMBLE in nintendo)
        assertFalse(Feature.MOTION in nintendo)
        assertFalse(Feature.TOUCHPAD in nintendo)
        assertEquals(
            MoonlightCatalog.typeCapabilities(MoonlightEmulatedType.XBOX),
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
        ).forEach { assertTrue(it.name, it in MoonlightCatalog.HOST_LAYER) }
    }

    @Test
    fun `mouse rides the control stream on every type, keyboard stays out until implemented`() {
        listOf(MoonlightEmulatedType.XBOX, MoonlightEmulatedType.PLAYSTATION, MoonlightEmulatedType.NINTENDO)
            .forEach { type -> assertTrue(Feature.MOUSE in MoonlightCatalog.typeCapabilities(type)) }
        assertFalse(Feature.KEYBOARD in MoonlightCatalog.HOST_LAYER)
    }

    @Test
    fun `source bits claim a battery only when the source reports one`() {
        assertEquals(
            MoonlightControlProtocol.CAP_BATTERY,
            MoonlightCatalog.sourceBits(everything) and MoonlightControlProtocol.CAP_BATTERY,
        )
        val noBattery = everything - CapabilitySet.of(Feature.BATTERY)
        assertEquals(0, MoonlightCatalog.sourceBits(noBattery) and MoonlightControlProtocol.CAP_BATTERY)
    }

    @Test
    fun `a fully capable source declares the base bits for Xbox and Nintendo and 0xFF for PlayStation`() {
        assertEquals(baseBits, MoonlightCatalog.capabilityBits(MoonlightEmulatedType.XBOX, everything))
        assertEquals(baseBits, MoonlightCatalog.capabilityBits(MoonlightEmulatedType.NINTENDO, everything))
        assertEquals(0xFF, MoonlightCatalog.capabilityBits(MoonlightEmulatedType.PLAYSTATION, everything))
    }

    @Test
    fun `trigger rumble and battery ride only when the source really has them`() {
        val noExtras =
            CapabilitySet.of(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.RUMBLE)
        val bits = MoonlightCatalog.capabilityBits(MoonlightEmulatedType.XBOX, noExtras)
        // Rumble no longer drags CAP_TRIGGER_RUMBLE along: a pad without the
        // trigger motors must not invite RUMBLE_TRIGGERS events it would eat.
        assertEquals(0, bits and MoonlightControlProtocol.CAP_TRIGGER_RUMBLE)
        assertEquals(0, bits and MoonlightControlProtocol.CAP_BATTERY)
        val withExtras =
            CapabilitySet.of(
                Feature.GAMEPAD,
                Feature.ANALOG_TRIGGERS,
                Feature.RUMBLE,
                Feature.TRIGGER_RUMBLE,
                Feature.BATTERY,
            )
        val bits2 = MoonlightCatalog.capabilityBits(MoonlightEmulatedType.XBOX, withExtras)
        assertEquals(MoonlightControlProtocol.CAP_TRIGGER_RUMBLE, bits2 and MoonlightControlProtocol.CAP_TRIGGER_RUMBLE)
        assertEquals(MoonlightControlProtocol.CAP_BATTERY, bits2 and MoonlightControlProtocol.CAP_BATTERY)
    }

    @Test
    fun `a source without motion does not let a PlayStation type ask for gyro reports`() {
        val noMotion = CapabilitySet.of(Feature.GAMEPAD, Feature.ANALOG_TRIGGERS, Feature.RUMBLE)
        val bits = MoonlightCatalog.capabilityBits(MoonlightEmulatedType.PLAYSTATION, noMotion)
        assertEquals(0, bits and MoonlightControlProtocol.CAP_GYRO)
        assertEquals(0, bits and MoonlightControlProtocol.CAP_ACCELEROMETER)
        assertEquals(0, bits and MoonlightControlProtocol.CAP_TOUCHPAD)
    }

    // The whole chain, byte for byte: catalog -> declared bits -> the packet the host reads
    // out of its naturally aligned struct. A live Sunshine host logs these as
    // `capabilities [0003] supportedButtonFlags [0000FFFF]` for the Xbox case.
    @Test
    fun `each type produces its own byte-exact CONTROLLER_ARRIVAL`() {
        assertArrival(MoonlightEmulatedType.XBOX, expectedCaps = 0x47, expectedButtons = 0xFFFF)
        assertArrival(MoonlightEmulatedType.NINTENDO, expectedCaps = 0x47, expectedButtons = 0xFFFF)
        assertArrival(
            MoonlightEmulatedType.PLAYSTATION,
            expectedCaps = 0xFF,
            expectedButtons = 0xFFFF or MoonlightControlProtocol.BTN_TOUCHPAD,
        )
    }

    private fun assertArrival(
        type: Int,
        expectedCaps: Int,
        expectedButtons: Int,
    ) {
        val caps = MoonlightCatalog.capabilityBits(type, everything)
        val buttons = MoonlightEmulatedType.supportedButtons(caps)
        assertEquals("capabilities for type $type", expectedCaps, caps)
        assertEquals("buttons for type $type", expectedButtons, buttons)

        val bytes =
            MoonlightInputEncoder.controllerArrival(
                controllerNumber = 0,
                controllerType = type,
                capabilities = caps,
                supportedButtons = buttons,
            )
        assertEquals(MoonlightInputEncoder.CONTROLLER_ARRIVAL_LEN, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(8)
        assertEquals(MoonlightControlProtocol.INPUT_CONTROLLER_ARRIVAL, buf.int)
        assertEquals(0, buf.get().toInt())
        assertEquals(type, buf.get().toInt() and 0xFF)
        assertEquals(expectedCaps, buf.get().toInt() and 0xFF)
        assertEquals(0, buf.get().toInt())
        assertEquals(expectedButtons, buf.int)
    }
}
