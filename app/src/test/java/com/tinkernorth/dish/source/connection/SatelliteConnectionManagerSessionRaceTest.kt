// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.CoroutineContext

// One heartbeat tick: the alive poll has run once.
private const val ONE_TICK_MS = 1100L

// Five missed ticks: the heartbeat has declared the session dead.
private const val PAST_DEATH_MS = 5100L

// The alive polls a session misses before the heartbeat declares it dead.
private const val DEATH_MISSES = 5

// The epoch a heartbeat ack reports when the satellite's topology drifted from the first session's
// (granted at epoch 1), and the one it reports when no enriched ack has arrived.
private const val DRIFTED_EPOCH = 9
private const val NO_ACK_EPOCH = -1

private const val SEND_COUNTER_FRESH = 0L

// The first session's applied view with no slots: it matches a slotless connection, at epoch 9.
private const val FIRST_SESSION_EMPTY_VIEW =
    """{"connectionId":"conn_1","epoch":9,"controllers":[],"hostFeatures":{"mouseControl":{"granted":false}}}"""

// The epoch the rekey's fresh PUT grants the second session, and a slot delete's answer about the first.
private const val SECOND_SESSION_EPOCH = 3
private const val STALE_DELETE_ANSWER = """{"epoch":7}"""

private const val REJECTED = """{"error":"unauthorized","code":"NOT_PAIRED"}"""
private const val APPLIED =
    """{"epoch":2,"controller":{"ctrlIdx":0,"result":"ok","appliedType":1,""" +
        """"motion":{"sinkSupportedForType":false,"backendOk":true}}}"""

// Two more missed ticks after an answered one: the heartbeat has turned the session Faltering.
private const val UNTIL_FALTERING_MS = 2000L

/**
 * Runs what it is handed at once, as the fixture's unconfined IO does, except the next [holdNext]
 * dispatches: it keeps those until [release], so a test decides when a step launched onto IO runs.
 */
private class HoldingDispatcher : CoroutineDispatcher() {
    private val held = ArrayDeque<Runnable>()

    var holdNext = 0

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = holdNext > 0

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        holdNext--
        held.addLast(block)
    }

    fun release() {
        while (held.isNotEmpty()) held.removeFirst().run()
    }
}

// A session ended without a user disconnect (a reconcile or rekey tearing it down for a fresh PUT, a
// rejected slot put) leaves the generation where it was, so the session that replaces it is adopted
// under the same generation. Each test lets a verdict about the first session land on the second one,
// and pins that it touches nothing there.
@OptIn(ExperimentalCoroutinesApi::class)
class SatelliteConnectionManagerSessionRaceTest : SatelliteConnectionManagerFixture() {
    private fun grantFor(connectionId: String): String = sessionGrantBody().replace("conn_1", connectionId)

    private fun sessionPutsGrant(vararg connectionIds: String) {
        coEvery {
            discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany connectionIds.map { ok(grantFor(it)) }
    }

    private fun assertStillTheSecondSession(conn: SatelliteConnection) {
        assertEquals(SatelliteSessionState.Live, conn.state.value)
        assertEquals("conn_2", conn.connectionId)
        coVerify(exactly = 2) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { discoveryRepo.disconnect(any(), any(), "conn_2", any(), any()) }
    }

    // The first session's heartbeat asks for a reconcile (its epoch drifted) and, in the same tick, a
    // rekey: the reconcile's view of the first session is still out when the rekey's fresh PUT
    // replaces it, and answers only once the second session is live.
    private fun reconcileAnswersAfterARekeyReplacedTheSession(
        view: String,
        check: (SatelliteConnectionManager, SatelliteConnection) -> Unit,
    ) = runMgrTest { mgr, _ ->
        stubLiveSession()
        connectLive(mgr)
        val conn = mgr.get(serverId)!!
        sessionPutsGrant("conn_2", "conn_3")
        val viewAnswered = CompletableDeferred<Unit>()
        coEvery { discoveryRepo.getSession(any(), any(), any(), any(), any()) } coAnswers {
            viewAnswered.await()
            ok(view)
        }
        every { controllerRepo.getServerEpoch(any()) } returnsMany listOf(DRIFTED_EPOCH, NO_ACK_EPOCH)
        every { controllerRepo.getSendCounter(any()) } returnsMany listOf(COUNTER_REPUSH_THRESHOLD, SEND_COUNTER_FRESH)
        scope.testScheduler.advanceTimeBy(ONE_TICK_MS)
        scope.testScheduler.runCurrent()
        assertEquals("conn_2", conn.connectionId)

        viewAnswered.complete(Unit)
        scope.testScheduler.runCurrent()
        check(mgr, conn)
    }

    @Test
    fun `a reconcile view of a session a rekey replaced does not tear the new session down`() =
        reconcileAnswersAfterARekeyReplacedTheSession(matchingViewBody(epoch = DRIFTED_EPOCH)) { _, conn ->
            assertStillTheSecondSession(conn)
        }

    @Test
    fun `a matching reconcile view of a session a rekey replaced adopts nothing into the new one`() =
        reconcileAnswersAfterARekeyReplacedTheSession(FIRST_SESSION_EMPTY_VIEW) { _, conn ->
            assertStillTheSecondSession(conn)
            assertEquals(1, conn.lastAppliedEpoch)
        }

    @Test
    fun `a reconcile rejected for a session a rekey replaced leaves the new one and its key`() =
        reconcileAnswersAfterARekeyReplacedTheSession(REJECTED) { mgr, conn ->
            assertStillTheSecondSession(conn)
            verify(exactly = 0) { store.forgetSatelliteSharedKey(any()) }
            assertFalse(serverId in mgr.staleSatelliteIds.value)
        }

    // The first session's tick runs the reconcile its drift asked for, which replaces the session,
    // and then finds the send counter past the re-PUT threshold: the rekey it asks for is the first
    // session's, and the second session's counter starts afresh.
    @Test
    fun `a rekey asked for by a session a reconcile replaced leaves the new session alone`() =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            connectLive(mgr)
            val conn = mgr.get(serverId)!!
            sessionPutsGrant("conn_2", "conn_3")
            coEvery { discoveryRepo.getSession(any(), any(), any(), any(), any()) } returns
                ok(matchingViewBody(epoch = DRIFTED_EPOCH))
            every { controllerRepo.getServerEpoch(any()) } returnsMany listOf(DRIFTED_EPOCH, NO_ACK_EPOCH)
            every { controllerRepo.getSendCounter(any()) } returnsMany listOf(COUNTER_REPUSH_THRESHOLD, SEND_COUNTER_FRESH)

            scope.testScheduler.advanceTimeBy(ONE_TICK_MS)
            scope.testScheduler.runCurrent()

            assertStillTheSecondSession(conn)
        }

