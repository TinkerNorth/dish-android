// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.testing.composerTest
import com.tinkernorth.dish.architecture.testing.probe
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.net.moonlight.AUTO
import com.tinkernorth.dish.core.net.moonlight.CAP_ACCELEROMETER
import com.tinkernorth.dish.core.net.moonlight.CAP_ANALOG_TRIGGERS
import com.tinkernorth.dish.core.net.moonlight.CAP_BATTERY
import com.tinkernorth.dish.core.net.moonlight.CAP_GYRO
import com.tinkernorth.dish.core.net.moonlight.CAP_RGB_LED
import com.tinkernorth.dish.core.net.moonlight.CAP_RUMBLE
import com.tinkernorth.dish.core.net.moonlight.CAP_TOUCHPAD
import com.tinkernorth.dish.core.net.moonlight.CAP_TRIGGER_RUMBLE
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.core.net.moonlight.NINTENDO
import com.tinkernorth.dish.core.net.moonlight.PLAYSTATION
import com.tinkernorth.dish.core.net.moonlight.XBOX
import com.tinkernorth.dish.core.net.moonlight.capabilityBits
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_DS4
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_MOUSE
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnection
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A Moonlight host hears a pad's type in the CONTROLLER_ARRIVAL sent when the session acquires
// the pad, and again only when the session replugs the pad as another type. The composer shows
// the type the session holds the pad as, whatever Auto or the stored pick would resolve to now,
// and resolves live only for a pad the session has not acquired.
class CapabilityComposerAnnouncedTypeTest {
    private val host = MoonlightHost(name = "PC", address = "10.0.0.5", uniqueId = "abc")

    private fun TestScope.session(): MoonlightConnection =
        MoonlightConnection(HOST_ID, host, backgroundScope, StandardTestDispatcher(testScheduler))

    // Announced with every bit the type allows unless a test says otherwise, so by default only
    // the type narrows what the dashboard shows.
    private fun MoonlightConnection.acquire(
        slotId: String,
        type: Int,
        capabilities: Int = capabilityBits(type, ALL_BITS),
    ) = acquirePad(slotId = slotId, emulatedType = type, capabilities = capabilities, supportedButtons = 0)

    private fun pad(
        hasGyro: Boolean,
        surface: Int? = PAD_SURFACE_ID,
    ): PhysicalGamepadRegistry.Device = device(PAD_ID, hasGyro = hasGyro, touchpadDeviceId = surface)

    private fun Rig.candidateHasInput(slotId: String): Boolean =
        composer.capabilityForCandidate(slotId, AUTO, ConnectionKind.MOONLIGHT, HOST_ID).inputOk(Feature.GAMEPAD)

    private class Rig(
        val composer: CapabilityComposer,
        val devices: MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>,
        val session: MoonlightConnection,
    )

    private fun TestScope.rig(
        padHasGyro: Boolean,
        storedType: Int = AUTO,
        padSurface: Int? = PAD_SURFACE_ID,
    ): Rig {
        val devices = MutableStateFlow(mapOf(PAD_ID to pad(padHasGyro, padSurface)))
        val session = session()
        val types = mapOf(PAD_SLOT to storedType)
        val composer =
            composerFor(
                phoneAvailable = false,
                devices = devices,
                bindings = MutableStateFlow(mapOf(PAD_SLOT to HOST_ID)),
                connections =
                    MutableStateFlow(listOf(summary(HOST_ID, kind = ConnectionKind.MOONLIGHT, satelliteControllerTypes = types))),
                scope = backgroundScope,
                stores =
                    StoreStates(
                        satTypes = MutableStateFlow(mapOf((HOST_ID to PAD_SLOT) to storedType)),
                        moonlightConnections = MutableStateFlow(mapOf(HOST_ID to session)),
                    ),
                model = ModelFacts(modelHasTouchpad = true),
            )
        composer.probe(this)
        return Rig(composer, devices, session)
    }

