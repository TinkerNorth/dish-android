// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.connection

import android.content.Context
import android.content.SharedPreferences
import com.tinkernorth.dish.composer.MotionCapability
import com.tinkernorth.dish.composer.MotionCapabilityComposer
import com.tinkernorth.dish.core.jni.ControllerRepository
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.net.DiscoveryRepository
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatusStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SatelliteConnectionManagerTest {
    private lateinit var context: Context
    private lateinit var discoveryRepo: DiscoveryRepository
    private lateinit var controllerRepo: ControllerRepository
    private lateinit var store: ConnectionStore
    private lateinit var prefs: SharedPreferences
    private lateinit var prefsEditor: SharedPreferences.Editor

    private val scope = TestScope(UnconfinedTestDispatcher())
    private val ioDispatcher = UnconfinedTestDispatcher(scope.testScheduler)
    private val json = Json { ignoreUnknownKeys = true }

    private val server =
        DiscoveredServer(
            name = "Pc",
            ip = "10.0.0.5",
            udpPort = 9876,
            pairPort = 9878,
            httpPort = 9877,
        )
    private val serverId = SatelliteConnection.idFor(server)

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        discoveryRepo = mockk(relaxed = true)
        controllerRepo = mockk(relaxed = true)
        store = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        prefsEditor = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getString("deviceId", null) } returns "test-device-id"
        every { prefs.edit() } returns prefsEditor
        every { prefsEditor.putString(any(), any()) } returns prefsEditor
        every { prefsEditor.remove(any()) } returns prefsEditor
        every { store.remembered() } returns emptyList()
        every { store.satelliteSharedKey(any()) } returns null
    }

    private val motionCapabilityProvider =
        javax.inject.Provider<MotionCapabilityComposer> {
            mockk(relaxed = true) {
                every { capabilityFor(any()) } returns MotionCapability.Off
            }
        }

    private val motionBackendStatusStore = SatelliteMotionBackendStatusStore()

    private fun manager(): SatelliteConnectionManager =
        SatelliteConnectionManager(
            context = context,
            scope = scope,
            discoveryRepo = discoveryRepo,
            controllerRepo = controllerRepo,
            store = store,
            json = json,
            ioDispatcher = ioDispatcher,
            motionCapabilityProvider = motionCapabilityProvider,
            motionBackendStatusStore = motionBackendStatusStore,
        )

    private fun runMgrTest(block: suspend (SatelliteConnectionManager, MutableList<ConnectionEvent>) -> Unit) =
        runTest(scope.testScheduler) {
            val mgr = manager()
            val events = mutableListOf<ConnectionEvent>()
            val collector = scope.launch { mgr.events.collect { events += it } }
            block(mgr, events)
            scope.testScheduler.advanceUntilIdle()
            collector.cancel()
        }

    @Test
    fun `pair returning empty string surfaces server-unreachable error not PairingRequired`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns ""

            mgr.connect(server)
            scope.testScheduler.advanceUntilIdle()

            assertTrue(
                "expected unreachable Error, got: $events",
                events.any { it is ConnectionEvent.Error && it.message.contains("unreachable", ignoreCase = true) },
            )
            assertTrue(events.none { it is ConnectionEvent.PairingRequired })
            assertEquals(SatelliteSessionState.Idle, mgr.get(serverId)?.state?.value)
        }

    @Test
    fun `pair throwing exception surfaces server-unreachable error`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } throws
                RuntimeException("ECONNREFUSED")

            mgr.connect(server)
            scope.testScheduler.advanceUntilIdle()

            assertTrue(events.any { it is ConnectionEvent.Error && it.message.contains("unreachable", ignoreCase = true) })
        }

    @Test
    fun `pair returning ok=false with reachable server emits PairingRequired`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns
                """{"ok":false,"error":"PIN required"}"""

            mgr.connect(server)
            scope.testScheduler.advanceUntilIdle()

            assertTrue("expected PairingRequired, got: $events", events.any { it is ConnectionEvent.PairingRequired })
        }

    @Test
    fun `pairWithPin returning empty string surfaces unreachable error rather than retrying`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), "1234") } returns ""

            mgr.pairWithPin(server, "1234")
            scope.testScheduler.advanceUntilIdle()

            assertTrue(events.any { it is ConnectionEvent.Error && it.message.contains("unreachable", ignoreCase = true) })
        }

    @Test
    fun `pairWithPin returning ok=false surfaces server's error message`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), "0000") } returns
                """{"ok":false,"error":"Invalid PIN"}"""

            mgr.pairWithPin(server, "0000")
            scope.testScheduler.advanceUntilIdle()

            assertTrue(events.any { it is ConnectionEvent.Error && it.message.contains("Invalid PIN") })
        }

    @Test
    fun `connect skips pair when a shared key is already stored`() =
        runMgrTest { mgr, _ ->
            every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
            coEvery { discoveryRepo.connect(any(), any(), any()) } returns ""

            mgr.connect(server)
            scope.testScheduler.advanceUntilIdle()

            coVerify(exactly = 0) { discoveryRepo.pair(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `disconnect on unknown id is a no-op`() {
        val mgr = manager()
        mgr.disconnect("satellite:does-not-exist:0")
    }

    @Test
    fun `forget removes the connection from the live map`() =
        runMgrTest { mgr, _ ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns ""
            mgr.connect(server)
            scope.testScheduler.advanceUntilIdle()
            assertTrue(mgr.connections.value.containsKey(serverId))

            mgr.forget(serverId)

            assertNull(mgr.connections.value[serverId])
        }

    @Test
    fun `connect is idempotent while a session is already in CONNECTING`() =
        runMgrTest { mgr, _ ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } coAnswers {
                awaitCancellation()
            }

            mgr.connect(server)
            scope.testScheduler.runCurrent()
            mgr.connect(server)
            scope.testScheduler.runCurrent()

            coVerify(exactly = 1) { discoveryRepo.pair(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `AUTO_RECONNECT does not emit error when pair returns empty`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns ""

            mgr.connect(server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.advanceUntilIdle()

            assertTrue(
                "auto-reconnect must not emit a ConnectionEvent.Error on unreachable: $events",
                events.none { it is ConnectionEvent.Error },
            )
            assertEquals(SatelliteSessionState.Idle, mgr.get(serverId)?.state?.value)
        }

    @Test
    fun `AUTO_RECONNECT does not emit error when pair throws`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } throws
                RuntimeException("ECONNREFUSED")

            mgr.connect(server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.advanceUntilIdle()

            assertTrue(events.none { it is ConnectionEvent.Error })
        }

    @Test
    fun `USER_INITIATED still emits error on unreachable (back-compat)`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns ""

            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.advanceUntilIdle()

            assertTrue(
                "user-initiated MUST emit Error so the tap result is visible: $events",
                events.any { it is ConnectionEvent.Error },
            )
        }

    @Test
    fun `default intent is USER_INITIATED so single-arg call sites stay loud`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns ""

            mgr.connect(server)
            scope.testScheduler.advanceUntilIdle()

            assertTrue(events.any { it is ConnectionEvent.Error })
        }

    @Test
    fun `AUTO_RECONNECT marks Stale when server rejects empty PIN`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns
                """{"ok":false,"error":"PIN required"}"""

            mgr.connect(server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.advanceUntilIdle()

            assertTrue(
                "stale set should contain the server id on auto-reconnect pair-rejected",
                serverId in mgr.staleSatelliteIds.value,
            )
            assertTrue(events.none { it is ConnectionEvent.PairingRequired })
        }

    @Test
    fun `USER_INITIATED with pair-rejected still emits PairingRequired (not Stale)`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns
                """{"ok":false,"error":"PIN required"}"""

            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.advanceUntilIdle()

            assertTrue(events.any { it is ConnectionEvent.PairingRequired })
            assertTrue(
                "stale set must NOT include user-initiated tap targets",
                serverId !in mgr.staleSatelliteIds.value,
            )
        }

    @Test
    fun `pairWithPin success clears Stale marker`() =
        runMgrTest { mgr, _ ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), "") } returns
                """{"ok":false}"""
            mgr.connect(server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.advanceUntilIdle()
            assertTrue(serverId in mgr.staleSatelliteIds.value)

            coEvery { discoveryRepo.pair(any(), any(), any(), any(), "1234") } returns
                """{"ok":true,"sharedKey":"${"bb".repeat(32)}"}"""
            coEvery { discoveryRepo.connect(any(), any(), any()) } returns ""
            every { store.satelliteSharedKey(serverId) } returns "bb".repeat(32)

            mgr.pairWithPin(server, "1234")
            scope.testScheduler.advanceUntilIdle()

            assertTrue(
                "stale should clear on successful PIN handshake",
                serverId !in mgr.staleSatelliteIds.value,
            )
        }

    @Test
    fun `forget clears Stale marker`() =
        runMgrTest { mgr, _ ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns """{"ok":false}"""
            mgr.connect(server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.advanceUntilIdle()
            assertTrue(serverId in mgr.staleSatelliteIds.value)

            mgr.forget(serverId)

            assertTrue(serverId !in mgr.staleSatelliteIds.value)
        }

    @Test
    fun `events flow does not replay prior errors to a new subscriber`() =
        runTest(scope.testScheduler) {
            val mgr = manager()
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns ""
            val firstEvents = mutableListOf<ConnectionEvent>()
            val firstCollector = scope.launch { mgr.events.collect { firstEvents += it } }
            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.advanceUntilIdle()
            firstCollector.cancel()
            assertTrue("first subscriber should have received the error", firstEvents.isNotEmpty())

            val secondEvents = mutableListOf<ConnectionEvent>()
            val secondCollector = scope.launch { mgr.events.collect { secondEvents += it } }
            scope.testScheduler.advanceUntilIdle()
            secondCollector.cancel()
            assertTrue(
                "second subscriber must see no replayed events: $secondEvents",
                secondEvents.isEmpty(),
            )
        }
}
