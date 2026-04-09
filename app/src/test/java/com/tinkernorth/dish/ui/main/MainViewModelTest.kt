package com.tinkernorth.dish.ui.main

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import app.cash.turbine.test
import com.tinkernorth.dish.data.model.DiscoveredServer
import com.tinkernorth.dish.data.repository.ControllerRepository
import com.tinkernorth.dish.data.repository.DiscoveryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class MainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val application = mockk<Application>(relaxed = true)
    private val discoveryRepo = mockk<DiscoveryRepository>(relaxed = true)
    private val controllerRepo = mockk<ControllerRepository>(relaxed = true)
    private val sharedPrefs = mockk<SharedPreferences>(relaxed = true)
    private val prefsEditor = mockk<SharedPreferences.Editor>(relaxed = true)

    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Build::class)
        every { Build.MODEL } returns "TestDevice"

        every { application.getSharedPreferences("satellite", Context.MODE_PRIVATE) } returns sharedPrefs
        every { sharedPrefs.edit() } returns prefsEditor
        every { sharedPrefs.getString("deviceId", null) } returns "test-device-id"

        viewModel = MainViewModel(application, discoveryRepo, controllerRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `initial state is correct`() = runTest {
        viewModel.uiState.test {
            val initialState = awaitItem()
            assertFalse(initialState.isConnected)
            assertFalse(initialState.isScanning)
            assertTrue(initialState.discoveredServers.isEmpty())
            assertTrue(initialState.controllers.isEmpty())
        }
    }

    @Test
    fun `onScanClicked updates scanning state and finds servers`() = runTest {
        val server = DiscoveredServer("Test Server", "192.168.1.100", 9879, 9880, 9881)
        coEvery { discoveryRepo.discoverServers(any(), any()) } returns listOf(server)

        viewModel.uiState.test {
            awaitItem() // skip initial
            viewModel.onScanClicked()

            assertTrue(awaitItem().isScanning)

            val resultState = awaitItem()
            assertFalse(resultState.isScanning)
            assertEquals(1, resultState.discoveredServers.size)
            assertEquals("Test Server", resultState.discoveredServers[0].name)
        }
    }

    @Test
    fun `onServerSelected with successful pairing connects to server`() = runTest {
        val server = DiscoveredServer("Test Server", "192.168.1.100", 9879, 9880, 9881)
        coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns "{\"ok\":true, \"sharedKey\":\"" + "0".repeat(64) + "\"}"
        coEvery { discoveryRepo.connect(any(), any(), any()) } returns "{\"connectionId\":\"conn-123\", \"token\":\"AABBCCDD\"}"
        every { controllerRepo.openSocket(any(), any()) } returns true
        every { sharedPrefs.getString("sharedKey", "") } returns "0".repeat(64)

        viewModel.uiState.test {
            awaitItem() // skip initial
            viewModel.onServerSelected(server)

            // Should go through scanning=true then connected=true
            var state = awaitItem()
            while (!state.isConnected) {
                state = awaitItem()
            }
            assertTrue(state.isConnected)
            assertEquals("Test Server", state.connectedServerName)
        }
    }

    @Test
    fun `onServerSelected with pairing required emits ShowPairingDialog event`() = runTest {
        val server = DiscoveredServer("Test Server", "192.168.1.100", 9879, 9880, 9881)
        coEvery { discoveryRepo.pair(any(), any(), any(), any(), "") } returns "{\"ok\":false, \"error\":\"Pairing required\"}"

        viewModel.events.test {
            viewModel.onServerSelected(server)
            val event = awaitItem()
            assertTrue(event is MainEvent.ShowPairingDialog)
            assertEquals(server, (event as MainEvent.ShowPairingDialog).server)
        }
    }

    @Test
    fun `onDisconnectClicked cleans up connection`() = runTest {
        val server = DiscoveredServer("Test Server", "192.168.1.100", 9879, 9880, 9881)
        coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns "{\"ok\":true, \"sharedKey\":\"" + "0".repeat(64) + "\"}"
        coEvery { discoveryRepo.connect(any(), any(), any()) } returns "{\"connectionId\":\"conn-123\", \"token\":\"AABBCCDD\"}"
        every { controllerRepo.openSocket(any(), any()) } returns true
        every { sharedPrefs.getString("sharedKey", "") } returns "0".repeat(64)

        viewModel.uiState.test {
            awaitItem() // skip initial
            viewModel.onServerSelected(server)

            while (!awaitItem().isConnected) { /* wait */ }

            viewModel.onDisconnectClicked()

            var state = awaitItem()
            while (state.isConnected) {
                state = awaitItem()
            }
            assertFalse(state.isConnected)
        }

        coVerify { discoveryRepo.disconnect(any(), any(), eq("conn-123"), any()) }
        coVerify { controllerRepo.closeSocket() }
        coVerify { controllerRepo.stopHeartbeat() }
    }

    @Test
    fun `onControllerConnected adds controller to state`() = runTest {
        viewModel.uiState.test {
            awaitItem() // initial
            viewModel.onControllerConnected(1, "Test Controller")

            var state = awaitItem()
            while (state.controllers.isEmpty()) {
                state = awaitItem()
            }
            assertEquals(1, state.controllers.size)
            assertEquals("Test Controller", state.controllers[0].name)
        }

        coVerify { controllerRepo.addController(0, 0x0003) }
    }
}
