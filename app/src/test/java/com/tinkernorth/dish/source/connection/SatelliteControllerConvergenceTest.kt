// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection

import com.tinkernorth.dish.core.net.ControllerDescriptor
import com.tinkernorth.dish.core.net.HttpReply
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SLOT = "slot-1"
private const val REPLACEMENT_SLOT = "slot-2"
private const val TYPE = 1
private const val INITIAL_EPOCH = 2
private const val NEXT_SESSION_EPOCH = 9
private const val HEARTBEAT_TICK_MS = 1100L
private const val APPLIED_CONTROLLER =
    """{"epoch":2,"controller":{"ctrlIdx":0,"result":"ok","appliedType":1,""" +
        """"motion":{"sinkSupportedForType":true,"backendOk":true}}}"""

private class ControllerHost {
    var gate: CompletableDeferred<Unit>? = null
    var descriptor: String? = null
    var calls = 0
    var activeRequests = 0
    var peakRequests = 0
    var deletes = 0
    var reply = HttpReply(200, APPLIED_CONTROLLER, null)

    suspend fun put(sent: String): HttpReply {
        calls++
        activeRequests++
        peakRequests = maxOf(peakRequests, activeRequests)
        gate?.await()
        descriptor = sent
        activeRequests--
        return reply
    }

    suspend fun delete(): HttpReply {
        deletes++
        activeRequests++
        peakRequests = maxOf(peakRequests, activeRequests)
        gate?.await()
        descriptor = null
        activeRequests--
        return HttpReply(200, """{"epoch":3}""", null)
    }
}

