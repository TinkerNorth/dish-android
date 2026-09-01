// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.repository.SatelliteCapabilitiesRepository
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads the host's live state when a link goes Live. The controller-audio verdict lives only in
 * that document, so "who reads it, and when" is the whole behaviour: before this existed the only
 * reader was the configure screen, and a session restored by auto-reconnect streamed with the
 * microphone and the controller speaker switched off because unknown reads as off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostCapabilitiesProbeTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val server = DiscoveredServer(name = "PC", ip = "1.1.1.1", httpPort = 9877)
    private val connsFlow = MutableStateFlow<Map<String, SatelliteConnection>>(emptyMap())
    private val satellite =
        mockk<SatelliteConnectionManager>(relaxed = true) { every { connections } returns connsFlow }
    private val capabilitiesRepo = mockk<SatelliteCapabilitiesRepository>(relaxed = true)

    /** [sessionHandle] is mutable because a reconnect is a NEW session, which is what dedupes. */
    private fun conn(
        connId: String,
        session: MutableStateFlow<SatelliteSessionState>,
        sessionHandle: AtomicInteger = AtomicInteger(1),
    ): SatelliteConnection =
        mockk(relaxed = true) {
            every { id } returns connId
            every { state } returns session
            every { handle } answers { sessionHandle.get() }
            every { server } returns MutableStateFlow(this@HostCapabilitiesProbeTest.server)
        }

    private fun startProbe() = HostCapabilitiesProbe(satellite, capabilitiesRepo, scope).start()

    @Test
    fun `a link that reaches Live is probed`() {
        val session = MutableStateFlow(SatelliteSessionState.Linking)
        connsFlow.value = mapOf(HOST to conn(HOST, session))
        startProbe()
        scope.testScheduler.advanceUntilIdle()
        coVerify(exactly = 0) { capabilitiesRepo.refresh(any(), any()) }

        session.value = SatelliteSessionState.Live
        scope.testScheduler.advanceUntilIdle()
        coVerify(exactly = 1) { capabilitiesRepo.refresh(server, HOST) }
    }

    @Test
    fun `a satellite already Live when the probe starts is probed too`() {
        // Auto-reconnect at startup is exactly this shape: the session is up before anything is
        // watching it, and nobody opens the configure screen.
        connsFlow.value = mapOf(HOST to conn(HOST, MutableStateFlow(SatelliteSessionState.Live)))
        startProbe()
        scope.testScheduler.advanceUntilIdle()
        coVerify(exactly = 1) { capabilitiesRepo.refresh(server, HOST) }
    }

    @Test
    fun `a heartbeat blip is not a fresh Live`() {
        // Live to Faltering and back is one session recovering, not new news about the host, and
        // re-probing on every recovery would be chatter.
        val session = MutableStateFlow(SatelliteSessionState.Live)
        connsFlow.value = mapOf(HOST to conn(HOST, session))
        startProbe()
        scope.testScheduler.advanceUntilIdle()

        session.value = SatelliteSessionState.Faltering
        session.value = SatelliteSessionState.Live
        session.value = SatelliteSessionState.Faltering
        session.value = SatelliteSessionState.Live
        scope.testScheduler.advanceUntilIdle()
        coVerify(exactly = 1) { capabilitiesRepo.refresh(server, HOST) }
    }

    @Test
    fun `a reconnect is probed again, because the host's switch may have moved`() {
        val session = MutableStateFlow(SatelliteSessionState.Live)
        val handle = AtomicInteger(1)
        connsFlow.value = mapOf(HOST to conn(HOST, session, handle))
        startProbe()
        scope.testScheduler.advanceUntilIdle()

        handle.set(2)
        session.value = SatelliteSessionState.Idle
        scope.testScheduler.advanceUntilIdle()
        session.value = SatelliteSessionState.Live
        scope.testScheduler.advanceUntilIdle()
        coVerify(exactly = 2) { capabilitiesRepo.refresh(server, HOST) }
    }

    @Test
    fun `a new session seen only as a blip is still probed, because the handle moved`() {
        // A drop and reconnect the collector never observes as down: a StateFlow conflates, so the
        // states in between compress to the same shape a heartbeat wobble has. A transition test
        // would read that as one continuous session and leave the host's verdict stale; the handle
        // is what says a new session was negotiated, and it is why the dedupe keys on that.
        val session = MutableStateFlow(SatelliteSessionState.Live)
        val handle = AtomicInteger(1)
        connsFlow.value = mapOf(HOST to conn(HOST, session, handle))
        startProbe()
        scope.testScheduler.advanceUntilIdle()

        session.value = SatelliteSessionState.Faltering
        scope.testScheduler.advanceUntilIdle()
        handle.set(2)
        session.value = SatelliteSessionState.Live
        scope.testScheduler.advanceUntilIdle()
        coVerify(exactly = 2) { capabilitiesRepo.refresh(server, HOST) }
    }

    @Test
    fun `a session already gone is not probed, and does not block a later one`() {
        // handle is -1 until the session PUT lands; a Live reading without one is a session that
        // died between the two reads, and recording it would poison the dedupe.
        val session = MutableStateFlow(SatelliteSessionState.Live)
        val handle = AtomicInteger(-1)
        connsFlow.value = mapOf(HOST to conn(HOST, session, handle))
        startProbe()
        scope.testScheduler.advanceUntilIdle()
        coVerify(exactly = 0) { capabilitiesRepo.refresh(any(), any()) }

        handle.set(3)
        session.value = SatelliteSessionState.Linking
        scope.testScheduler.advanceUntilIdle()
        session.value = SatelliteSessionState.Live
        scope.testScheduler.advanceUntilIdle()
        coVerify(exactly = 1) { capabilitiesRepo.refresh(server, HOST) }
    }

    @Test
    fun `each satellite is watched independently`() {
        val first = MutableStateFlow(SatelliteSessionState.Live)
        val second = MutableStateFlow(SatelliteSessionState.Linking)
        connsFlow.value = mapOf(HOST to conn(HOST, first))
        startProbe()
        scope.testScheduler.advanceUntilIdle()

        connsFlow.value = mapOf(HOST to conn(HOST, first), OTHER to conn(OTHER, second))
        scope.testScheduler.advanceUntilIdle()
        coVerify(exactly = 0) { capabilitiesRepo.refresh(server, OTHER) }

        second.value = SatelliteSessionState.Live
        scope.testScheduler.advanceUntilIdle()
        coVerify(exactly = 1) { capabilitiesRepo.refresh(server, HOST) }
        coVerify(exactly = 1) { capabilitiesRepo.refresh(server, OTHER) }
    }

    @Test
    fun `a forgotten satellite is no longer watched`() {
        val session = MutableStateFlow(SatelliteSessionState.Linking)
        connsFlow.value = mapOf(HOST to conn(HOST, session))
        startProbe()
        scope.testScheduler.advanceUntilIdle()

        // Forgetting drops it from the map; the watcher must go with it rather than collecting a
        // connection nothing else holds.
        connsFlow.value = emptyMap()
        scope.testScheduler.advanceUntilIdle()
        session.value = SatelliteSessionState.Live
        scope.testScheduler.advanceUntilIdle()
        coVerify(exactly = 0) { capabilitiesRepo.refresh(any(), any()) }
    }

    @Test
    fun `a satellite added back after being forgotten is watched afresh`() {
        val session = MutableStateFlow(SatelliteSessionState.Linking)
        connsFlow.value = mapOf(HOST to conn(HOST, session))
        startProbe()
        scope.testScheduler.advanceUntilIdle()
        connsFlow.value = emptyMap()
        scope.testScheduler.advanceUntilIdle()

        connsFlow.value = mapOf(HOST to conn(HOST, MutableStateFlow(SatelliteSessionState.Live)))
        scope.testScheduler.advanceUntilIdle()
        coVerify(exactly = 1) { capabilitiesRepo.refresh(server, HOST) }
    }

    private companion object {
        const val HOST = "satellite:1.1.1.1:9876"
        const val OTHER = "satellite:2.2.2.2:9876"
    }
}
