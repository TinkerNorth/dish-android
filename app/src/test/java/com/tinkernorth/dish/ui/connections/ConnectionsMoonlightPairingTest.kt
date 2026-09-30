// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.connections

import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionEvent
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A pairing started from the hosts screen belongs to its view model, which outlives the screen. A
 * rotation recreates the screen mid-pairing, and the pairing, with the PIN it shows, has to still be
 * there for the screen that replaces it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsMoonlightPairingTest {
    private val dispatcher = StandardTestDispatcher()
    private val events = MutableSharedFlow<MoonlightConnectionEvent>(extraBufferCapacity = 8)
    private val host = MoonlightHost(name = "PC", address = "10.0.0.5")
    private val other = MoonlightHost(name = "Den", address = "10.0.0.6")
    private lateinit var moonlight: MoonlightConnectionManager
    private lateinit var vm: ConnectionsViewModel

    // Pairings that have started and not ended.
    private var running = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        moonlight = mockk(relaxed = true)
        every { moonlight.events } returns events
        coEvery { moonlight.pairHost(any()) } coAnswers { waitForThePin(firstArg()) }
        vm =
            ConnectionsViewModel(
                hub = mockk(relaxed = true),
                satellite = mockk(relaxed = true),
                moonlight = moonlight,
                store = mockk(relaxed = true),
                hostFeatures = mockk(relaxed = true),
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // A pairing in phase 1: it has announced its PIN and waits for the host to be given it.
    private suspend fun waitForThePin(paired: MoonlightHost): Boolean {
        running++
        try {
            events.emit(MoonlightConnectionEvent.PairingPinReady(paired, PIN))
            awaitCancellation()
        } finally {
            running--
        }
    }

    // The screen that replaces a rotated one subscribes after the PIN was announced.
    @Test
    fun `a hosts screen recreated mid-pairing finds the pairing running and its PIN`() =
        runTest(dispatcher) {
            vm.pairMoonlight(host)
            dispatcher.scheduler.runCurrent()

            val whatTheNewScreenSees = vm.moonlightPin.value

            assertEquals(1, running)
            assertEquals(MoonlightPinPrompt(hostName = "PC", pin = PIN), whatTheNewScreenSees)
        }

    @Test
    fun `cancelling a pairing from the hosts screen ends it and takes its PIN away`() =
        runTest(dispatcher) {
            vm.pairMoonlight(host)
            dispatcher.scheduler.runCurrent()

            vm.cancelMoonlightPairing()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, running)
            assertNull(vm.moonlightPin.value)
        }

    @Test
    fun `a pairing that ends takes its PIN away`() =
        runTest(dispatcher) {
            val pairingEnds = CompletableDeferred<Boolean>()
            coEvery { moonlight.pairHost(any()) } coAnswers {
                events.emit(MoonlightConnectionEvent.PairingPinReady(host, PIN))
                pairingEnds.await()
            }
            vm.pairMoonlight(host)
            dispatcher.scheduler.runCurrent()
            assertEquals(MoonlightPinPrompt(hostName = "PC", pin = PIN), vm.moonlightPin.value)

            pairingEnds.complete(true)
            dispatcher.scheduler.advanceUntilIdle()

            assertNull(vm.moonlightPin.value)
        }

    // The manager announces every pairing's PIN, the binding screen's included; this screen shows its own.
    @Test
    fun `a PIN announced for a pairing with another host is not shown`() =
        runTest(dispatcher) {
            coEvery { moonlight.pairHost(any()) } coAnswers {
                events.emit(MoonlightConnectionEvent.PairingPinReady(other, PIN))
                awaitCancellation()
            }

            vm.pairMoonlight(host)
            dispatcher.scheduler.runCurrent()

            assertNull(vm.moonlightPin.value)
        }

    // Pair on another row replaces the pairing running, and the one replaced may take a moment to end.
    @Test
    fun `a pairing that replaces another keeps its PIN when the one it replaced ends`() =
        runTest(dispatcher) {
            val replacedEnds = CompletableDeferred<Unit>()
            coEvery { moonlight.pairHost(host) } coAnswers {
                events.emit(MoonlightConnectionEvent.PairingPinReady(host, PIN))
                withContext(NonCancellable) { replacedEnds.await() }
                false
            }
            vm.pairMoonlight(host)
            dispatcher.scheduler.runCurrent()

            vm.pairMoonlight(other)
            dispatcher.scheduler.runCurrent()
            replacedEnds.complete(Unit)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(MoonlightPinPrompt(hostName = "Den", pin = PIN), vm.moonlightPin.value)
        }

    private companion object {
        const val PIN = "4821"
    }
}