private class SessionHost(
    private val grantBody: String,
    private val firstGrantGate: CompletableDeferred<Unit>? = null,
    private val deleteGate: CompletableDeferred<Unit>? = null,
    initialExists: Boolean = false,
) {
    var exists = initialExists
        private set
    var grants = 0
        private set

    suspend fun put(): HttpReply {
        grants++
        if (grants == 1) firstGrantGate?.await()
        exists = true
        return HttpReply(200, grantBody, null)
    }

    suspend fun delete(): HttpReply {
        deleteGate?.await()
        exists = false
        return HttpReply(200, "{}", null)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SatelliteControllerConvergenceTest : SatelliteConnectionManagerFixture() {
    private val host = ControllerHost()
    private var caps = BASE_WIRE_CAPS
    private var mode = ControllerDescriptor.TOUCHPAD_MODE_OFF

    private fun registeredConnection(mgr: SatelliteConnectionManager): SatelliteConnection {
        stubLiveSession()
        every { capabilityComposer.wireCapsFor(any()) } answers { caps }
        every { capabilityComposer.touchpadWireMode(any()) } answers { mode }
        coEvery { discoveryRepo.putController(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            host.put(arg<String>(6))
        }
        coEvery { discoveryRepo.deleteController(any(), any(), any(), any(), any(), any()) } coAnswers { host.delete() }
        connectLive(mgr)
        val conn = mgr.get(serverId)!!
        conn.attachSlot(SLOT, TYPE)
        scope.testScheduler.runCurrent()
        assertEquals(INITIAL_EPOCH, conn.lastAppliedEpoch)
        return conn
    }

    private fun holdRequests(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { host.gate = it }

    private fun changeCaps(
        conn: SatelliteConnection,
        desired: Int,
    ) {
        caps = desired
        conn.refreshCapsIfChanged()
        scope.testScheduler.runCurrent()
    }

    private fun changeMode(
        conn: SatelliteConnection,
        desired: String,
    ) {
        mode = desired
        conn.refreshCapsIfChanged()
        scope.testScheduler.runCurrent()
    }

    private fun release(gate: CompletableDeferred<Unit>) {
        gate.complete(Unit)
        scope.testScheduler.runCurrent()
    }

    @Test
    fun `reverting caps while a PUT is pending converges the host to the final choice`() =
        runMgrTest { mgr, _ -> convergeRevertedCaps(mgr) }

    private fun convergeRevertedCaps(mgr: SatelliteConnectionManager) {
        val conn = registeredConnection(mgr)
        val gate = holdRequests()
        changeCaps(conn, BASE_WIRE_CAPS or ControllerDescriptor.CAP_MOTION)
        changeCaps(conn, BASE_WIRE_CAPS)

        release(gate)

        assertEquals(conn.descriptorFor(SLOT)?.toJson(), host.descriptor)
        assertEquals(
            BASE_WIRE_CAPS,
            conn.slots.value
                .getValue(SLOT)
                .lastAdvertisedCaps,
        )
    }

    @Test
    fun `reverting touchpad mode while a PUT is pending converges the host to off`() =
        runMgrTest { mgr, _ -> convergeRevertedTouchpadMode(mgr) }

    private fun convergeRevertedTouchpadMode(mgr: SatelliteConnectionManager) {
        val conn = registeredConnection(mgr)
        val gate = holdRequests()
        changeMode(conn, ControllerDescriptor.TOUCHPAD_MODE_DS4)
        changeMode(conn, ControllerDescriptor.TOUCHPAD_MODE_OFF)

        release(gate)

        assertEquals(conn.descriptorFor(SLOT)?.toJson(), host.descriptor)
        assertEquals(
            ControllerDescriptor.TOUCHPAD_MODE_OFF,
            conn.slots.value
                .getValue(SLOT)
                .lastAdvertisedTouchpadMode,
        )
    }

    @Test
    fun `successive controller changes never overlap their HTTP mutations`() = runMgrTest { mgr, _ -> serializeControllerChanges(mgr) }

    private fun serializeControllerChanges(mgr: SatelliteConnectionManager) {
        val conn = registeredConnection(mgr)
        val gate = holdRequests()
        changeCaps(conn, BASE_WIRE_CAPS or ControllerDescriptor.CAP_MOTION)
        changeCaps(conn, BASE_WIRE_CAPS or ControllerDescriptor.CAP_SPEAKER)

        assertEquals(1, host.peakRequests)
        release(gate)
        assertEquals(conn.descriptorFor(SLOT)?.toJson(), host.descriptor)
    }

    @Test
    fun `removing a slot while its settings PUT is pending leaves no host controller`() =
        runMgrTest { mgr, _ -> removeSlotDuringPendingPut(mgr) }

    private fun removeSlotDuringPendingPut(mgr: SatelliteConnectionManager) {
        val conn = registeredConnection(mgr)
        val gate = holdRequests()
        changeCaps(conn, BASE_WIRE_CAPS or ControllerDescriptor.CAP_MOTION)
        conn.detachSlot(SLOT)
        scope.testScheduler.runCurrent()

        assertEquals(1, host.peakRequests)
        release(gate)
        assertNull(host.descriptor)
    }

    @Test
    fun `a queued delete cannot remove the slot that reuses its controller index`() =
        runMgrTest { mgr, _ -> replaceSlotDuringPendingDelete(mgr) }

    private fun replaceSlotDuringPendingDelete(mgr: SatelliteConnectionManager) {
        val conn = registeredConnection(mgr)
        val gate = holdRequests()
        conn.detachSlot(SLOT)
        scope.testScheduler.runCurrent()
        conn.attachSlot(REPLACEMENT_SLOT, TYPE)
        scope.testScheduler.runCurrent()

        assertEquals(1, host.peakRequests)
        release(gate)
        assertEquals(conn.descriptorFor(REPLACEMENT_SLOT)?.toJson(), host.descriptor)
    }

    @Test
    fun `disconnect drops queued and changed descriptor work for its session`() =
        runMgrTest { mgr, _ -> discardDisconnectedControllerWork(mgr) }

    private fun discardDisconnectedControllerWork(mgr: SatelliteConnectionManager) {
        val conn = registeredConnection(mgr)
        val gate = holdRequests()
        changeCaps(conn, BASE_WIRE_CAPS or ControllerDescriptor.CAP_MOTION)
        changeCaps(conn, BASE_WIRE_CAPS or ControllerDescriptor.CAP_SPEAKER)
        mgr.disconnect(serverId)
        val callsBeforeReply = host.calls

        release(gate)

        assertEquals(callsBeforeReply, host.calls)
        assertEquals(SatelliteSessionState.Idle, conn.state.value)
        assertFalse(
            conn.slots.value
                .getValue(SLOT)
                .registered,
        )
    }

    @Test
    fun `a full session PUT waits for an older controller mutation before rotating`() =
        runMgrTest { mgr, _ -> rotateAfterControllerMutation(mgr) }

    private fun rotateAfterControllerMutation(mgr: SatelliteConnectionManager) {
        val conn = registeredConnection(mgr)
        val gate = holdRequests()
        changeCaps(conn, BASE_WIRE_CAPS or ControllerDescriptor.CAP_MOTION)
        coEvery { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            ok(sessionGrantBody().replace("\"epoch\":1", "\"epoch\":$NEXT_SESSION_EPOCH"))
        every { controllerRepo.getSendCounter(any()) } returns COUNTER_REPUSH_THRESHOLD
        scope.testScheduler.advanceTimeBy(HEARTBEAT_TICK_MS)
        scope.testScheduler.runCurrent()

        coVerify(exactly = 1) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
        release(gate)
        coVerify(exactly = 2) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(NEXT_SESSION_EPOCH, conn.lastAppliedEpoch)
    }

    @Test
    fun `a lost PUT acknowledgement still converges intent changed during the request`() =
        runMgrTest { mgr, _ -> convergeChangedIntentAfterLostAcknowledgement(mgr) }

    private fun convergeChangedIntentAfterLostAcknowledgement(mgr: SatelliteConnectionManager) {
        val conn = registeredConnection(mgr)
        val gate = holdRequests()
        host.reply = unreachable()
        changeCaps(conn, BASE_WIRE_CAPS or ControllerDescriptor.CAP_MOTION)
        changeCaps(conn, BASE_WIRE_CAPS)

        release(gate)

        assertEquals(conn.descriptorFor(SLOT)?.toJson(), host.descriptor)
        assertEquals(3, host.calls)
    }

    @Test
    fun `an unchanged failed PUT stops without continuously retrying`() = runMgrTest { mgr, _ -> stopAfterUnchangedFailedPut(mgr) }

    private fun stopAfterUnchangedFailedPut(mgr: SatelliteConnectionManager) {
        val conn = registeredConnection(mgr)
        host.reply = unreachable()

        changeCaps(conn, BASE_WIRE_CAPS or ControllerDescriptor.CAP_MOTION)

        assertEquals(2, host.calls)
        assertEquals(
            BASE_WIRE_CAPS,
            conn.slots.value
                .getValue(SLOT)
                .lastAdvertisedCaps,
        )
    }

    @Test
    fun `disconnect invalidates a queued rekey before an immediate reconnect sends its PUT`() =
        runMgrTest { mgr, _ -> reconnectAfterQueuedRekey(mgr) }

    private fun reconnectAfterQueuedRekey(mgr: SatelliteConnectionManager) {
        val conn = registeredConnection(mgr)
        val gate = holdRequests()
        changeCaps(conn, BASE_WIRE_CAPS or ControllerDescriptor.CAP_MOTION)
        every { controllerRepo.getSendCounter(any()) } returns COUNTER_REPUSH_THRESHOLD
        scope.testScheduler.advanceTimeBy(HEARTBEAT_TICK_MS)
        scope.testScheduler.runCurrent()
        assertEquals(SatelliteSessionState.Live, conn.state.value)

        mgr.disconnect(serverId)
        mgr.connect(server, ConnectIntent.USER_INITIATED)
        scope.testScheduler.runCurrent()
        coVerify(exactly = 1) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
        release(gate)

        coVerify(exactly = 2) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { discoveryRepo.disconnect(any(), any(), "conn_1", any(), any()) }
        assertEquals(SatelliteSessionState.Live, conn.state.value)
    }

    @Test
    fun `reconnect waits for session DELETE even when the host reuses the connection id`() =
        runMgrTest { mgr, _ -> reconnectAfterSessionDelete(mgr) }

    private fun reconnectAfterSessionDelete(mgr: SatelliteConnectionManager) {
        val conn = registeredConnection(mgr)
        val gate = CompletableDeferred<Unit>()
        val sessionHost = SessionHost(sessionGrantBody(), deleteGate = gate, initialExists = true)
        coEvery { discoveryRepo.disconnect(any(), any(), any(), any(), any()) } coAnswers { sessionHost.delete() }
        coEvery { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers { sessionHost.put() }

        mgr.disconnect(serverId)
        mgr.connect(server, ConnectIntent.USER_INITIATED)
        scope.testScheduler.runCurrent()
        assertEquals(SatelliteSessionState.Linking, conn.state.value)
        coVerify(exactly = 1) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
        release(gate)

        assertTrue(sessionHost.exists)
        assertEquals(SatelliteSessionState.Live, conn.state.value)
        assertEquals("conn_1", conn.connectionId)
        coVerify(exactly = 2) { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `stale session grant cleanup completes before reconnect reuses its connection id`() =
        runMgrTest { mgr, _ -> reconnectAfterStaleGrantCleanup(mgr) }

    private fun reconnectAfterStaleGrantCleanup(mgr: SatelliteConnectionManager) {
        stubLiveSession()
        val grantGate = CompletableDeferred<Unit>()
        val deleteGate = CompletableDeferred<Unit>()
        val sessionHost = SessionHost(sessionGrantBody(), firstGrantGate = grantGate, deleteGate = deleteGate)
        coEvery { discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers { sessionHost.put() }
        coEvery { discoveryRepo.disconnect(any(), any(), any(), any(), any()) } coAnswers { sessionHost.delete() }
        mgr.connect(server)
        scope.testScheduler.runCurrent()
        mgr.disconnect(serverId)
        mgr.connect(server, ConnectIntent.USER_INITIATED)

        release(grantGate)
        assertEquals(1, sessionHost.grants)
        release(deleteGate)

        assertEquals(2, sessionHost.grants)
        assertTrue(sessionHost.exists)
        assertEquals(SatelliteSessionState.Live, mgr.get(serverId)?.state?.value)
    }
}