    @Test
    fun `the session keeps the type a held pad was announced with, whatever it is asked for next`() =
        composerTest {
            val session = session()
            session.acquire(PAD_SLOT, XBOX)
            session.acquire(PAD_SLOT, PLAYSTATION)
            assertEquals(XBOX, session.padFor(PAD_SLOT)?.emulatedType)
        }

    @Test
    fun `a gyro that enumerates after the pad was announced as Xbox leaves the dashboard on Xbox`() =
        composerTest {
            val rig = rig(padHasGyro = false)
            rig.session.acquire(PAD_SLOT, XBOX)
            testScheduler.runCurrent()

            rig.devices.value = mapOf(PAD_ID to pad(hasGyro = true))
            testScheduler.runCurrent()

            assertEquals(moonlightTypeCapabilities(XBOX), rig.composer.capabilityFor(PAD_SLOT).type)
            assertEquals(TOUCHPAD_MODE_MOUSE, rig.composer.touchpadWireMode(PAD_SLOT))
        }

    @Test
    fun `a gyro that goes away after the pad was announced as PlayStation leaves the dashboard on PlayStation`() =
        composerTest {
            val rig = rig(padHasGyro = true)
            rig.session.acquire(PAD_SLOT, PLAYSTATION)
            testScheduler.runCurrent()

            rig.devices.value = mapOf(PAD_ID to pad(hasGyro = false))
            testScheduler.runCurrent()

            assertEquals(moonlightTypeCapabilities(PLAYSTATION), rig.composer.capabilityFor(PAD_SLOT).type)
            assertEquals(TOUCHPAD_MODE_DS4, rig.composer.touchpadWireMode(PAD_SLOT))
        }

    @Test
    fun `an explicit re-pick while the pad is held shows the pad the host still has`() =
        composerTest {
            val rig = rig(padHasGyro = false, storedType = NINTENDO)
            rig.session.acquire(PAD_SLOT, PLAYSTATION)
            testScheduler.runCurrent()

            assertEquals(moonlightTypeCapabilities(PLAYSTATION), rig.composer.capabilityFor(PAD_SLOT).type)
            assertEquals(TOUCHPAD_MODE_DS4, rig.composer.touchpadWireMode(PAD_SLOT))
        }

    @Test
    fun `a pad the session replugs as another type re-publishes the dashboard with it`() =
        composerTest {
            val rig = rig(padHasGyro = false, storedType = NINTENDO)
            rig.session.acquire(PAD_SLOT, PLAYSTATION)
            testScheduler.runCurrent()

            rig.session.reannouncePad(PAD_SLOT, NINTENDO, capabilities = capabilityBits(NINTENDO, ALL_BITS), supportedButtons = 0)
            testScheduler.runCurrent()

            assertEquals(moonlightTypeCapabilities(NINTENDO), rig.composer.capabilityFor(PAD_SLOT).type)
        }

    @Test
    fun `acquiring the pad re-publishes the dashboard with the announced type`() =
        composerTest {
            val rig = rig(padHasGyro = true)
            testScheduler.runCurrent()
            assertEquals(moonlightTypeCapabilities(PLAYSTATION), rig.composer.capabilityFor(PAD_SLOT).type)

            rig.session.acquire(PAD_SLOT, XBOX)
            testScheduler.runCurrent()

            assertEquals(moonlightTypeCapabilities(XBOX), rig.composer.capabilityFor(PAD_SLOT).type)
        }

    @Test
    fun `releasing the pad hands the dashboard back to the live resolution`() =
        composerTest {
            val rig = rig(padHasGyro = true)
            rig.session.acquire(PAD_SLOT, XBOX)
            testScheduler.runCurrent()

            rig.session.releasePad(PAD_SLOT)
            testScheduler.runCurrent()

            assertEquals(moonlightTypeCapabilities(PLAYSTATION), rig.composer.capabilityFor(PAD_SLOT).type)
            assertEquals(TOUCHPAD_MODE_DS4, rig.composer.touchpadWireMode(PAD_SLOT))
        }