    // A slot converge for the first session is out when the first session's heartbeat asks for a
    // rekey; the converge's answer arrives once the rekey's fresh PUT has made the second one live.
    private fun slotAnswerAfterARekeyReplacedTheSession(
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
        coEvery {
            discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ok(grantFor("conn_2").replace(""""epoch":1""", """"epoch":$SECOND_SESSION_EPOCH"""))
        every { controllerRepo.getSendCounter(any()) } returnsMany listOf(COUNTER_REPUSH_THRESHOLD, SEND_COUNTER_FRESH)
        scope.testScheduler.advanceTimeBy(ONE_TICK_MS)
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

    private fun attachThenDetachSlot(conn: SatelliteConnection) {
        attachSlot(conn)
        scope.testScheduler.runCurrent()
        conn.detachSlot("slot-1")
    }

    private fun appliedThenGatedDelete(
        answered: CompletableDeferred<Unit>,
        answer: String,
    ) {
        coEvery { discoveryRepo.putController(any(), any(), any(), any(), any(), any(), any()) } returns ok(APPLIED)
        coEvery { discoveryRepo.deleteController(any(), any(), any(), any(), any(), any()) } coAnswers {
            answered.await()
            ok(answer)
        }
    }

    @Test
    fun `a slot put answering for a session a rekey replaced folds nothing into the new one`() =
        slotAnswerAfterARekeyReplacedTheSession(APPLIED, ::attachSlot, ::gatedControllerPut) { _, conn ->
            assertEquals(SECOND_SESSION_EPOCH, conn.lastAppliedEpoch)
            val slot = conn.slots.value.getValue("slot-1")
            assertFalse(slot.registered)
        }

    @Test
    fun `a slot put rejected for a session a rekey replaced leaves the new one and its key`() =
        slotAnswerAfterARekeyReplacedTheSession(REJECTED, ::attachSlot, ::gatedControllerPut) { mgr, conn ->
            assertEquals(SatelliteSessionState.Live, conn.state.value)
            assertEquals("conn_2", conn.connectionId)
            verify(exactly = 0) { store.forgetSatelliteSharedKey(any()) }
            assertFalse(serverId in mgr.staleSatelliteIds.value)
        }

    @Test
    fun `a slot delete answering for a session a rekey replaced leaves the new one's epoch`() =
        slotAnswerAfterARekeyReplacedTheSession(STALE_DELETE_ANSWER, ::attachThenDetachSlot, ::appliedThenGatedDelete) { _, conn ->
            assertEquals(SECOND_SESSION_EPOCH, conn.lastAppliedEpoch)
        }

    // The reconcile the first session's drift asks for is held until [overtake] has taken the session
    // away; by then there is nothing of that session left to ask the satellite about.
    private fun heldReconcileAfter(overtake: (SatelliteConnectionManager) -> Unit) {
        val io = HoldingDispatcher()
        runMgrTest(io) { mgr, _ ->
            stubLiveSession()
            connectLive(mgr)
            every { controllerRepo.getServerEpoch(any()) } returnsMany listOf(DRIFTED_EPOCH, NO_ACK_EPOCH)
            coEvery { discoveryRepo.getSession(any(), any(), any(), any(), any()) } returns
                ok(matchingViewBody(epoch = DRIFTED_EPOCH))
            io.holdNext = 1
            overtake(mgr)

            io.release()
            scope.testScheduler.runCurrent()

            coVerify(exactly = 0) { discoveryRepo.getSession(any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `a reconcile that runs after a disconnect asks the satellite nothing`() =
        heldReconcileAfter { mgr ->
            scope.testScheduler.advanceTimeBy(ONE_TICK_MS)
            scope.testScheduler.runCurrent()
            mgr.disconnect(serverId)
        }

    @Test
    fun `a reconcile that runs after a rekey replaced its session asks the satellite nothing`() =
        heldReconcileAfter { mgr ->
            sessionPutsGrant("conn_2", "conn_3")
            every { controllerRepo.getSendCounter(any()) } returnsMany listOf(COUNTER_REPUSH_THRESHOLD, SEND_COUNTER_FRESH)
            scope.testScheduler.advanceTimeBy(ONE_TICK_MS)
            scope.testScheduler.runCurrent()
            assertStillTheSecondSession(mgr.get(serverId)!!)
        }

    // The rekey the first tick asks for is held until two missed ticks have turned the session
    // Faltering: it restarts only a Live session, and leaves this one to its heartbeat.
    @Test
    fun `a rekey that runs once its session falters leaves the session to its heartbeat`() {
        val io = HoldingDispatcher()
        runMgrTest(io) { mgr, _ ->
            stubLiveSession()
            connectLive(mgr)
            val conn = mgr.get(serverId)!!
            every { controllerRepo.getSendCounter(any()) } returns COUNTER_REPUSH_THRESHOLD
            io.holdNext = 1
            scope.testScheduler.advanceTimeBy(ONE_TICK_MS)
            scope.testScheduler.runCurrent()
            every { controllerRepo.isConnectionAlive(any()) } returns false
            scope.testScheduler.advanceTimeBy(UNTIL_FALTERING_MS)
            scope.testScheduler.runCurrent()
            assertEquals(SatelliteSessionState.Faltering, conn.state.value)

            io.release()
            scope.testScheduler.runCurrent()

            assertEquals(SatelliteSessionState.Faltering, conn.state.value)
            assertEquals("conn_1", conn.connectionId)
            coVerify(exactly = 1) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    /**
     * On another thread, a slot put the satellite rejects drops the first session (no disconnect, so
     * the generation stays) and a new tap opens the second one. The rejection answers only when the
     * first session's heartbeat tick, already past its own checks, lets it: the tick's verdict then
     * lands after the second session is live.
     */
    private fun staleVerdictAfterASameGenerationReplacement(stubVerdict: (replace: () -> Unit) -> Unit) =
        runMgrTest { mgr, _ ->
            stubLiveSession()
            connectLive(mgr)
            val conn = mgr.get(serverId)!!
            sessionPutsGrant("conn_2")
            val rejectionMayAnswer = CountDownLatch(1)
            var slotPuts = 0
            coEvery { discoveryRepo.putController(any(), any(), any(), any(), any(), any(), any()) } answers {
                slotPuts++
                if (slotPuts == 1) {
                    awaitOpened(rejectionMayAnswer)
                    ok(REJECTED)
                } else {
                    ok(APPLIED)
                }
            }
            val dropAndRetap =
                OnAnotherThread {
                    conn.attachSlot("slot-1", controllerType = 1)
                    mgr.connect(server, ConnectIntent.USER_INITIATED)
                }
            dropAndRetap.start()
            awaitThreadState(dropAndRetap.thread, Thread.State.TIMED_WAITING)
            stubVerdict {
                rejectionMayAnswer.countDown()
                dropAndRetap.awaitDone()
            }

            scope.testScheduler.advanceTimeBy(PAST_DEATH_MS)
            scope.testScheduler.runCurrent()

            assertStillTheSecondSession(conn)
        }

    @Test
    fun `a close-notify read for a session replaced under the same generation leaves the new one live`() =
        staleVerdictAfterASameGenerationReplacement { replace ->
            var reads = 0
            every { controllerRepo.getSessionCloseReason(any()) } answers {
                reads++
                if (reads == 1) {
                    replace()
                    SatelliteConnection.CLOSE_REASON_KICKED
                } else {
                    -1
                }
            }
        }

    @Test
    fun `a heartbeat death decided for a session replaced under the same generation leaves the new one live`() =
        staleVerdictAfterASameGenerationReplacement { replace ->
            var polls = 0
            every { controllerRepo.isConnectionAlive(any()) } answers {
                polls++
                if (polls == DEATH_MISSES) replace()
                polls > DEATH_MISSES
            }
        }
}
