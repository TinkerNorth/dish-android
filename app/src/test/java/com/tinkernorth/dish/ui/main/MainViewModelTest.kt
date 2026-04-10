package com.tinkernorth.dish.ui.main

import android.content.Context
import android.content.SharedPreferences
import app.cash.turbine.test
import com.tinkernorth.dish.data.model.DiscoveredServer
import com.tinkernorth.dish.data.network.ServerConnectionManager
import com.tinkernorth.dish.data.repository.ControllerRepository
import com.tinkernorth.dish.data.repository.DiscoveryRepository
import com.tinkernorth.dish.ui.common.ControllerManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val context = mockk<Context>(relaxed = true)
    private val discoveryRepo = mockk<DiscoveryRepository>(relaxed = true)
    private val controllerRepo = mockk<ControllerRepository>(relaxed = true)
    private val sharedPrefs = mockk<SharedPreferences>(relaxed = true)
    private val prefsEditor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var serverManager: ServerConnectionManager
    private lateinit var controllerManager: ControllerManager
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { context.getSharedPreferences("satellite", Context.MODE_PRIVATE) } returns sharedPrefs
        every { sharedPrefs.edit() } returns prefsEditor
        every { sharedPrefs.getString("deviceId", null) } returns "test-device-id"
        every { sharedPrefs.getString("sharedKey", "") } returns "0".repeat(64)
    }

    private fun createViewModel(scope: TestScope) {
        serverManager = ServerConnectionManager(context, scope, discoveryRepo, controllerRepo, json)
        controllerManager = ControllerManager(scope, controllerRepo)
        viewModel = MainViewModel(serverManager, controllerManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `initial state is correct`() = runTest {
        createViewModel(this)
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
        createViewModel(this)
        val server = DiscoveredServer("Test Server", "192.168.1.100", 9879, 9880, 9881)
        coEvery { discoveryRepo.discoverServers(any(), any()) } returns listOf(server)

        viewModel.uiState.test {
            awaitItem() // skip initial
            viewModel.onScanClicked()

            // Collect until we see the final state with discovered servers
            var state = awaitItem()
            while (state.discoveredServers.isEmpty()) {
                state = awaitItem()
            }
            assertEquals(1, state.discoveredServers.size)
            assertEquals("Test Server", state.discoveredServers[0].name)
        }
    }

    @Test
    fun `onControllerConnected adds controller to state`() = runTest {
        createViewModel(this)
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
    }
}