    @Test
    fun `another slot's pad on the same session says nothing about this one`() =
        composerTest {
            val rig = rig(padHasGyro = true)
            rig.session.acquire(OTHER_SLOT, XBOX)
            testScheduler.runCurrent()

            assertEquals(moonlightTypeCapabilities(PLAYSTATION), rig.composer.capabilityFor(PAD_SLOT).type)
            assertEquals(TOUCHPAD_MODE_DS4, rig.composer.touchpadWireMode(PAD_SLOT))
        }

    // The host reads the motion bits in the arrival, and only then: a PlayStation pad announced
    // before its gyro enumerated has no motion on the host until the session replugs it with the
    // bits, so the dashboard shows no motion until then either.
    @Test
    fun `a held pad announced without the motion bits shows no motion while the pad has a gyro`() =
        composerTest {
            val rig = rig(padHasGyro = false, storedType = PLAYSTATION)
            rig.session.acquire(PAD_SLOT, PLAYSTATION, capabilities = PS_BITS_WITHOUT_MOTION)
            testScheduler.runCurrent()

            rig.devices.value = mapOf(PAD_ID to pad(hasGyro = true))
            testScheduler.runCurrent()

            val caps = rig.composer.capabilityFor(PAD_SLOT)
            assertFalse(caps.typeOk(Feature.MOTION))
            assertFalse(caps.isAvailable(Feature.MOTION))
            assertTrue(caps.typeOk(Feature.TOUCHPAD))
        }

    @Test
    fun `a replug that carries the motion bits re-publishes motion on the dashboard`() =
        composerTest {
            val rig = rig(padHasGyro = true, storedType = PLAYSTATION)
            rig.session.acquire(PAD_SLOT, PLAYSTATION, capabilities = PS_BITS_WITHOUT_MOTION)
            testScheduler.runCurrent()

            rig.session.reannouncePad(PAD_SLOT, PLAYSTATION, capabilities = capabilityBits(PLAYSTATION, ALL_BITS), supportedButtons = 0)
            testScheduler.runCurrent()

            assertEquals(moonlightTypeCapabilities(PLAYSTATION), rig.composer.capabilityFor(PAD_SLOT).type)
            assertTrue(rig.composer.capabilityFor(PAD_SLOT).isAvailable(Feature.MOTION))
        }

    // Wolf reads no other bit at arrival (control/input_handler.cpp create_new_joypad): the pad
    // it builds, its rumble, its LED, its battery and its touch surface follow the type alone.
    @Test
    fun `a bit the host never reads at arrival crosses nothing out of the dashboard`() =
        composerTest {
            val rig = rig(padHasGyro = true, storedType = PLAYSTATION)
            rig.session.acquire(PAD_SLOT, PLAYSTATION)
            val everything = capabilityBits(PLAYSTATION, ALL_BITS)
            for (bit in NON_MOTION_BITS) {
                rig.session.reannouncePad(PAD_SLOT, PLAYSTATION, capabilities = everything and bit.inv(), supportedButtons = 0)
                testScheduler.runCurrent()

                assertEquals("bit $bit", moonlightTypeCapabilities(PLAYSTATION), rig.composer.capabilityFor(PAD_SLOT).type)
            }
        }

    // A pad announced with one motion bit still declares motion: either is what the host reads
    // to ask for motion at all.
    @Test
    fun `a held pad announced with either motion bit shows motion`() =
        composerTest {
            val rig = rig(padHasGyro = true, storedType = PLAYSTATION)
            rig.session.acquire(PAD_SLOT, PLAYSTATION)
            for (motion in listOf(CAP_ACCELEROMETER, CAP_GYRO)) {
                rig.session.reannouncePad(PAD_SLOT, PLAYSTATION, capabilities = PS_BITS_WITHOUT_MOTION or motion, supportedButtons = 0)
                testScheduler.runCurrent()

                assertTrue("bit $motion", rig.composer.capabilityFor(PAD_SLOT).typeOk(Feature.MOTION))
            }
        }

