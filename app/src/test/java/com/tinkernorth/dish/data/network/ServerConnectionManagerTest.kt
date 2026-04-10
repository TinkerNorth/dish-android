package com.tinkernorth.dish.data.network

import android.content.Context
import android.content.SharedPreferences
import app.cash.turbine.test
import com.tinkernorth.dish.data.model.DiscoveredServer
import com.tinkernorth.dish.data.repository.ControllerRepository
import com.tinkernorth.dish.data.repository.DiscoveryRepository
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
class ServerConnectionManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val context = mockk<Context>(relaxed = true)
    private val discoveryRepo = mockk<DiscoveryRepository>(relaxed = true)
    private val controllerRepo = mockk<ControllerRepository>(relaxed = true)
    private val sharedPrefs = mockk<SharedPreferences>(relaxed = true)
    private val prefsEditor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var manager: ServerConnectionManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { context.getSharedPreferences("satellite", Context.MODE_PRIVATE) } returns sharedPrefs
        every { sharedPrefs.edit() } returns prefsEditor
        every { sharedPrefs.getString("deviceId", null) } returns "test-device-id"
        // Return a valid hex key (64 chars)
        every { sharedPrefs.getString("sharedKey", "") } returns "0".repeat(64)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `startDiscovery updates scanning state and servers`() = runTest {
        manager = ServerConnectionManager(context, this, discoveryRepo, controllerRepo, json)
        val server = DiscoveredServer("Test Server", "192.168.1.100", 9879, 9880, 9881)
        coEvery { discoveryRepo.discoverServers(any(), any()) } returns listOf(server)

        manager.discoveredServers.test {
            manager.startDiscovery()
            assertEquals(emptyList<DiscoveredServer>(), awaitItem())
            assertEquals(listOf(server), awaitItem())
        }
    }

    @Test
    fun `selectServer with successful pairing connects`() = runTest {
        manager = ServerConnectionManager(context, this, discoveryRepo, controllerRepo, json)
        val server = DiscoveredServer("Test Server", "192.168.1.100", 9879, 9880, 9881)

        coEvery { discoveryRepo.pair(any(), any(), any(), any(), "") } returns "{\"ok\":true, \"sharedKey\":\"" + "0".repeat(64) + "\"}"
        coEvery { discoveryRepo.connect(any(), any(), any()) } returns "{\"connectionId\":\"conn-123\", \"token\":\"AABBCCDD\"}"
        every { controllerRepo.openSocket(any(), any()) } returns true

        manager.isConnected.test {
            assertFalse(awaitItem())
            manager.selectServer(server)
            assertTrue(awaitItem())
        }

        assertEquals(server, manager.connectedServer.value)
        assertEquals("conn-123", manager.connectionId)
    }

    @Test
    fun `selectServer with pairing required emits event`() = runTest {
        manager = ServerConnectionManager(context, this, discoveryRepo, controllerRepo, json)
        val server = DiscoveredServer("Test Server", "192.168.1.100", 9879, 9880, 9881)

        coEvery { discoveryRepo.pair(any(), any(), any(), any(), "") } returns "{\"ok\":false, \"error\":\"Pairing required\"}"

        manager.events.test {
            manager.selectServer(server)
            val event = awaitItem()
            assertTrue(event is ConnectionEvent.PairingRequired)
            assertEquals(server, (event as ConnectionEvent.PairingRequired).server)
        }
    }

    @Test
    fun `disconnect cleans up state and calls repo`() = runTest {
        manager = ServerConnectionManager(context, this, discoveryRepo, controllerRepo, json)
        val server = DiscoveredServer("Test Server", "192.168.1.100", 9879, 9880, 9881)

        // Mock successful connection first
        coEvery { discoveryRepo.pair(any(), any(), any(), any(), "") } returns "{\"ok\":true, \"sharedKey\":\"" + "0".repeat(64) + "\"}"
        coEvery { discoveryRepo.connect(any(), any(), any()) } returns "{\"connectionId\":\"conn-123\", \"token\":\"AABBCCDD\"}"
        every { controllerRepo.openSocket(any(), any()) } returns true

        manager.isConnected.test {
            // Initial state
            assertFalse(awaitItem())

            manager.selectServer(server)

            // Wait for connected
            assertTrue(awaitItem())

            manager.disconnect()

            // Wait for disconnected
            assertFalse(awaitItem())
        }

        coVerify { controllerRepo.closeSocket() }
        coVerify { controllerRepo.stopHeartbeat() }
    }
}
