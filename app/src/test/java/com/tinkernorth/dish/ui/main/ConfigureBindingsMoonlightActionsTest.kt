// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.content.Context
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.repository.SatelliteCapabilitiesRepository
import com.tinkernorth.dish.repository.SatelliteCatalogRepository
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnection
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionEvent
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightProbe
import com.tinkernorth.dish.source.connection.moonlight.MoonlightTrustState
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.RumbleEnabledStore
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import com.tinkernorth.dish.source.usb.UsbGamepadManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the binding screen DOES, as opposed to what it renders: that saving a binding
 * reaches the store whatever the host has just said about itself, and that the two
 * actions whose worth depends on what happens after them actually do it.
 *
 * The render side of the same states is pinned by MoonlightSessionUiTest and
 * ConfigUiStateMoonlightTest; this suite is the wiring underneath them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigureBindingsMoonlightActionsTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var hub: ConnectionCoordinator
    private lateinit var moonlight: MoonlightConnectionManager
    private lateinit var vm: ConfigureBindingsViewModel

    private val host = MoonlightHost(name = "PC", address = "10.0.0.5", uniqueId = "abc")

    private val summary =
        ConnectionSummary(
            id = host.id,
            kind = ConnectionKind.MOONLIGHT,
            label = "PC",
            detail = "",
            live = LinkState.Saved,
            boundSlotIds = emptyList(),
        )

    private val connections = MutableStateFlow(listOf(summary))
    private val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
    private val satTypes = MutableStateFlow<Map<Pair<String, String>, Int>>(emptyMap())
    private val events = MutableSharedFlow<MoonlightConnectionEvent>(extraBufferCapacity = 8)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        hub = mockk(relaxed = true)
        every { hub.connections } returns connections
        every { hub.bindings } returns bindings
        every { hub.satTypes } returns satTypes
        every { hub.summary(host.id) } returns summary
        every { hub.bind(any(), any(), any()) } returns true

        moonlight = mockk(relaxed = true)
        every { moonlight.events } returns events
        every { moonlight.rememberedHost(host.id) } returns host
        every { moonlight.rememberedEmulatedType(host.id) } returns MoonlightEmulatedType.AUTO
        every { moonlight.rememberedAppId(host.id) } returns ""
        every { moonlight.rememberedAppName(host.id) } returns ""
        every { moonlight.get(host.id) } returns null
        coEvery { moonlight.probe(any()) } returns MoonlightProbe(trust = MoonlightTrustState.PAIRED)

        val capabilities = mockk<CapabilityComposer>(relaxed = true)
        every { capabilities.capabilityFor(any()) } returns SlotCapabilities.NONE
        every { capabilities.capabilityForCandidate(any(), any(), any(), any()) } returns SlotCapabilities.NONE
        val registry = mockk<PhysicalGamepadRegistry>(relaxed = true)
        every { registry.devices } returns MutableStateFlow(emptyMap())
        val usb = mockk<UsbGamepadManager>(relaxed = true)
        every { usb.controllers } returns MutableStateFlow(emptyMap())

        vm =
            ConfigureBindingsViewModel(
                context = mockk<Context>(relaxed = true),
                hub = hub,
                gamepadRegistry = registry,
                motionEnabledStore = mockk<MotionEnabledStore>(relaxed = true),
                rumbleEnabledStore = mockk<RumbleEnabledStore>(relaxed = true),
                capabilityComposer = capabilities,
                satellite = mockk<SatelliteConnectionManager>(relaxed = true),
                moonlight = moonlight,
                usbGamepadManager = usb,
                catalogRepo = mockk<SatelliteCatalogRepository>(relaxed = true),
                capabilitiesRepo = mockk<SatelliteCapabilitiesRepository>(relaxed = true),
                native = mockk<PhysicalInputNative>(relaxed = true),
                hostFeaturesStore = SatelliteHostFeaturesStore(),
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun openOn(trust: MoonlightTrustState) {
        coEvery { moonlight.probe(any()) } returns MoonlightProbe(trust = trust)
        vm.load(VIRTUAL_SLOT_ID)
        vm.setHost(host.id)
        dispatcher.scheduler.advanceUntilIdle()
    }

    // B12, B21. A binding is a durable intent: it is saved against the host the user
    // chose, not against the answer that host happened to give a second earlier. The
    // session is attempted when the controller is used, not when the binding is saved.
    @Test
    fun `a binding is saved whatever the host has just said about itself`() =
        runTest(dispatcher) {
            val states =
                listOf(
                    MoonlightTrustState.NOT_PAIRED,
                    MoonlightTrustState.UNREACHABLE,
                    MoonlightTrustState.REMEMBERED,
                    MoonlightTrustState.TRUST_LOST,
                    MoonlightTrustState.REPLACED,
                    MoonlightTrustState.PAIRED,
                )
            states.forEach { trust ->
                openOn(trust)

                assertTrue("$trust must not block Apply", vm.ui.value.canApply)
                vm.apply()
                dispatcher.scheduler.advanceUntilIdle()

                val finished = vm.applyState.value as ApplyState.Finished
                assertNull("$trust ended in ${finished.errorMessage}", finished.errorMessage)
                vm.dismissApplyResult()
            }
            verify(exactly = states.size) { hub.bind(VIRTUAL_SLOT_ID, host.id, MoonlightEmulatedType.AUTO) }
        }

    // B7. The controller number is 1-based for the reader and 0-based on the wire, so the
    // pad the host was told about as 0 is the one the card calls controller 1.
    @Test
    fun `a live session names the app and this binding's own controller number`() =
        runTest(dispatcher) {
            val conn = MoonlightConnection(host.id, host, TestScope(dispatcher), dispatcher)
            conn.acquirePad(VIRTUAL_SLOT_ID, MoonlightEmulatedType.XBOX, 0x03, 0xFFFF)
            every { moonlight.get(host.id) } returns conn
            coEvery { moonlight.probe(any()) } returns MoonlightProbe(trust = MoonlightTrustState.PAIRED)

            vm.load(VIRTUAL_SLOT_ID)
            vm.setHost(host.id)
            // Queued after the probe, so the probe reads the session the way a live one reads.
            conn.markLive(mockk(relaxed = true), "1", "Desktop")
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(MoonlightSessionUi.Live(controllerNumber = 1, appName = "Desktop"), vm.ui.value.moonlightSession)
        }

    // B16. A cancel answers 200 whether or not anything was running, so its reply proves
    // nothing and the screen has to ask the host again rather than believe it.
    @Test
    fun `quitting the app asks the host to close it and then re-checks the host`() =
        runTest(dispatcher) {
            openOn(MoonlightTrustState.PAIRED)

            vm.onMoonlightAction(MoonlightAction.QUIT_APP)
            dispatcher.scheduler.advanceUntilIdle()

            verify { moonlight.quitHostApp(host) }
            // Once on entering the screen, once because the cancel proved nothing.
            coVerify(atLeast = 2) { moonlight.probe(host) }
        }

    // B5. New code is only ever offered while a pairing is in flight, so a guard that did
    // nothing when one was live made the one button that state exists to offer unreachable.
    @Test
    fun `asking for a new code while a pairing is live starts another one`() =
        runTest(dispatcher) {
            coEvery { moonlight.pairHost(any()) } coAnswers { awaitCancellation() }
            openOn(MoonlightTrustState.NOT_PAIRED)

            vm.onMoonlightAction(MoonlightAction.PAIR)
            dispatcher.scheduler.runCurrent()
            vm.onMoonlightAction(MoonlightAction.NEW_CODE)
            dispatcher.scheduler.runCurrent()

            coVerify(exactly = 2) { moonlight.pairHost(host) }
        }

    // B5. Cancel drops the pairing job, so phase 1 is not left holding its socket for the
    // rest of the PIN window and completing a pairing nobody is watching any more.
    @Test
    fun `cancelling a pairing cancels the job behind it`() =
        runTest(dispatcher) {
            var running = 0
            coEvery { moonlight.pairHost(any()) } coAnswers {
                running++
                try {
                    awaitCancellation()
                } finally {
                    running--
                }
            }
            openOn(MoonlightTrustState.NOT_PAIRED)

            vm.onMoonlightAction(MoonlightAction.PAIR)
            dispatcher.scheduler.runCurrent()
            assertEquals(1, running)
            vm.onMoonlightAction(MoonlightAction.CANCEL)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("the pairing has to stop when the user says so", 0, running)
        }
}