    // Wolf's PlayStation pad is one DualSense whatever the arrival said, and it places every
    // CONTROLLER_TOUCH on it (control/input_handler.cpp controller_touch), so a surface that
    // enumerates after the pad was announced reaches the host's touchpad without a replug.
    @Test
    fun `a surface that enumerates after a PlayStation pad was announced shows the touchpad and routes it to the pad`() =
        composerTest {
            val rig = rig(padHasGyro = true, storedType = PLAYSTATION, padSurface = null)
            rig.session.acquire(PAD_SLOT, PLAYSTATION, capabilities = capabilityBits(PLAYSTATION, ALL_BITS) and CAP_TOUCHPAD.inv())
            testScheduler.runCurrent()

            rig.devices.value = mapOf(PAD_ID to pad(hasGyro = true, surface = PAD_SURFACE_ID))
            testScheduler.runCurrent()

            val caps = rig.composer.capabilityFor(PAD_SLOT)
            assertTrue(caps.typeOk(Feature.TOUCHPAD))
            assertTrue(caps.isAvailable(Feature.TOUCHPAD))
            assertEquals(TOUCHPAD_MODE_DS4, rig.composer.touchpadWireMode(PAD_SLOT))
        }

    // The type still rules: an Xbox pad on the host has no touch surface, whatever bits it came with.
    @Test
    fun `a held Xbox pad routes the surface to the mouse, whatever bits it was announced with`() =
        composerTest {
            val rig = rig(padHasGyro = false, storedType = XBOX)
            rig.session.acquire(PAD_SLOT, XBOX, capabilities = ALL_BITS)
            testScheduler.runCurrent()

            assertFalse(rig.composer.capabilityFor(PAD_SLOT).typeOk(Feature.TOUCHPAD))
            assertEquals(TOUCHPAD_MODE_MOUSE, rig.composer.touchpadWireMode(PAD_SLOT))
        }

    @Test
    fun `a candidate query still resolves live, because the session asks it what to announce`() =
        composerTest {
            val rig = rig(padHasGyro = true)
            rig.session.acquire(PAD_SLOT, XBOX)
            testScheduler.runCurrent()

            val candidate = rig.composer.capabilityForCandidate(PAD_SLOT, AUTO, ConnectionKind.MOONLIGHT, HOST_ID)

            assertEquals(moonlightTypeCapabilities(PLAYSTATION), candidate.type)
        }

    // The session controller asks for no new pad for a slot whose candidate has no input: that
    // is how it tells a device the registry no longer has from one it can derive a pad from.
    @Test
    fun `a candidate for a pad the registry no longer has has no input, and one for a present pad has`() =
        composerTest {
            val rig = rig(padHasGyro = true)
            assertTrue(rig.candidateHasInput(PAD_SLOT))

            rig.devices.value = emptyMap()

            assertFalse(rig.candidateHasInput(PAD_SLOT))
            assertTrue(rig.candidateHasInput(VIRTUAL_SLOT_ID))
        }

    // The session derives the next request from the candidate, so a candidate that followed the
    // announced bits could never ask for the motion the held pad lacks.
    @Test
    fun `a candidate query ignores the bits the held pad was announced with`() =
        composerTest {
            val rig = rig(padHasGyro = true, storedType = PLAYSTATION)
            rig.session.acquire(PAD_SLOT, PLAYSTATION, capabilities = PS_BITS_WITHOUT_MOTION)
            testScheduler.runCurrent()

            val candidate = rig.composer.capabilityForCandidate(PAD_SLOT, PLAYSTATION, ConnectionKind.MOONLIGHT, HOST_ID)

            assertTrue(candidate.isAvailable(Feature.MOTION))
        }

    private companion object {
        const val HOST_ID = "moonlight:abc"
        const val PAD_ID = 7
        const val PAD_SLOT = "7"
        const val OTHER_SLOT = "8"
        const val PAD_SURFACE_ID = 70
        const val ALL_BITS = 0xFF
        const val PS_BITS_WITHOUT_MOTION = ALL_BITS and (CAP_ACCELEROMETER or CAP_GYRO).inv()
        val NON_MOTION_BITS = listOf(CAP_ANALOG_TRIGGERS, CAP_RUMBLE, CAP_TRIGGER_RUMBLE, CAP_TOUCHPAD, CAP_BATTERY, CAP_RGB_LED)
    }
}
