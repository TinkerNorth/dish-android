// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.testing.composerTest
import com.tinkernorth.dish.architecture.testing.probe
import com.tinkernorth.dish.core.net.moonlight.AUTO
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.core.net.moonlight.NINTENDO
import com.tinkernorth.dish.core.net.moonlight.PLAYSTATION
import com.tinkernorth.dish.core.net.moonlight.XBOX
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_DS4
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_MOUSE
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test

// A Moonlight host hears a pad's type in the CONTROLLER_ARRIVAL sent when the session acquires
// the pad, and again only when the session replugs the pad as another type. The composer shows
// the type the session holds the pad as, whatever Auto or the stored pick would resolve to now,
// and resolves live only for a pad the session has not acquired.
class CapabilityComposerAnnouncedTypeTest {
    private val host = MoonlightHost(name = "PC", address = "10.0.0.5", uniqueId = "abc")

    private fun TestScope.session(): MoonlightConnection =
        MoonlightConnection(HOST_ID, host, backgroundScope, StandardTestDispatcher(testScheduler))

    private fun MoonlightConnection.acquire(
        slotId: String,
        type: Int,
    ) = acquirePad(slotId = slotId, emulatedType = type, capabilities = 0, supportedButtons = 0)

    private fun pad(hasGyro: Boolean): PhysicalGamepadRegistry.Device = device(PAD_ID, hasGyro = hasGyro, touchpadDeviceId = PAD_SURFACE_ID)

    private class Rig(
        val composer: CapabilityComposer,
        val devices: MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>,
        val session: MoonlightConnection,
    )

    private fun TestScope.rig(
        padHasGyro: Boolean,
        storedType: Int = AUTO,
    ): Rig {
        val devices = MutableStateFlow(mapOf(PAD_ID to pad(padHasGyro)))
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

            rig.session.reannouncePad(PAD_SLOT, NINTENDO, capabilities = 0, supportedButtons = 0)
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

    @Test
    fun `a candidate query still resolves live, because the session asks it what to announce`() =
        composerTest {
            val rig = rig(padHasGyro = true)
            rig.session.acquire(PAD_SLOT, XBOX)
            testScheduler.runCurrent()

            val candidate = rig.composer.capabilityForCandidate(PAD_SLOT, AUTO, ConnectionKind.MOONLIGHT, HOST_ID)

            assertEquals(moonlightTypeCapabilities(PLAYSTATION), candidate.type)
        }

    private companion object {
        const val HOST_ID = "moonlight:abc"
        const val PAD_ID = 7
        const val PAD_SLOT = "7"
        const val OTHER_SLOT = "8"
        const val PAD_SURFACE_ID = 70
    }
}
