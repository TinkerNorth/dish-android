// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.connection

import android.content.Context
import android.content.SharedPreferences
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.core.jni.ControllerRepository
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.net.DiscoveryGateway
import com.tinkernorth.dish.core.net.HttpReply
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.repository.RememberedSatellite
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatusStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    private lateinit var discoveryRepo: DiscoveryGateway
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

    private fun reply(
        status: Int,
        body: String,
    ) = HttpReply(status, body, null)

    private fun ok(body: String) = reply(200, body)

    private fun unreachable() = reply(0, """{"error":"request failed: connect timed out"}""")

    private fun identityMismatch() = HttpReply(0, """{"error":"request failed: hostname not verified"}""", null, pinMismatch = true)

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
        // -1 = dead socket: the RX-drain loop exits after one call. Anything
        // else would spin it forever on the unconfined test dispatcher (mocks
        // return instantly, the loop never suspends).
        every { controllerRepo.receiveAck(any()) } returns -1
        every { controllerRepo.getServerEpoch(any()) } returns -1
        every { controllerRepo.getActiveBitmap(any()) } returns -1
        every { controllerRepo.getSessionCloseReason(any()) } returns -1
        every { controllerRepo.isConnectionAlive(any()) } returns true
    }

    // One shared instance: the manager pulls wire projections through the provider on every
    // descriptor build, so per-get() mocks would be unstubbable from a test body.
    private val capabilityComposer: CapabilityComposer =
        mockk(relaxed = true) {
            every { state } returns
                kotlinx.coroutines.flow.MutableStateFlow(
                    emptyMap<String, com.tinkernorth.dish.core.model.SlotCapabilities>(),
                )
            every { motionWireBit(any()) } returns 0
            every { touchpadWireMode(any()) } returns "off"
        }

    private val capabilityProvider = javax.inject.Provider<CapabilityComposer> { capabilityComposer }

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
            capabilityProvider = capabilityProvider,
            motionBackendStatusStore = motionBackendStatusStore,
        )

    private fun runMgrTest(block: suspend (SatelliteConnectionManager, MutableList<ConnectionEvent>) -> Unit) =
        runTest(scope.testScheduler) {
            val mgr = manager()
            val events = mutableListOf<ConnectionEvent>()
            val collector = scope.launch { mgr.events.collect { events += it } }
            block(mgr, events)
            // A live session's heartbeat poll reschedules itself forever, so
            // the scheduler can never go idle while one exists. Tear all
            // sessions down before the final drain.
            mgr.connections.value.keys
                .forEach(mgr::disconnect)
            scope.testScheduler.advanceUntilIdle()
            collector.cancel()
        }

    @Test
    fun `pair returning empty string surfaces server-unreachable error not PairingRequired`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns ok("")

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
                ok("""{"ok":false,"error":"PIN required"}""")

            mgr.connect(server)
            scope.testScheduler.advanceUntilIdle()

            assertTrue("expected PairingRequired, got: $events", events.any { it is ConnectionEvent.PairingRequired })
        }

    @Test
    fun `pairWithPin returning empty string surfaces unreachable error rather than retrying`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), "1234") } returns ok("")

            mgr.pairWithPin(server, "1234")
            scope.testScheduler.advanceUntilIdle()

            assertTrue(events.any { it is ConnectionEvent.Error && it.message.contains("unreachable", ignoreCase = true) })
        }

    @Test
    fun `pairWithPin returning ok=false surfaces server's error message`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), "0000") } returns
                ok("""{"ok":false,"error":"Invalid PIN"}""")

            mgr.pairWithPin(server, "0000")
            scope.testScheduler.advanceUntilIdle()

            assertTrue(events.any { it is ConnectionEvent.Error && it.message.contains("Invalid PIN") })
        }

    @Test
    fun `connect skips pair when a pairing key is already stored`() =
        runMgrTest { mgr, _ ->
            every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns ok("")

            mgr.connect(server)
            scope.testScheduler.advanceUntilIdle()

            coVerify(exactly = 0) { discoveryRepo.pair(any(), any(), any(), any(), any()) }
            coVerify { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `session PUT carries the hmac proof and the full descriptor set`() =
        runMgrTest { mgr, _ ->
            val keyHex = "aa".repeat(32)
            every { store.satelliteSharedKey(serverId) } returns keyHex
            var sentProof: String? = null
            var sentDescriptors: String? = null
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                sentProof = arg(4)
                sentDescriptors = arg(5)
                ok("")
            }

            mgr.connect(server)
            scope.testScheduler.advanceUntilIdle()

            val expectedProof =
                com.tinkernorth.dish.core.net.SessionCrypto.hmacProof(
                    com.tinkernorth.dish.core.net
                        .hexToBytes(keyHex),
                    "test-device-id",
                )
            assertEquals(expectedProof, sentProof)
            assertEquals("[]", sentDescriptors) // no slots declared yet
        }

    @Test
    fun `successful session PUT derives a per-session key for the UDP channel`() =
        runMgrTest { mgr, _ ->
            val keyHex = "aa".repeat(32)
            every { store.satelliteSharedKey(serverId) } returns keyHex
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns
                ok(
                    """{"connectionId":"conn_1","token":"00000001","sessionSalt":"0102030405060708",""" +
                        """"epoch":1,"maxControllers":16,"protocolVersion":1,"controllers":[],""" +
                        """"hostFeatures":{"mouseControl":{"granted":false}}}""",
                )
            every { controllerRepo.openSocket(any(), any()) } returns 5
            val keySlot = io.mockk.slot<ByteArray>()
            every { controllerRepo.setConnectionParams(5, any(), capture(keySlot)) } returns Unit

            mgr.connect(server)
            // NOT advanceUntilIdle: the session is Live here, and its
            // self-rescheduling heartbeat poll makes "idle" unreachable.
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Live, mgr.get(serverId)?.state?.value)
            val pairingKey =
                com.tinkernorth.dish.core.net
                    .hexToBytes(keyHex)
            val expected =
                com.tinkernorth.dish.core.net.SessionCrypto.deriveSessionKey(
                    pairingKey,
                    com.tinkernorth.dish.core.net
                        .hexToBytes("0102030405060708"),
                    com.tinkernorth.dish.core.net
                        .hexToBytes("00000001"),
                )
            assertTrue(
                "UDP must be keyed with the HKDF session key, never the pairing key",
                expected.contentEquals(keySlot.captured) && !pairingKey.contentEquals(keySlot.captured),
            )
        }

    @Test
    fun `a coded 401 is terminal - key dropped, row stale, no retry loop`() =
        runMgrTest { mgr, events ->
            every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns reply(401, """{"error":"unauthorized","code":"NOT_PAIRED"}""")

            mgr.connect(server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.advanceUntilIdle()

            verify { store.forgetSatelliteSharedKey(serverId) }
            assertTrue(serverId in mgr.staleSatelliteIds.value)
            assertEquals(SatelliteSessionState.Idle, mgr.get(serverId)?.state?.value)
            // Terminal: exactly one PUT, no scheduled retry hammering the same dead key.
            coVerify(exactly = 1) {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            }
            assertTrue(events.none { it is ConnectionEvent.PairingRequired })
        }

    @Test
    fun `transport failure on a silent intent schedules a bounded backoff retry`() =
        runMgrTest { mgr, _ ->
            every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns ok("")

            mgr.connect(server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.advanceTimeBy(1100) // past the 1s first backoff
            scope.testScheduler.runCurrent()

            coVerify(atLeast = 2) {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            }

            // Terminate the retry chain (a coded 401 is terminal) so the
            // trailing advanceUntilIdle can drain instead of chasing backoffs.
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns reply(401, """{"error":"unauthorized","code":"NOT_PAIRED"}""")
        }

    @Test
    fun `transport failure on a user tap does NOT schedule a silent retry`() =
        runMgrTest { mgr, _ ->
            every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns ok("")

            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.advanceTimeBy(70_000)
            scope.testScheduler.runCurrent()

            coVerify(exactly = 1) {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `disconnect on unknown id is a no-op`() {
        val mgr = manager()
        mgr.disconnect("satellite:does-not-exist:0")
    }

    @Test
    fun `forget removes the connection from the live map`() =
        runMgrTest { mgr, _ ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns ok("")
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
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns ok("")

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
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns ok("")

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
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns ok("")

            mgr.connect(server)
            scope.testScheduler.advanceUntilIdle()

            assertTrue(events.any { it is ConnectionEvent.Error })
        }

    @Test
    fun `AUTO_RECONNECT marks Stale when server rejects empty PIN`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns
                ok("""{"ok":false,"error":"PIN required"}""")

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
                ok("""{"ok":false,"error":"PIN required"}""")

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
                ok("""{"ok":false}""")
            mgr.connect(server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.advanceUntilIdle()
            assertTrue(serverId in mgr.staleSatelliteIds.value)

            coEvery { discoveryRepo.pair(any(), any(), any(), any(), "1234") } returns
                ok("""{"ok":true,"sharedKey":"${"bb".repeat(32)}"}""")
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns ok("")
            every { store.satelliteSharedKey(serverId) } returns "bb".repeat(32)

            mgr.pairWithPin(server, "1234")
            scope.testScheduler.advanceUntilIdle()

            assertTrue(
                "stale should clear on successful PIN handshake",
                serverId !in mgr.staleSatelliteIds.value,
            )
        }

    @Test
    fun `forget self-unpairs on the satellite before dropping the key`() =
        runMgrTest { mgr, _ ->
            every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns ok("")
            mgr.connect(server)
            scope.testScheduler.advanceUntilIdle()

            mgr.forget(serverId)
            scope.testScheduler.advanceUntilIdle()

            coVerify { discoveryRepo.unpair(any(), any(), "test-device-id", any(), any()) }
            verify { store.forgetSatellite(serverId) }
            assertNull(mgr.connections.value[serverId])
        }

    @Test
    fun `forget clears Stale marker`() =
        runMgrTest { mgr, _ ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns ok("""{"ok":false}""")
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
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns ok("")
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

    @Test
    fun `forget cancels an in-flight reverse-pairing poll`() =
        runMgrTest { mgr, _ ->
            // Pair accepts the request (no immediate key), then status stays pending.
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any(), any()) } returns
                ok("""{"status":"pending"}""")
            var pollCount = 0
            coEvery { discoveryRepo.pairStatus(any(), any(), any()) } coAnswers {
                pollCount++
                ok("""{"status":"pending"}""")
            }

            mgr.requestApproval(server, "4242")
            // Let a couple of poll rounds run so we know the loop is live before we forget.
            scope.testScheduler.advanceTimeBy(5000)
            scope.testScheduler.runCurrent()
            assertTrue("poll should have run at least once before forget", pollCount > 0)

            mgr.forget(serverId)
            val afterForget = pollCount
            // Advance well past the 2-minute timeout: a leaked poll would keep calling pairStatus.
            scope.testScheduler.advanceUntilIdle()

            assertEquals("no pairStatus calls after forget", afterForget, pollCount)
            coVerify(exactly = 0) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { controllerRepo.openSocket(any(), any()) }
        }

    @Test
    fun `disconnect cancels an in-flight reverse-pairing poll`() =
        runMgrTest { mgr, _ ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any(), any()) } returns
                ok("""{"status":"pending"}""")
            var pollCount = 0
            coEvery { discoveryRepo.pairStatus(any(), any(), any()) } coAnswers {
                pollCount++
                ok("""{"status":"pending"}""")
            }

            mgr.requestApproval(server, "4242")
            scope.testScheduler.advanceTimeBy(5000)
            scope.testScheduler.runCurrent()
            assertTrue("poll should have run at least once before disconnect", pollCount > 0)

            mgr.disconnect(serverId)
            val afterDisconnect = pollCount
            scope.testScheduler.advanceUntilIdle()

            assertEquals("no pairStatus calls after disconnect", afterDisconnect, pollCount)
            coVerify(exactly = 0) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { controllerRepo.openSocket(any(), any()) }
        }

    @Test
    fun `malformed (non-hex) server token degrades gracefully without crashing`() =
        runMgrTest { mgr, _ ->
            every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
            // Auth-shape OK (connectionId + token present) but the token is non-hex,
            // so hexToBytes throws. The session must fail closed, not crash the coroutine.
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns ok("""{"connectionId":"c1","token":"zzzz","sessionSalt":"0102030405060708"}""")

            mgr.connect(server)
            scope.testScheduler.advanceUntilIdle()

            assertEquals(SatelliteSessionState.Idle, mgr.get(serverId)?.state?.value)
            verify(exactly = 0) { controllerRepo.openSocket(any(), any()) }
        }

    @Test
    fun `connect to a public ip is refused before any socket is opened`() =
        runMgrTest { mgr, _ ->
            val publicServer = server.copy(ip = "8.8.8.8")
            val publicId = SatelliteConnection.idFor(publicServer)
            // Stored key routes both straight to openSession (the IP choke point).
            every { store.satelliteSharedKey(publicId) } returns "aa".repeat(32)
            every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
            coEvery { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any()) } returns ok("")

            mgr.connect(publicServer)
            scope.testScheduler.advanceUntilIdle()

            // Public address: rejected before contacting the host or opening a socket.
            coVerify(exactly = 0) { discoveryRepo.putSession("8.8.8.8", any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { controllerRepo.openSocket("8.8.8.8", any()) }
            assertEquals(SatelliteSessionState.Idle, mgr.get(publicId)?.state?.value)

            // Private address: passes the guard and proceeds to the connect call.
            mgr.connect(server)
            scope.testScheduler.advanceUntilIdle()
            coVerify(exactly = 1) { discoveryRepo.putSession("10.0.0.5", any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `benign epoch drift reconciles by adopting the epoch, no session re-PUT`() =
        runMgrTest { mgr, _ ->
            every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns
                ok(
                    """{"connectionId":"conn_1","token":"00000001","sessionSalt":"0102030405060708",""" +
                        """"epoch":1,"controllers":[],"hostFeatures":{"mouseControl":{"granted":false}}}""",
                )
            every { controllerRepo.openSocket(any(), any()) } returns 5
            coEvery {
                discoveryRepo.putController(any(), any(), any(), any(), any(), any(), any())
            } returns
                ok(
                    """{"epoch":2,"controller":{"ctrlIdx":0,"result":"ok","appliedType":1,""" +
                        """"motion":{"sinkSupportedForType":false,"backendOk":true}}}""",
                )
            coEvery { discoveryRepo.getSession(any(), any(), any(), any(), any()) } returns
                ok(
                    """{"connectionId":"conn_1","epoch":9,"controllers":""" +
                        """[{"ctrlIdx":0,"active":true,"appliedType":1,"touchpadMode":"off"}],""" +
                        """"hostFeatures":{"mouseControl":{"granted":false}}}""",
                )

            mgr.connect(server)
            scope.testScheduler.runCurrent()
            val conn = mgr.get(serverId)!!
            conn.attachSlot("slot-1", controllerType = 1)
            scope.testScheduler.runCurrent()

            // Ack says epoch 9 but the applied state still matches the desired
            // set. Adopt the epoch instead of churning token/keys with a re-PUT.
            every { controllerRepo.getServerEpoch(any()) } returns 9
            every { controllerRepo.getActiveBitmap(any()) } returns 0b1
            scope.testScheduler.advanceTimeBy(1100)
            scope.testScheduler.runCurrent()

            assertEquals(9, conn.lastAppliedEpoch)
            assertEquals(SatelliteSessionState.Live, conn.state.value)
            coVerify(exactly = 1) { discoveryRepo.getSession(any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `an unpaired close-notify is terminal - key dropped, row stale, no silent retry`() =
        runMgrTest { mgr, _ ->
            every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns
                ok(
                    """{"connectionId":"conn_1","token":"00000001","sessionSalt":"0102030405060708",""" +
                        """"epoch":1,"controllers":[],"hostFeatures":{"mouseControl":{"granted":false}}}""",
                )
            every { controllerRepo.openSocket(any(), any()) } returns 5
            var closeReason = -1
            every { controllerRepo.getSessionCloseReason(any()) } answers { closeReason }

            mgr.connect(server)
            scope.testScheduler.runCurrent()
            assertEquals(SatelliteSessionState.Live, mgr.get(serverId)?.state?.value)

            closeReason = SatelliteConnection.CLOSE_REASON_UNPAIRED
            scope.testScheduler.advanceTimeBy(1100) // one alive-poll tick, not the 5-miss window
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Idle, mgr.get(serverId)?.state?.value)
            verify { store.forgetSatelliteSharedKey(serverId) }
            assertTrue(serverId in mgr.staleSatelliteIds.value)

            // Terminal: nothing rides the backoff curve afterwards.
            scope.testScheduler.advanceTimeBy(120_000)
            scope.testScheduler.runCurrent()
            coVerify(exactly = 1) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `toggling a slot to mouse mid-session escalates syncSlot to a full session re-PUT`() =
        runMgrTest { mgr, _ ->
            every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
            val mouseRequests = mutableListOf<Boolean>()
            val sentDescriptors = mutableListOf<String>()
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                sentDescriptors += arg<String>(5)
                mouseRequests += arg<Boolean>(6)
                // The grant is computed ONLY here, and this server keeps denying it.
                ok(
                    """{"connectionId":"conn_1","token":"00000001","sessionSalt":"0102030405060708",""" +
                        """"epoch":1,"controllers":[{"ctrlIdx":0,"result":"ok","appliedType":1,""" +
                        """"motion":{"sinkSupportedForType":false,"backendOk":true}}],""" +
                        """"hostFeatures":{"mouseControl":{"granted":false}}}""",
                )
            }
            every { controllerRepo.openSocket(any(), any()) } returns 5
            // The controller PUT applies the descriptor but can never deliver the grant.
            coEvery {
                discoveryRepo.putController(any(), any(), any(), any(), any(), any(), any())
            } returns
                ok(
                    """{"epoch":2,"controller":{"ctrlIdx":0,"result":"ok","appliedType":1,""" +
                        """"motion":{"sinkSupportedForType":false,"backendOk":true}}}""",
                )
            coEvery { discoveryRepo.getSession(any(), any(), any(), any(), any()) } returns
                ok(
                    """{"connectionId":"conn_1","epoch":2,"controllers":""" +
                        """[{"ctrlIdx":0,"active":true,"appliedType":1,"touchpadMode":"ds4"}],""" +
                        """"hostFeatures":{"mouseControl":{"granted":false}}}""",
                )

            mgr.connect(server)
            scope.testScheduler.runCurrent()
            val conn = mgr.get(serverId)!!
            assertEquals(SatelliteSessionState.Live, conn.state.value)

            // A non-mouse slot change converges via the controller route alone.
            every { capabilityComposer.touchpadWireMode("slot-1") } returns "ds4"
            conn.attachSlot("slot-1", controllerType = 1)
            scope.testScheduler.runCurrent()
            coVerify(exactly = 1) { discoveryRepo.putController(any(), any(), any(), any(), any(), any(), any()) }
            assertEquals(listOf(false), mouseRequests)

            // The pick moving to mouse converges the way production does: the wire projection
            // changes and refreshCapsIfChanged re-syncs the slot.
            every { capabilityComposer.touchpadWireMode("slot-1") } returns "mouse"
            conn.refreshCapsIfChanged()
            scope.testScheduler.runCurrent()

            // wants ≠ granted after the controller PUT → reconcile GET → applied view
            // lacks the grant → full session re-PUT carrying the request.
            coVerify(exactly = 1) { discoveryRepo.getSession(any(), any(), any(), any(), any()) }
            assertEquals(listOf(false, true), mouseRequests)
            assertTrue(
                "the re-PUT must carry the mouse descriptor: ${sentDescriptors.last()}",
                sentDescriptors.last().contains("\"touchpadMode\":\"mouse\""),
            )
        }

    @Test
    fun `a transport failure with an error body does not pop the PIN dialog`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns unreachable()

            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.advanceUntilIdle()

            assertTrue(events.none { it is ConnectionEvent.PairingRequired })
            assertTrue(
                "expected unreachable Error, got: $events",
                events.any {
                    it is ConnectionEvent.Error && it.message.contains("unreachable", ignoreCase = true)
                },
            )
        }

    @Test
    fun `a transport failure on auto-reconnect does not mark the row stale`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns unreachable()

            mgr.connect(server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.advanceUntilIdle()

            assertTrue(
                "an unreachable box must not read as needs-pairing",
                serverId !in mgr.staleSatelliteIds.value,
            )
            assertTrue(events.isEmpty())
        }

    @Test
    fun `pair 409 surfaces a protocol mismatch instead of the PIN dialog`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns
                reply(409, """{"error":"protocol version unsupported","supported":2}""")

            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.advanceUntilIdle()

            assertTrue(events.none { it is ConnectionEvent.PairingRequired })
            assertTrue(
                events.any { it is ConnectionEvent.Error && it.message.contains("protocol", ignoreCase = true) },
            )
            assertTrue(serverId !in mgr.staleSatelliteIds.value)
        }

    @Test
    fun `session PUT 409 on a user tap explains the version mismatch`() =
        runMgrTest { mgr, events ->
            every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns reply(409, """{"error":"protocol version unsupported","supported":2}""")

            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.advanceUntilIdle()

            assertTrue(
                events.any { it is ConnectionEvent.Error && it.message.contains("protocol", ignoreCase = true) },
            )
        }

    @Test
    fun `session PUT 409 does not ride the silent retry curve`() =
        runMgrTest { mgr, events ->
            every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns reply(409, """{"error":"protocol version unsupported","supported":2}""")

            mgr.connect(server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.advanceTimeBy(120_000)
            scope.testScheduler.runCurrent()

            coVerify(exactly = 1) {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            }
            assertTrue(events.isEmpty())
        }

    @Test
    fun `a cert pin mismatch surfaces identity-changed and keeps the pairing key`() =
        runMgrTest { mgr, events ->
            every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns identityMismatch()

            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.advanceUntilIdle()

            assertTrue(
                "expected identity-changed Error, got: $events",
                events.any { it is ConnectionEvent.Error && it.message.contains("identity", ignoreCase = true) },
            )
            verify(exactly = 0) { store.forgetSatelliteSharedKey(any()) }
            assertTrue(serverId !in mgr.staleSatelliteIds.value)
        }

    @Test
    fun `a cert pin mismatch does not schedule a silent retry`() =
        runMgrTest { mgr, _ ->
            every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns identityMismatch()

            mgr.connect(server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.advanceTimeBy(120_000)
            scope.testScheduler.runCurrent()

            coVerify(exactly = 1) {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `pairWithPin against a changed identity explains it instead of failing generically`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), "1234") } returns identityMismatch()

            mgr.pairWithPin(server, "1234")
            scope.testScheduler.advanceUntilIdle()

            assertTrue(
                events.any { it is ConnectionEvent.Error && it.message.contains("identity", ignoreCase = true) },
            )
        }

    @Test
    fun `a scan hands the results to the store so known rows re-point`() =
        runMgrTest { mgr, _ ->
            val moved = server.copy(machineId = "m1", ip = "10.0.0.99")
            coEvery { discoveryRepo.discoverServers(any(), any()) } returns listOf(moved)

            mgr.startDiscovery()
            scope.testScheduler.advanceUntilIdle()

            verify { store.refreshFromDiscovery(listOf(moved)) }
            assertEquals(listOf(moved), mgr.discoveredServers.value)
        }

    @Test
    fun `a scan re-points an idle connection at the discovered address`() =
        runMgrTest { mgr, _ ->
            val midServer = server.copy(machineId = "m1")
            val midId = SatelliteConnection.idFor(midServer)
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns unreachable()
            mgr.connect(midServer, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.advanceUntilIdle()
            assertEquals(
                "10.0.0.5",
                mgr
                    .get(midId)
                    ?.server
                    ?.value
                    ?.ip,
            )

            coEvery { discoveryRepo.discoverServers(any(), any()) } returns
                listOf(midServer.copy(ip = "10.0.0.99"))
            mgr.startDiscovery()
            scope.testScheduler.advanceUntilIdle()

            assertEquals(
                "10.0.0.99",
                mgr
                    .get(midId)
                    ?.server
                    ?.value
                    ?.ip,
            )
        }

    @Test
    fun `a scan leaves a live session's address alone`() =
        runMgrTest { mgr, _ ->
            val midServer = server.copy(machineId = "m1")
            val midId = SatelliteConnection.idFor(midServer)
            every { store.satelliteSharedKey(midId) } returns "aa".repeat(32)
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns
                ok(
                    """{"connectionId":"conn_1","token":"00000001","sessionSalt":"0102030405060708",""" +
                        """"epoch":1,"controllers":[],"hostFeatures":{"mouseControl":{"granted":false}}}""",
                )
            every { controllerRepo.openSocket(any(), any()) } returns 5

            mgr.connect(midServer)
            scope.testScheduler.runCurrent()
            assertEquals(SatelliteSessionState.Live, mgr.get(midId)?.state?.value)

            coEvery { discoveryRepo.discoverServers(any(), any()) } returns
                listOf(midServer.copy(ip = "10.0.0.99"))
            mgr.startDiscovery()
            scope.testScheduler.runCurrent()

            assertEquals(
                "10.0.0.5",
                mgr
                    .get(midId)
                    ?.server
                    ?.value
                    ?.ip,
            )
        }

    @Test
    fun `a silent retry dials the address remembered at fire time`() =
        runMgrTest { mgr, _ ->
            val midServer = server.copy(machineId = "m1")
            val midId = SatelliteConnection.idFor(midServer)
            every { store.satelliteSharedKey(midId) } returns "aa".repeat(32)
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns unreachable()

            mgr.connect(midServer, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.runCurrent()
            coVerify { discoveryRepo.putSession("10.0.0.5", any(), any(), any(), any(), any(), any()) }

            every { store.remembered() } returns
                listOf(
                    RememberedSatellite(
                        id = midId,
                        name = "Pc",
                        ip = "10.0.0.99",
                        udpPort = 9876,
                        pairPort = 9878,
                        httpPort = 9877,
                        machineId = "m1",
                    ),
                )
            scope.testScheduler.advanceTimeBy(1100)
            scope.testScheduler.runCurrent()

            coVerify { discoveryRepo.putSession("10.0.0.99", any(), any(), any(), any(), any(), any()) }

            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns reply(401, """{"error":"unauthorized","code":"NOT_PAIRED"}""")
        }

    @Test
    fun `a slot attached while the session PUT is in flight converges right after connect`() =
        runMgrTest { mgr, _ ->
            every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                gate.await()
                ok(
                    """{"connectionId":"conn_1","token":"00000001","sessionSalt":"0102030405060708",""" +
                        """"epoch":1,"controllers":[],"hostFeatures":{"mouseControl":{"granted":false}}}""",
                )
            }
            every { controllerRepo.openSocket(any(), any()) } returns 5
            coEvery {
                discoveryRepo.putController(any(), any(), any(), any(), any(), any(), any())
            } returns
                ok(
                    """{"epoch":2,"controller":{"ctrlIdx":0,"result":"ok","appliedType":1,""" +
                        """"motion":{"sinkSupportedForType":false,"backendOk":true}}}""",
                )

            mgr.connect(server)
            scope.testScheduler.runCurrent()
            val conn = mgr.get(serverId)!!
            conn.attachSlot("slot-1", controllerType = 1)
            scope.testScheduler.runCurrent()
            coVerify(exactly = 0) {
                discoveryRepo.putController(any(), any(), any(), any(), any(), any(), any())
            }

            gate.complete(Unit)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Live, conn.state.value)
            coVerify(exactly = 1) {
                discoveryRepo.putController(any(), any(), any(), eq(0), any(), any(), any())
            }
            assertTrue(
                "the late-attached slot must register through the post-connect converge",
                conn.slots.value
                    .getValue("slot-1")
                    .registered,
            )
        }

    @Test
    fun `a slot detached while the session PUT is in flight is deleted right after connect`() =
        runMgrTest { mgr, _ ->
            every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns
                ok(
                    """{"connectionId":"conn_1","token":"00000001","sessionSalt":"0102030405060708",""" +
                        """"epoch":1,"controllers":[],"hostFeatures":{"mouseControl":{"granted":false}}}""",
                )
            every { controllerRepo.openSocket(any(), any()) } returns 5
            coEvery {
                discoveryRepo.putController(any(), any(), any(), any(), any(), any(), any())
            } returns
                ok(
                    """{"epoch":2,"controller":{"ctrlIdx":0,"result":"ok","appliedType":1,""" +
                        """"motion":{"sinkSupportedForType":false,"backendOk":true}}}""",
                )
            coEvery {
                discoveryRepo.deleteController(any(), any(), any(), any(), any(), any())
            } returns ok("""{"epoch":3}""")

            mgr.connect(server)
            scope.testScheduler.runCurrent()
            val conn = mgr.get(serverId)!!
            conn.attachSlot("slot-1", controllerType = 1)
            scope.testScheduler.runCurrent()
            assertTrue(
                conn.slots.value
                    .getValue("slot-1")
                    .registered,
            )

            mgr.disconnect(serverId)
            scope.testScheduler.runCurrent()

            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                gate.await()
                ok(
                    """{"connectionId":"conn_2","token":"00000002","sessionSalt":"0102030405060708",""" +
                        """"epoch":4,"controllers":[{"ctrlIdx":0,"result":"ok","appliedType":1,""" +
                        """"motion":{"sinkSupportedForType":false,"backendOk":true}}],""" +
                        """"hostFeatures":{"mouseControl":{"granted":false}}}""",
                )
            }
            mgr.connect(server)
            scope.testScheduler.runCurrent()
            conn.detachSlot("slot-1")
            scope.testScheduler.runCurrent()
            coVerify(exactly = 0) {
                discoveryRepo.deleteController(any(), any(), any(), any(), any(), any())
            }

            gate.complete(Unit)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Live, conn.state.value)
            coVerify(exactly = 1) {
                discoveryRepo.deleteController(any(), any(), "conn_2", eq(0), any(), any())
            }
        }

    @Test
    fun `no slot changes during the session PUT means no extra controller calls`() =
        runMgrTest { mgr, _ ->
            every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                gate.await()
                ok(
                    """{"connectionId":"conn_1","token":"00000001","sessionSalt":"0102030405060708",""" +
                        """"epoch":1,"controllers":[],"hostFeatures":{"mouseControl":{"granted":false}}}""",
                )
            }
            every { controllerRepo.openSocket(any(), any()) } returns 5

            mgr.connect(server)
            scope.testScheduler.runCurrent()
            gate.complete(Unit)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Live, mgr.get(serverId)?.state?.value)
            coVerify(exactly = 0) {
                discoveryRepo.putController(any(), any(), any(), any(), any(), any(), any())
            }
            coVerify(exactly = 0) {
                discoveryRepo.deleteController(any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `requestApproval against an unreachable box reports unreachable`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any(), any()) } returns unreachable()

            mgr.requestApproval(server, "4242")
            scope.testScheduler.advanceUntilIdle()

            assertTrue(
                events.any {
                    it is ConnectionEvent.Error && it.message.contains("unreachable", ignoreCase = true)
                },
            )
        }

    @Test
    fun `an unreachable status poll counts as pending not a refusal`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any(), any()) } returns
                ok("""{"status":"pending"}""")
            var polls = 0
            coEvery { discoveryRepo.pairStatus(any(), any(), any()) } coAnswers {
                polls++
                if (polls == 1) {
                    unreachable()
                } else {
                    ok("""{"ok":true,"status":"approved","sharedKey":"${"cc".repeat(32)}"}""")
                }
            }
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any())
            } returns ok("")

            mgr.requestApproval(server, "4242")
            scope.testScheduler.advanceTimeBy(5000)
            scope.testScheduler.runCurrent()

            assertTrue(
                events.none {
                    it is ConnectionEvent.Error && it.message.contains("declined", ignoreCase = true)
                },
            )
            verify { store.setSatelliteSharedKey(serverId, "cc".repeat(32)) }
        }
}
