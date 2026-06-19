// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.source.connection.ConnectionEvent
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupConnectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var satellite: SatelliteConnectionManager
    private lateinit var vm: SetupConnectionViewModel

    private val discovered = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    private val connections = MutableStateFlow<Map<String, SatelliteConnection>>(emptyMap())
    private val stale = MutableStateFlow<Set<String>>(emptySet())
    private val scanning = MutableStateFlow(false)
    private val events = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 8)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        satellite = mockk(relaxed = true)
        every { satellite.discoveredServers } returns discovered
        every { satellite.connections } returns connections
        every { satellite.staleSatelliteIds } returns stale
        every { satellite.isScanning } returns scanning
        every { satellite.events } returns events
        vm = SetupConnectionViewModel(satellite)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts on the path step, not scanning`() =
        runTest(dispatcher) {
            dispatcher.scheduler.runCurrent()
            assertEquals(SetupConnectionViewModel.Step.PATH, vm.state.value.step)
            assertFalse(vm.state.value.scanning)
        }

    @Test
    fun `choosing satellite advances to the list and starts discovery`() =
        runTest(dispatcher) {
            vm.chooseSatellite()
            dispatcher.scheduler.runCurrent()
            assertEquals(SetupConnectionViewModel.Step.SATELLITE, vm.state.value.step)
            verify(exactly = 1) { satellite.startDiscovery() }
        }

    @Test
    fun `choosing satellite again does not restart discovery`() =
        runTest(dispatcher) {
            vm.chooseSatellite()
            vm.chooseSatellite()
            dispatcher.scheduler.runCurrent()
            verify(exactly = 1) { satellite.startDiscovery() }
        }

    @Test
    fun `back rewinds the list to the path pick, then defers to the activity`() =
        runTest(dispatcher) {
            vm.chooseSatellite()
            dispatcher.scheduler.runCurrent()
            assertTrue(vm.back())
            assertEquals(SetupConnectionViewModel.Step.PATH, vm.state.value.step)
            assertFalse(vm.back())
        }

    @Test
    fun `scanning state is forwarded from the manager`() =
        runTest(dispatcher) {
            scanning.value = true
            dispatcher.scheduler.runCurrent()
            assertTrue(vm.state.value.scanning)
        }

    @Test
    fun `a connection error surfaces as an error event`() =
        runTest(dispatcher) {
            val seen = collectEvents()
            events.emit(ConnectionEvent.Error("boom"))
            dispatcher.scheduler.runCurrent()
            assertEquals(listOf<SetupConnectionViewModel.Event>(SetupConnectionViewModel.Event.Error("boom")), seen)
        }

    @Test
    fun `a pairing-required event promotes the host to the pin dialog`() =
        runTest(dispatcher) {
            val server = mockk<DiscoveredServer>()
            val seen = collectEvents()
            events.emit(ConnectionEvent.PairingRequired(server))
            dispatcher.scheduler.runCurrent()
            assertEquals(1, seen.size)
            val event = seen.single()
            assertTrue(event is SetupConnectionViewModel.Event.ShowPairing)
            assertEquals(server, (event as SetupConnectionViewModel.Event.ShowPairing).server)
        }

    private fun kotlinx.coroutines.test.TestScope.collectEvents(): List<SetupConnectionViewModel.Event> {
        val out = mutableListOf<SetupConnectionViewModel.Event>()
        backgroundScope.launch { vm.events.collect { out.add(it) } }
        dispatcher.scheduler.runCurrent()
        return out
    }
}
