// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The sockets openSocket hands out, in order, and the refusal.
private const val FIRST_SOCKET = 5
private const val SECOND_SOCKET = 6
private const val NO_SOCKET = -1

private const val CLIENT_PIN = "4242"
private const val OTHER_CLIENT_PIN = "2222"
private const val PIN = "1234"

// One heartbeat tick: the alive poll has run once.
private const val ONE_TICK_MS = 1100L

// Five missed ticks: the heartbeat has declared the session dead.
private const val PAST_DEATH_MS = 5100L

// The alive polls a session misses before the heartbeat declares it dead.
private const val DEATH_MISSES = 5

// Twice the 60 s backoff cap: any retry still pending has fired by then.
private const val PAST_EVERY_BACKOFF_MS = 120_000L

// The approval poll asks every 2 s for two minutes: its last round trip goes out at 120 s.
private const val LAST_APPROVAL_POLL_MS = 120_000L
private const val APPROVAL_POLLS = 60

// How long a disconnect from another thread is given to land before the caller moves on.
private const val LANDING_WINDOW_MS = 500L

private const val PENDING = """{"status":"pending"}"""
private const val PAIRED = """{"ok":true,"sharedKey":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""
private const val APPROVED = """{"status":"approved","sharedKey":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""

// The applied view of the session this client holds, with no slots, as it matches a slotless connection.
private const val EMPTY_VIEW_AT_EPOCH_9 =
    """{"connectionId":"conn_1","epoch":9,"controllers":[],"hostFeatures":{"mouseControl":{"granted":false}}}"""

private const val NOT_PAIRED_YET = """{"ok":false}"""
private const val REJECTED = """{"error":"unauthorized","code":"NOT_PAIRED"}"""

/**
 * A user disconnect from another thread, the way the UI thread's lands on a handshake running on a
 * worker. [landWithin] starts it and gives it [LANDING_WINDOW_MS] to finish: ample when nothing holds
 * it back, and bounded so that a lock the caller holds makes it wait its turn instead of deadlocking.
 */
private class DisconnectFromAnotherThread(
    private val mgr: SatelliteConnectionManager,
    private val id: String,
) {
    private var thread: Thread? = null

    @Volatile private var failure: Throwable? = null

    fun landWithin() {
        if (thread != null) return
        val started = Thread(::disconnect)
        started.setUncaughtExceptionHandler { _, thrown -> failure = thrown }
        thread = started
        started.start()
        started.join(LANDING_WINDOW_MS)
    }

    fun awaitLanded() {
        thread?.join()
        failure?.let { throw it }
    }

    private fun disconnect() = mgr.disconnect(id)
}

// Interleavings of a user disconnect with every handshake, converge and retry the manager runs: each
// test lands the disconnect at one point a real one can reach and pins that it wins, that nothing the
// stale flow does afterwards touches a connection a newer handshake owns, and that nothing leaks.
@OptIn(ExperimentalCoroutinesApi::class)
class SatelliteConnectionManagerRaceTest : SatelliteConnectionManagerFixture() {
    private fun stateOf(mgr: SatelliteConnectionManager) = mgr.get(serverId)?.state?.value

    private fun grantFor(connectionId: String): String = sessionGrantBody().replace("conn_1", connectionId)

    private fun assertNoBanner(events: List<ConnectionEvent>) = assertTrue("the user asked for this; no banner: $events", events.isEmpty())

    // The pair round trip reaches the satellite whatever becomes of its caller, and a caller cancelled
    // meanwhile gets the cancellation back on return, as withContext does to the request under it.
    private fun pairAnsweredRegardless(body: String): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        coEvery { discoveryRepo.pair(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            withContext(NonCancellable) { gate.await() }
            currentCoroutineContext().ensureActive()
            ok(body)
        }
        return gate
    }

    // A disconnect that lands the moment the connection turns Linking, before the handshake has read anything else.
    private fun disconnectOnceLinking(mgr: SatelliteConnectionManager) {
        scope.launch {
            val conn = mgr.connections.mapNotNull { it[serverId] }.first()
            conn.state.first { it == SatelliteSessionState.Linking }
            mgr.disconnect(serverId)
        }
    }

    @Test
    fun `a disconnect during an approval request's first pair leaves the connect after it to go live`() =
        runMgrTest { mgr, events ->
            val pairAnswered = pairAnsweredRegardless(PENDING)
            mgr.requestApproval(server, CLIENT_PIN)
            scope.testScheduler.runCurrent()

            mgr.disconnect(serverId)
            stubStoredKey()
            every { controllerRepo.openSocket(any(), any()) } returns FIRST_SOCKET
            val (put) = gatedSessionPuts(sessionGrantBody())
            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.runCurrent()
            pairAnswered.complete(Unit)
            scope.testScheduler.runCurrent()
            assertEquals(SatelliteSessionState.Linking, stateOf(mgr))

            put.complete(Unit)
            scope.testScheduler.runCurrent()
            assertEquals(SatelliteSessionState.Live, stateOf(mgr))
            assertEquals("conn_1", mgr.get(serverId)?.connectionId)
            assertNoBanner(events)
        }

    // A re-issued request moves no generation: only the cancellation can stop the first one.
    @Test
    fun `a re-issued approval request is not ended by the pair the first one had out`() =
        runMgrTest { mgr, events ->
            val firstAnswered = CompletableDeferred<Unit>()
            var pairs = 0
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                pairs++
                if (pairs > 1) awaitCancellation()
                withContext(NonCancellable) { firstAnswered.await() }
                currentCoroutineContext().ensureActive()
                ok(PENDING)
            }
            mgr.requestApproval(server, CLIENT_PIN)
            scope.testScheduler.runCurrent()
            mgr.requestApproval(server, OTHER_CLIENT_PIN)
            scope.testScheduler.runCurrent()

            firstAnswered.complete(Unit)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Linking, stateOf(mgr))
            assertTrue("the first request's cancellation is no failure: $events", events.isEmpty())
        }

    @Test
    fun `a re-issued approval request is not timed out by the last poll the first one had out`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any(), any(), any(), any()) } returns ok(PENDING)
            val lastPollAnswered = CompletableDeferred<Unit>()
            var polls = 0
            coEvery { discoveryRepo.pairStatus(any(), any(), any(), any()) } coAnswers {
                polls++
                if (polls == APPROVAL_POLLS) {
                    withContext(NonCancellable) { lastPollAnswered.await() }
                    currentCoroutineContext().ensureActive()
                }
                ok(PENDING)
            }
            mgr.requestApproval(server, CLIENT_PIN)
            scope.testScheduler.advanceTimeBy(LAST_APPROVAL_POLL_MS)
            scope.testScheduler.runCurrent()
            assertEquals(APPROVAL_POLLS, polls)

            mgr.requestApproval(server, OTHER_CLIENT_PIN)
            scope.testScheduler.runCurrent()
            lastPollAnswered.complete(Unit)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Linking, stateOf(mgr))
            assertTrue("the first request's cancellation is no timeout: $events", events.isEmpty())
        }

    @Test
    fun `a connect that a disconnect meets as it starts linking never goes live`() =
        runMgrTest { mgr, events ->
            stubLiveSession()
            disconnectOnceLinking(mgr)

            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Idle, stateOf(mgr))
            coVerify(exactly = 1) { discoveryRepo.disconnect("10.0.0.5", 9877, "conn_1", "test-device-id", any()) }
            verify(exactly = 0) { controllerRepo.openSocket(any(), any()) }
            assertNoBanner(events)
        }

    @Test
    fun `a PIN pair that a disconnect meets as it starts linking opens no session`() =
        runMgrTest { mgr, events ->
            stubLiveSession()
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any(), any(), any(), any()) } returns ok(PAIRED)
            disconnectOnceLinking(mgr)

            mgr.pairWithPin(server, PIN)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Idle, stateOf(mgr))
            coVerify(exactly = 0) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
            assertNoBanner(events)
        }

    // The disconnect lands before the poll exists, so no cancellation reaches it: only the generation can.
    @Test
    fun `an approval request that a disconnect meets as it starts linking never polls`() =
        runMgrTest { mgr, events ->
            stubLiveSession()
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any(), any(), any(), any()) } returns ok(PENDING)
            coEvery { discoveryRepo.pairStatus(any(), any(), any(), any()) } returns ok(APPROVED)
            disconnectOnceLinking(mgr)

            mgr.requestApproval(server, CLIENT_PIN)
            scope.testScheduler.advanceTimeBy(PAST_EVERY_BACKOFF_MS)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Idle, stateOf(mgr))
            coVerify(exactly = 0) { discoveryRepo.pairStatus(any(), any(), any(), any()) }
            coVerify(exactly = 0) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
            assertNoBanner(events)
        }

    @Test
    fun `a pair that finds us unknown after a disconnect asks nothing of the user`() =
        runMgrTest { mgr, events ->
            val pairAnswered = gatedPair(NOT_PAIRED_YET)
            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.runCurrent()

            mgr.disconnect(serverId)
            pairAnswered.complete(Unit)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Idle, stateOf(mgr))
            assertNoBanner(events)
            assertFalse(serverId in mgr.staleSatelliteIds.value)
        }

    @Test
    fun `a PIN pair that fails after a disconnect raises no banner`() =
        runMgrTest { mgr, events ->
            val pairAnswered = gatedPair("")
            mgr.pairWithPin(server, PIN)
            scope.testScheduler.runCurrent()

            mgr.disconnect(serverId)
            pairAnswered.complete(Unit)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Idle, stateOf(mgr))
            assertNoBanner(events)
        }

    // As with the poll, the disconnect lands before the request's job is registered to be cancelled.
    @Test
    fun `an approval request whose pair fails after a disconnect raises no banner`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any(), any(), any(), any()) } returns unreachable()
            disconnectOnceLinking(mgr)

            mgr.requestApproval(server, CLIENT_PIN)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Idle, stateOf(mgr))
            assertNoBanner(events)
        }

    // The old session's heartbeat tick has already read its verdict when the user disconnects and
    // taps again; the verdict belongs to the session that is gone.
    private fun staleVerdictLeavesTheNewSession(stubVerdict: (SatelliteConnectionManager) -> Unit) =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            connectLive(mgr)
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
            } returns ok(grantFor("conn_2"))
            stubVerdict(mgr)

            scope.testScheduler.advanceTimeBy(PAST_EVERY_BACKOFF_MS)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Live, stateOf(mgr))
            assertEquals("conn_2", mgr.get(serverId)?.connectionId)
            coVerify(exactly = 0) { discoveryRepo.disconnect(any(), any(), "conn_2", any(), any()) }
            coVerify(exactly = 2) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    private fun reconnect(mgr: SatelliteConnectionManager) {
        mgr.disconnect(serverId)
        mgr.connect(server, ConnectIntent.USER_INITIATED)
    }

    @Test
    fun `a heartbeat death decided before a disconnect and a new tap leaves the new session live`() =
        staleVerdictLeavesTheNewSession { mgr ->
            var polls = 0
            every { controllerRepo.isConnectionAlive(any()) } answers {
                polls++
                if (polls == DEATH_MISSES) reconnect(mgr)
                polls > DEATH_MISSES
            }
        }

    @Test
    fun `a close-notify read before a disconnect and a new tap leaves the new session live`() =
        staleVerdictLeavesTheNewSession { mgr ->
            var polls = 0
            every { controllerRepo.getSessionCloseReason(any()) } answers {
                polls++
                if (polls == 1) {
                    reconnect(mgr)
                    SatelliteConnection.CLOSE_REASON_KICKED
                } else {
                    -1
                }
            }
        }

    @Test
    fun `a disconnect while the granted session's socket opens closes it and hands the session back`() =
        runMgrTest { mgr, events ->
            stubStoredKey()
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
            } returns ok(sessionGrantBody())
            every { controllerRepo.openSocket(any(), any()) } answers {
                mgr.disconnect(serverId)
                FIRST_SOCKET
            }

            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Idle, stateOf(mgr))
            verify(exactly = 1) { controllerRepo.closeSocket(FIRST_SOCKET) }
            coVerify(exactly = 1) { discoveryRepo.disconnect("10.0.0.5", 9877, "conn_1", "test-device-id", any()) }
            verify(exactly = 0) { controllerRepo.startHeartbeat(any()) }
            assertNoBanner(events)
        }

    @Test
    fun `a disconnect and a new tap while the socket opens leave the new tap to go live on its own session`() =
        runMgrTest { mgr, _ ->
            stubStoredKey()
            val (stalePut, freshPut) = gatedSessionPuts(sessionGrantBody(), grantFor("conn_2"))
            var sockets = 0
            every { controllerRepo.openSocket(any(), any()) } answers {
                sockets++
                if (sockets == 1) {
                    mgr.disconnect(serverId)
                    mgr.connect(server, ConnectIntent.USER_INITIATED)
                    FIRST_SOCKET
                } else {
                    SECOND_SOCKET
                }
            }
            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.runCurrent()

            stalePut.complete(Unit)
            scope.testScheduler.runCurrent()
            assertEquals(SatelliteSessionState.Linking, stateOf(mgr))
            verify(exactly = 1) { controllerRepo.closeSocket(FIRST_SOCKET) }
            coVerify(exactly = 1) { discoveryRepo.disconnect(any(), any(), "conn_1", any(), any()) }

            freshPut.complete(Unit)
            scope.testScheduler.runCurrent()
            assertEquals(SatelliteSessionState.Live, stateOf(mgr))
            assertEquals("conn_2", mgr.get(serverId)?.connectionId)
            assertEquals(SECOND_SOCKET, mgr.get(serverId)?.handle)
            coVerify(exactly = 0) { discoveryRepo.disconnect(any(), any(), "conn_2", any(), any()) }
        }

    // A PIN submitted while a connect is still linking runs a second handshake under the same
    // generation; whichever answers second finds the connection live already.
    @Test
    fun `a handshake that loses the race to go live hands its own session back`() =
        runMgrTest { mgr, _ ->
            stubStoredKey()
            val olderProtocol = grantFor("conn_2").replace(""""protocolVersion":2""", """"protocolVersion":1""")
            val (connectPut, pinPut) = gatedSessionPuts(sessionGrantBody(), olderProtocol)
            every { controllerRepo.openSocket(any(), any()) } returnsMany listOf(FIRST_SOCKET, SECOND_SOCKET)
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any(), any(), any(), any()) } returns ok(PAIRED)
            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.runCurrent()
            mgr.pairWithPin(server, PIN)
            scope.testScheduler.runCurrent()

            connectPut.complete(Unit)
            scope.testScheduler.runCurrent()
            pinPut.complete(Unit)
            scope.testScheduler.runCurrent()

            val conn = mgr.get(serverId)!!
            assertEquals(SatelliteSessionState.Live, conn.state.value)
            assertEquals("conn_1", conn.connectionId)
            assertEquals(FIRST_SOCKET, conn.handle)
            assertEquals(2, conn.protocolVersion)
            assertEquals(2, conn.sessionFacts.value?.protocolVersion)
            verify(exactly = 1) { controllerRepo.closeSocket(SECOND_SOCKET) }
            coVerify(exactly = 1) { discoveryRepo.disconnect(any(), any(), "conn_2", any(), any()) }
            coVerify(exactly = 0) { discoveryRepo.disconnect(any(), any(), "conn_1", any(), any()) }
        }

    @Test
    fun `a disconnect and a new tap while a failed wire hands its session back leave the new tap alone`() =
        runMgrTest { mgr, events ->
            stubStoredKey()
            val (stalePut, freshPut) = gatedSessionPuts(sessionGrantBody(), grantFor("conn_2"))
            stalePut.complete(Unit)
            every { controllerRepo.openSocket(any(), any()) } returnsMany listOf(NO_SOCKET, SECOND_SOCKET)
            var retapped = false
            coEvery { discoveryRepo.disconnect(any(), any(), "conn_1", any(), any()) } coAnswers {
                if (!retapped) {
                    retapped = true
                    mgr.disconnect(serverId)
                    mgr.connect(server, ConnectIntent.USER_INITIATED)
                }
                ok("{}")
            }

            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.runCurrent()
            assertEquals(SatelliteSessionState.Linking, stateOf(mgr))
            assertNoBanner(events)

            freshPut.complete(Unit)
            scope.testScheduler.runCurrent()
            assertEquals(SatelliteSessionState.Live, stateOf(mgr))
            assertEquals("conn_2", mgr.get(serverId)?.connectionId)
        }

    @Test
    fun `a disconnect landing as a silent retry fires keeps the connection down`() =
        runMgrTest { mgr, _ ->
            stubStoredKey()
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
            } returns unreachable()
            mgr.connect(server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.runCurrent()

            every { store.remembered() } answers {
                mgr.disconnect(serverId)
                emptyList()
            }
            scope.testScheduler.advanceTimeBy(PAST_EVERY_BACKOFF_MS)
            scope.testScheduler.runCurrent()

            coVerify(exactly = 1) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
            assertEquals(SatelliteSessionState.Idle, stateOf(mgr))
        }

    // The user's disconnect lands just after the manager's own teardown and before the retry is
    // scheduled, so it has no pending retry to cancel: the retry itself must see it was overtaken.
    private fun disconnectAfterTeardownKeepsItDown(endSession: () -> Unit) =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            connectLive(mgr)
            var landed = false
            every { controllerRepo.closeSocket(any()) } answers {
                if (!landed) {
                    landed = true
                    mgr.disconnect(serverId)
                }
            }

            endSession()
            scope.testScheduler.advanceTimeBy(PAST_EVERY_BACKOFF_MS)
            scope.testScheduler.runCurrent()

            assertTrue(landed)
            coVerify(exactly = 1) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
            assertEquals(SatelliteSessionState.Idle, stateOf(mgr))
        }

    @Test
    fun `a disconnect between a close-notify's teardown and its retry keeps the connection down`() =
        disconnectAfterTeardownKeepsItDown {
            every { controllerRepo.getSessionCloseReason(any()) } returns SatelliteConnection.CLOSE_REASON_KICKED
            scope.testScheduler.advanceTimeBy(ONE_TICK_MS)
            scope.testScheduler.runCurrent()
            every { controllerRepo.getSessionCloseReason(any()) } returns -1
        }

    @Test
    fun `a disconnect between a dead heartbeat's teardown and its retry keeps the connection down`() =
        disconnectAfterTeardownKeepsItDown {
            every { controllerRepo.isConnectionAlive(any()) } returns false
            scope.testScheduler.advanceTimeBy(PAST_DEATH_MS)
            scope.testScheduler.runCurrent()
            every { controllerRepo.isConnectionAlive(any()) } returns true
        }

    // The disconnect comes from another thread while the session is being torn down for a fresh PUT;
    // the fresh PUT answers only once that disconnect has landed.
    private fun disconnectDuringRestartKeepsItDown(trigger: () -> Unit) =
        runMgrTest { mgr, events ->
            stubLiveSession()
            connectLive(mgr)
            val other = DisconnectFromAnotherThread(mgr, serverId)
            every { controllerRepo.stopHeartbeat(any()) } answers { other.landWithin() }
            coEvery {
                discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                other.awaitLanded()
                ok(grantFor("conn_2"))
            }

            trigger()
            other.awaitLanded()
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Idle, stateOf(mgr))
            coVerify(exactly = 1) { discoveryRepo.disconnect(any(), any(), "conn_2", any(), any()) }
            verify(exactly = 1) { controllerRepo.openSocket(any(), any()) }
            assertNoBanner(events)
        }

    @Test
    fun `a disconnect while a rekey tears the session down keeps it down`() =
        disconnectDuringRestartKeepsItDown {
            every { controllerRepo.getSendCounter(any()) } returns COUNTER_REPUSH_THRESHOLD
            scope.testScheduler.advanceTimeBy(ONE_TICK_MS)
            scope.testScheduler.runCurrent()
        }

    @Test
    fun `a disconnect while a reconcile tears the session down keeps it down`() =
        disconnectDuringRestartKeepsItDown {
            coEvery { discoveryRepo.getSession(any(), any(), any(), any(), any()) } returns
                ok(matchingViewBody(epoch = 9).replace("conn_1", "conn_other"))
            every { controllerRepo.getServerEpoch(any()) } returns 9
            scope.testScheduler.advanceTimeBy(ONE_TICK_MS)
            scope.testScheduler.runCurrent()
        }

    @Test
    fun `a reconcile view that matches but answers after a disconnect adopts nothing`() =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            val viewAnswered = CompletableDeferred<Unit>()
            coEvery { discoveryRepo.getSession(any(), any(), any(), any(), any()) } coAnswers {
                viewAnswered.await()
                ok(EMPTY_VIEW_AT_EPOCH_9)
            }
            connectLive(mgr)
            val conn = mgr.get(serverId)!!
            every { controllerRepo.getServerEpoch(any()) } returns 9
            scope.testScheduler.advanceTimeBy(ONE_TICK_MS)
            scope.testScheduler.runCurrent()

            mgr.disconnect(serverId)
            viewAnswered.complete(Unit)
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Idle, conn.state.value)
            assertEquals(-1, conn.lastAppliedEpoch)
        }

    // A slot converge is out when the user disconnects and taps again: its answer is about the old
    // session and must not land on the new one.
    private fun staleSlotAnswer(
        answer: String,
        converge: (SatelliteConnection) -> Unit,
        stubConverge: (CompletableDeferred<Unit>, String) -> Unit,
        check: (SatelliteConnectionManager, SatelliteConnection) -> Unit,
    ) = runMgrTest { mgr, _ ->
        stubLiveSession()
        connectLive(mgr)
        val conn = mgr.get(serverId)!!
        val answered = CompletableDeferred<Unit>()
        stubConverge(answered, answer)
        converge(conn)
        scope.testScheduler.runCurrent()

        mgr.disconnect(serverId)
        coEvery {
            discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ok(grantFor("conn_2").replace(""""epoch":1""", """"epoch":3"""))
        mgr.connect(server, ConnectIntent.USER_INITIATED)
        scope.testScheduler.runCurrent()
        assertEquals("conn_2", conn.connectionId)

        answered.complete(Unit)
        scope.testScheduler.runCurrent()
        check(mgr, conn)
    }

    private fun gatedControllerPut(
        answered: CompletableDeferred<Unit>,
        answer: String,
    ) {
        coEvery { discoveryRepo.putController(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            answered.await()
            ok(answer)
        }
    }

    private fun attachSlot(conn: SatelliteConnection) = conn.attachSlot("slot-1", controllerType = 1)

    @Test
    fun `a slot put that answers for a session since replaced folds nothing into the new one`() =
        staleSlotAnswer(
            """{"epoch":2,"controller":{"ctrlIdx":0,"result":"ok","appliedType":1,""" +
                """"motion":{"sinkSupportedForType":false,"backendOk":true}}}""",
            ::attachSlot,
            ::gatedControllerPut,
        ) { _, conn ->
            assertEquals(3, conn.lastAppliedEpoch)
            val slot = conn.slots.value.getValue("slot-1")
            assertFalse(slot.registered)
        }

    @Test
    fun `a slot put rejected for a session since replaced leaves the new one live`() =
        staleSlotAnswer(REJECTED, ::attachSlot, ::gatedControllerPut) { mgr, conn ->
            assertEquals(SatelliteSessionState.Live, conn.state.value)
            verify(exactly = 0) { store.forgetSatelliteSharedKey(any()) }
            assertFalse(serverId in mgr.staleSatelliteIds.value)
        }

    private fun attachThenDetachSlot(conn: SatelliteConnection) {
        attachSlot(conn)
        scope.testScheduler.runCurrent()
        conn.detachSlot("slot-1")
    }

    private fun appliedThenGatedDelete(
        answered: CompletableDeferred<Unit>,
        answer: String,
    ) {
        coEvery { discoveryRepo.putController(any(), any(), any(), any(), any(), any(), any()) } returns
            ok(
                """{"epoch":2,"controller":{"ctrlIdx":0,"result":"ok","appliedType":1,""" +
                    """"motion":{"sinkSupportedForType":false,"backendOk":true}}}""",
            )
        coEvery { discoveryRepo.deleteController(any(), any(), any(), any(), any(), any()) } coAnswers {
            answered.await()
            ok(answer)
        }
    }

    @Test
    fun `a slot delete that answers for a session since replaced leaves the new one's epoch`() =
        staleSlotAnswer("""{"epoch":7}""", ::attachThenDetachSlot, ::appliedThenGatedDelete) { _, conn ->
            assertEquals(3, conn.lastAppliedEpoch)
        }
}
