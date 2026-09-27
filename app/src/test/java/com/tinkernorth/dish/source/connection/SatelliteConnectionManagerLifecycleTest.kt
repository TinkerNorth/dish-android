// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection

import com.tinkernorth.dish.core.net.DISH_PROTOCOL_CURRENT
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SatelliteConnectionManagerLifecycleTest : SatelliteConnectionManagerFixture() {
    private fun sessionGrantBody(): String =
        """{"connectionId":"conn_1","token":"00000001","sessionSalt":"0102030405060708",""" +
            """"epoch":1,"protocolVersion":2,"controllers":[],"hostFeatures":{"mouseControl":{"granted":false}}}"""

    private fun stubStoredKey() {
        every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
    }

    // A satellite that grants every session PUT and a socket that opens: the shortest road to Live.
    private fun stubLiveSession() {
        stubStoredKey()
        coEvery {
            discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ok(sessionGrantBody())
        every { controllerRepo.openSocket(any(), any()) } returns 5
    }

    private fun connectLive(mgr: SatelliteConnectionManager) {
        mgr.connect(server)
        scope.testScheduler.runCurrent()
        assertEquals(SatelliteSessionState.Live, mgr.get(serverId)?.state?.value)
    }

    private fun closeNotifyRetries(reason: Int) =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            var closeReason = -1
            every { controllerRepo.getSessionCloseReason(any()) } answers { closeReason }
            connectLive(mgr)

            closeReason = reason
            scope.testScheduler.advanceTimeBy(1100)
            scope.testScheduler.runCurrent()
            assertEquals(SatelliteSessionState.Idle, mgr.get(serverId)?.state?.value)

            // The first silent retry fires one second after the close.
            closeReason = -1
            scope.testScheduler.advanceTimeBy(1100)
            scope.testScheduler.runCurrent()

            coVerify(exactly = 2) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
            assertEquals(SatelliteSessionState.Live, mgr.get(serverId)?.state?.value)
            verify(exactly = 0) { store.forgetSatelliteSharedKey(serverId) }
            endRetryChain()
        }

    @Test
    fun `a kicked close-notify retries on the backoff curve`() = closeNotifyRetries(SatelliteConnection.CLOSE_REASON_KICKED)

    @Test
    fun `a shutdown close-notify retries on the backoff curve`() = closeNotifyRetries(SatelliteConnection.CLOSE_REASON_SHUTDOWN)

    @Test
    fun `a replaced close-notify stays down without a retry`() =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            var closeReason = -1
            every { controllerRepo.getSessionCloseReason(any()) } answers { closeReason }
            connectLive(mgr)

            closeReason = SatelliteConnection.CLOSE_REASON_REPLACED
            scope.testScheduler.advanceTimeBy(1100)
            scope.testScheduler.runCurrent()
            assertEquals(SatelliteSessionState.Idle, mgr.get(serverId)?.state?.value)

            scope.testScheduler.advanceTimeBy(120_000)
            scope.testScheduler.runCurrent()

            coVerify(exactly = 1) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
            assertEquals(SatelliteSessionState.Idle, mgr.get(serverId)?.state?.value)
            verify(exactly = 0) { store.forgetSatelliteSharedKey(serverId) }
            assertFalse(serverId in mgr.staleSatelliteIds.value)
        }

    @Test
    fun `a socket that will not open settles idle without an error or retry`() =
        runMgrTest { mgr, events ->
            stubStoredKey()
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
            } returns ok(sessionGrantBody())
            every { controllerRepo.openSocket(any(), any()) } returns -1

            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.advanceTimeBy(120_000)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Idle, mgr.get(serverId)?.state?.value)
            assertTrue("a failed socket is silent today: $events", events.isEmpty())
            coVerify(exactly = 1) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { controllerRepo.setConnectionParams(any(), any(), any(), any()) }
        }

    @Test
    fun `a declined approval reports the decline and settles idle`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any(), any()) } returns ok("""{"status":"pending"}""")
            coEvery { discoveryRepo.pairStatus(any(), any(), any()) } returns ok("""{"status":"denied"}""")

            mgr.requestApproval(server, "4242")
            scope.testScheduler.advanceTimeBy(2100)
            scope.testScheduler.runCurrent()

            assertTrue(events.any { it is ConnectionEvent.Error && it.message == SatelliteConnectionManager.APPROVAL_DECLINED_MSG })
            assertEquals(SatelliteSessionState.Idle, mgr.get(serverId)?.state?.value)

            scope.testScheduler.advanceTimeBy(10_000)
            scope.testScheduler.runCurrent()
            coVerify(exactly = 1) { discoveryRepo.pairStatus(any(), any(), any()) }
        }

    @Test
    fun `an approval nobody answers times out after two minutes`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any(), any()) } returns ok("""{"status":"pending"}""")
            coEvery { discoveryRepo.pairStatus(any(), any(), any()) } returns ok("""{"status":"pending"}""")

            mgr.requestApproval(server, "4242")
            scope.testScheduler.advanceTimeBy(119_000)
            scope.testScheduler.runCurrent()
            assertTrue(events.none { it is ConnectionEvent.Error })
            assertEquals(SatelliteSessionState.Linking, mgr.get(serverId)?.state?.value)

            scope.testScheduler.advanceTimeBy(2000)
            scope.testScheduler.runCurrent()

            assertTrue(events.any { it is ConnectionEvent.Error && it.message == SatelliteConnectionManager.APPROVAL_TIMEOUT_MSG })
            assertEquals(SatelliteSessionState.Idle, mgr.get(serverId)?.state?.value)
        }

    @Test
    fun `a pin pair that lands during the poll makes the poll bail without tearing it down`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any(), any()) } returns ok("""{"status":"pending"}""")
            coEvery { discoveryRepo.pairStatus(any(), any(), any()) } returns ok("""{"status":"pending"}""")
            mgr.requestApproval(server, "4242")
            scope.testScheduler.advanceTimeBy(2100)
            scope.testScheduler.runCurrent()

            coEvery { discoveryRepo.pair(any(), any(), any(), any(), "1234") } returns
                ok("""{"ok":true,"sharedKey":"${"bb".repeat(32)}"}""")
            every { store.satelliteSharedKey(serverId) } returns "bb".repeat(32)
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
            } returns ok(sessionGrantBody())
            every { controllerRepo.openSocket(any(), any()) } returns 5
            mgr.pairWithPin(server, "1234")
            scope.testScheduler.runCurrent()
            assertEquals(SatelliteSessionState.Live, mgr.get(serverId)?.state?.value)

            scope.testScheduler.advanceTimeBy(130_000)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Live, mgr.get(serverId)?.state?.value)
            assertTrue("the poll must neither decline nor time out a session the PIN built: $events", events.isEmpty())
        }

    @Test
    fun `a second approval request supersedes the first poll`() =
        runMgrTest { mgr, _ ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any(), any()) } returns ok("""{"status":"pending"}""")
            var polls = 0
            coEvery { discoveryRepo.pairStatus(any(), any(), any()) } coAnswers {
                polls++
                ok("""{"status":"pending"}""")
            }
            mgr.requestApproval(server, "1111")
            scope.testScheduler.advanceTimeBy(2100)
            scope.testScheduler.runCurrent()
            assertEquals(1, polls)

            mgr.requestApproval(server, "2222")
            scope.testScheduler.advanceTimeBy(2100)
            scope.testScheduler.runCurrent()

            // The superseded poll would have fired a third time at its own cadence.
            assertEquals(2, polls)
            coVerify(exactly = 2) { discoveryRepo.pair(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a silent retry is dropped when the row went stale meanwhile`() =
        runMgrTest { mgr, _ ->
            stubStoredKey()
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
            } returns unreachable()
            mgr.connect(server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.runCurrent()

            // Before the 1 s retry fires, a user tap learns the satellite no longer knows us.
            endRetryChain()
            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.runCurrent()
            assertTrue(serverId in mgr.staleSatelliteIds.value)

            scope.testScheduler.advanceTimeBy(5000)
            scope.testScheduler.runCurrent()

            coVerify(exactly = 2) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a silent retry is dropped when the connection is no longer idle meanwhile`() =
        runMgrTest { mgr, _ ->
            stubStoredKey()
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
            } returns unreachable()
            mgr.connect(server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.runCurrent()

            // A user tap is mid-handshake when the 1 s retry fires.
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
            } coAnswers { awaitCancellation() }
            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.runCurrent()
            assertEquals(SatelliteSessionState.Linking, mgr.get(serverId)?.state?.value)

            scope.testScheduler.advanceTimeBy(5000)
            scope.testScheduler.runCurrent()

            coVerify(exactly = 2) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `the backoff doubles per attempt and caps at sixty seconds`() =
        runMgrTest { mgr, _ ->
            stubStoredKey()
            val putAtMs = mutableListOf<Long>()
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                putAtMs += scope.testScheduler.currentTime
                unreachable()
            }

            mgr.connect(server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.advanceTimeBy(183_500)
            scope.testScheduler.runCurrent()

            assertEquals(listOf(0L, 1000L, 3000L, 7000L, 15_000L, 31_000L, 63_000L, 123_000L, 183_000L), putAtMs)
            endRetryChain()
        }

    // The tap itself dials at once and schedules nothing; the silent retry already pending from the
    // curve still fires, and its failure restarts the curve at one second instead of eight.
    @Test
    fun `a user tap resets the backoff curve`() =
        runMgrTest { mgr, _ ->
            stubStoredKey()
            val putAtMs = mutableListOf<Long>()
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                putAtMs += scope.testScheduler.currentTime
                unreachable()
            }
            mgr.connect(server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.advanceTimeBy(3500)
            scope.testScheduler.runCurrent()
            assertEquals(listOf(0L, 1000L, 3000L), putAtMs)

            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.runCurrent()
            assertEquals(listOf(0L, 1000L, 3000L, 3500L), putAtMs)

            scope.testScheduler.advanceTimeBy(7500)
            scope.testScheduler.runCurrent()
            assertEquals(listOf(0L, 1000L, 3000L, 3500L, 7000L, 8000L, 10_000L), putAtMs)
            endRetryChain()
        }

    private fun stubSlotApplies() {
        coEvery {
            discoveryRepo.putController(any(), any(), any(), any(), any(), any(), any())
        } returns
            ok(
                """{"epoch":2,"controller":{"ctrlIdx":0,"result":"ok","appliedType":1,""" +
                    """"motion":{"sinkSupportedForType":false,"backendOk":true}}}""",
            )
    }

    private fun matchingViewBody(epoch: Int): String =
        """{"connectionId":"conn_1","epoch":$epoch,"controllers":""" +
            """[{"ctrlIdx":0,"active":true,"appliedType":1,"touchpadMode":"off"}],""" +
            """"hostFeatures":{"mouseControl":{"granted":false}}}"""

    @Test
    fun `a second reconcile while one is in flight is dropped`() =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            stubSlotApplies()
            val gate = CompletableDeferred<Unit>()
            coEvery { discoveryRepo.getSession(any(), any(), any(), any(), any()) } coAnswers {
                gate.await()
                ok(matchingViewBody(epoch = 9))
            }
            connectLive(mgr)
            val conn = mgr.get(serverId)!!
            conn.attachSlot("slot-1", controllerType = 1)
            scope.testScheduler.runCurrent()

            // Two heartbeat ticks report the drift while the first GET is still out.
            every { controllerRepo.getServerEpoch(any()) } returns 9
            every { controllerRepo.getActiveBitmap(any()) } returns 0b1
            scope.testScheduler.advanceTimeBy(2100)
            scope.testScheduler.runCurrent()
            coVerify(exactly = 1) { discoveryRepo.getSession(any(), any(), any(), any(), any()) }

            gate.complete(Unit)
            scope.testScheduler.runCurrent()
            assertEquals(9, conn.lastAppliedEpoch)
            assertEquals(SatelliteSessionState.Live, conn.state.value)
        }

    @Test
    fun `a reconcile view that rejects our proof drops the key and marks stale`() =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            coEvery { discoveryRepo.getSession(any(), any(), any(), any(), any()) } returns
                reply(401, """{"error":"unauthorized","code":"BAD_PROOF"}""")
            connectLive(mgr)

            every { controllerRepo.getServerEpoch(any()) } returns 9
            scope.testScheduler.advanceTimeBy(1100)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Idle, mgr.get(serverId)?.state?.value)
            verify { store.forgetSatelliteSharedKey(serverId) }
            assertTrue(serverId in mgr.staleSatelliteIds.value)
            scope.testScheduler.advanceTimeBy(120_000)
            scope.testScheduler.runCurrent()
            coVerify(exactly = 1) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a reconcile view for another session re-PUTs the whole topology`() =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            coEvery { discoveryRepo.getSession(any(), any(), any(), any(), any()) } returns
                ok(matchingViewBody(epoch = 9).replace("conn_1", "conn_other"))
            connectLive(mgr)

            every { controllerRepo.getServerEpoch(any()) } returns 9
            scope.testScheduler.advanceTimeBy(1100)
            scope.testScheduler.runCurrent()

            coVerify(exactly = 2) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
            assertEquals(SatelliteSessionState.Live, mgr.get(serverId)?.state?.value)
        }

    @Test
    fun `a malformed stored key forgets it and asks for a re-pair`() =
        runMgrTest { mgr, events ->
            every { store.satelliteSharedKey(serverId) } returns "abc"

            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.advanceUntilIdle()

            verify { store.forgetSatelliteSharedKey(serverId) }
            assertTrue(serverId in mgr.staleSatelliteIds.value)
            assertTrue(events.any { it is ConnectionEvent.Error && it.message == SatelliteConnectionManager.REPAIR_NEEDED_MSG })
            coVerify(exactly = 0) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a malformed stored key on a silent connect marks the row stale without a banner`() =
        runMgrTest { mgr, events ->
            every { store.satelliteSharedKey(serverId) } returns "zz".repeat(32)

            mgr.connect(server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.advanceUntilIdle()

            assertTrue(serverId in mgr.staleSatelliteIds.value)
            assertTrue(events.isEmpty())
        }

    @Test
    fun `a session reply without a tuple tells the user and settles idle`() =
        runMgrTest { mgr, events ->
            stubStoredKey()
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
            } returns ok("""{"epoch":1,"error":"no free pad"}""")

            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.advanceUntilIdle()

            assertEquals(SatelliteSessionState.Idle, mgr.get(serverId)?.state?.value)
            assertTrue(events.any { it is ConnectionEvent.Error && it.message == "Error: no free pad" })
        }

    @Test
    fun `a session reply without a tuple fails and retries silently`() =
        runMgrTest { mgr, events ->
            stubStoredKey()
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
            } returns ok("""{"epoch":1}""")

            mgr.connect(server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.advanceTimeBy(1100)
            scope.testScheduler.runCurrent()

            coVerify(exactly = 2) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
            assertTrue(events.isEmpty())
            endRetryChain()
        }

    @Test
    fun `a slot sync against a dead session folds nothing`() =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            coEvery {
                discoveryRepo.putController(any(), any(), any(), any(), any(), any(), any())
            } returns reply(404, """{"epoch":5,"error":"connection not found"}""")
            connectLive(mgr)
            val conn = mgr.get(serverId)!!

            conn.attachSlot("slot-1", controllerType = 1)
            scope.testScheduler.runCurrent()

            assertEquals(1, conn.lastAppliedEpoch)
            assertEquals(false, conn.slots.value["slot-1"]?.registered)
            assertEquals(SatelliteSessionState.Live, conn.state.value)
        }

    @Test
    fun `a slot sync the satellite rejects drops the key`() =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            coEvery {
                discoveryRepo.putController(any(), any(), any(), any(), any(), any(), any())
            } returns reply(401, """{"error":"unauthorized","code":"NOT_PAIRED"}""")
            connectLive(mgr)
            val conn = mgr.get(serverId)!!

            conn.attachSlot("slot-1", controllerType = 1)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Idle, conn.state.value)
            verify { store.forgetSatelliteSharedKey(serverId) }
            assertTrue(serverId in mgr.staleSatelliteIds.value)
        }

    private fun attachAppliedSlot(mgr: SatelliteConnectionManager): SatelliteConnection {
        stubSlotApplies()
        connectLive(mgr)
        val conn = mgr.get(serverId)!!
        conn.attachSlot("slot-1", controllerType = 1)
        scope.testScheduler.runCurrent()
        assertEquals(2, conn.lastAppliedEpoch)
        return conn
    }

    @Test
    fun `a slot delete adopts the epoch the reply carries`() =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            coEvery {
                discoveryRepo.deleteController(any(), any(), any(), any(), any(), any())
            } returns ok("""{"epoch":7}""")
            val conn = attachAppliedSlot(mgr)

            conn.detachSlot("slot-1")
            scope.testScheduler.runCurrent()

            coVerify { discoveryRepo.deleteController(any(), any(), "conn_1", 0, any(), any()) }
            assertEquals(7, conn.lastAppliedEpoch)
        }

    @Test
    fun `a slot delete with an error body does not adopt the epoch`() =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            coEvery {
                discoveryRepo.deleteController(any(), any(), any(), any(), any(), any())
            } returns ok("""{"epoch":7,"error":"not found"}""")
            val conn = attachAppliedSlot(mgr)

            conn.detachSlot("slot-1")
            scope.testScheduler.runCurrent()

            assertEquals(2, conn.lastAppliedEpoch)
        }

    @Test
    fun `startDiscovery while scanning is ignored`() =
        runMgrTest { mgr, _ ->
            coEvery { discoveryRepo.discoverServers(any(), any()) } coAnswers { awaitCancellation() }

            mgr.startDiscovery()
            scope.testScheduler.runCurrent()
            mgr.startDiscovery()
            scope.testScheduler.runCurrent()

            coVerify(exactly = 1) { discoveryRepo.discoverServers(any(), any()) }
            assertTrue(mgr.isScanning.value)
        }

    @Test
    fun `a capability projection change refreshes every live connection's caps`() =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            attachAppliedSlot(mgr)
            coVerify(exactly = 1) { discoveryRepo.putController(any(), any(), any(), any(), any(), any(), any()) }

            every { capabilityComposer.wireCapsFor(any()) } returns
                (BASE_WIRE_CAPS or com.tinkernorth.dish.core.net.ControllerDescriptor.CAP_MOTION)
            wireProjection.value =
                mapOf(
                    "slot-1" to
                        com.tinkernorth.dish.composer
                            .WireProjection(BASE_WIRE_CAPS, "off"),
                )
            scope.testScheduler.runCurrent()

            coVerify(exactly = 2) { discoveryRepo.putController(any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a projection emission that moves nothing re-sends no descriptor`() =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            attachAppliedSlot(mgr)

            wireProjection.value =
                mapOf(
                    "slot-1" to
                        com.tinkernorth.dish.composer
                            .WireProjection(BASE_WIRE_CAPS, "off"),
                )
            scope.testScheduler.runCurrent()

            coVerify(exactly = 1) { discoveryRepo.putController(any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a user disconnect tells the satellite over REST`() =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            connectLive(mgr)

            mgr.disconnect(serverId)
            scope.testScheduler.runCurrent()

            coVerify { discoveryRepo.disconnect("10.0.0.5", 9877, "conn_1", "test-device-id", any()) }
            assertEquals(SatelliteSessionState.Idle, mgr.get(serverId)?.state?.value)
        }

    @Test
    fun `a disconnect with no live tuple sends nothing`() =
        runMgrTest { mgr, _ ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns ok("")
            mgr.connect(server)
            scope.testScheduler.advanceUntilIdle()

            mgr.disconnect(serverId)
            scope.testScheduler.runCurrent()

            coVerify(exactly = 0) { discoveryRepo.disconnect(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `forget drops the settled protocol version`() =
        runMgrTest { mgr, _ ->
            stubStoredKey()
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), eq(DISH_PROTOCOL_CURRENT))
            } returns reply(409, """{"error":"protocol version unsupported","supported":1}""")
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), eq(1))
            } returns ok(sessionGrantBody().replace(""""protocolVersion":2""", """"protocolVersion":1"""))
            every { controllerRepo.openSocket(any(), any()) } returns 5
            connectLive(mgr)

            // The coordinator clears the host facts alongside; the manager must not keep its own copy.
            mgr.forget(serverId)
            hostFeaturesStore.clearConnection(serverId)
            scope.testScheduler.runCurrent()
            mgr.connect(server)
            scope.testScheduler.runCurrent()

            coVerify(exactly = 2) {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), eq(DISH_PROTOCOL_CURRENT))
            }
        }

    @Test
    fun `pairWithPin on a live session is ignored`() =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            connectLive(mgr)

            mgr.pairWithPin(server, "1234")
            scope.testScheduler.runCurrent()

            coVerify(exactly = 0) { discoveryRepo.pair(any(), any(), any(), any(), any()) }
            assertEquals(SatelliteSessionState.Live, mgr.get(serverId)?.state?.value)
        }

    // Only a satellite that advertises a machine id keeps its identity across an address change;
    // without one the new address is a new satellite.
    @Test
    fun `connect while faltering only updates the address`() =
        runMgrTest { mgr, _ ->
            val stable = server.copy(machineId = "m1")
            val stableId = SatelliteConnection.idFor(stable)
            stubLiveSession()
            every { store.satelliteSharedKey(stableId) } returns "aa".repeat(32)
            mgr.connect(stable)
            scope.testScheduler.runCurrent()
            assertEquals(SatelliteSessionState.Live, mgr.get(stableId)?.state?.value)
            every { controllerRepo.isConnectionAlive(any()) } returns false
            scope.testScheduler.advanceTimeBy(2100)
            scope.testScheduler.runCurrent()
            assertEquals(SatelliteSessionState.Faltering, mgr.get(stableId)?.state?.value)

            mgr.connect(stable.copy(ip = "10.0.0.7"), ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.runCurrent()

            assertEquals(
                "10.0.0.7",
                mgr
                    .get(stableId)
                    ?.server
                    ?.value
                    ?.ip,
            )
            assertEquals(SatelliteSessionState.Faltering, mgr.get(stableId)?.state?.value)
            coVerify(exactly = 1) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a slot the satellite refuses is reported by name and index`() =
        runMgrTest { mgr, events ->
            stubLiveSession()
            coEvery {
                discoveryRepo.putController(any(), any(), any(), any(), any(), any(), any())
            } returns ok("""{"epoch":2,"controller":{"ctrlIdx":0,"result":"backendUnavailable"}}""")
            connectLive(mgr)

            mgr.get(serverId)!!.attachSlot("slot-1", controllerType = 1)
            scope.testScheduler.runCurrent()

            assertTrue(
                events.any { it is ConnectionEvent.Error && it.message == "Couldn't apply controller on Pc: #0: backendUnavailable" },
            )
        }
}
