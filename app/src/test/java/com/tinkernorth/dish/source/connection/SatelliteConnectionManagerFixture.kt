// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection

import android.content.Context
import android.content.SharedPreferences
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.core.jni.ControllerRepository
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.net.DiscoveryGateway
import com.tinkernorth.dish.core.net.HttpReply
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.source.store.SatelliteHostFacts
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatusStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// What wireCaps resolves for a pad with nothing else on.
internal const val BASE_WIRE_CAPS =
    com.tinkernorth.dish.core.net.ControllerDescriptor.CAP_ANALOG_TRIGGERS or
        com.tinkernorth.dish.core.net.ControllerDescriptor.CAP_RUMBLE

// How long a thread is given to reach the state a test waits for before the test fails instead of hanging.
private const val QUEUE_DEADLINE_NS = 10_000_000_000L

/**
 * A call landing on the manager from a thread of its own, the way the UI thread's taps land while a
 * worker runs a handshake. [awaitDone] waits for it and rethrows whatever it threw.
 */
internal class OnAnotherThread(
    block: () -> Unit,
) {
    @Volatile private var failure: Throwable? = null

    val thread = Thread(block)

    init {
        thread.setUncaughtExceptionHandler { _, thrown -> failure = thrown }
    }

    fun start() = thread.start()

    fun awaitDone() {
        thread.join()
        failure?.let { throw it }
    }
}

// Returns once [thread] is in [state]; fails rather than hangs should it never get there.
internal fun awaitThreadState(
    thread: Thread,
    state: Thread.State,
) {
    val deadline = System.nanoTime() + QUEUE_DEADLINE_NS
    while (thread.state != state) {
        check(System.nanoTime() < deadline) { "${thread.name} never reached $state" }
        Thread.yield()
    }
}

// Returns once [latch] opens; fails rather than hangs should it never open.
internal fun awaitOpened(latch: CountDownLatch) {
    check(latch.await(QUEUE_DEADLINE_NS, TimeUnit.NANOSECONDS)) { "the latch never opened" }
}

// Returns once [thread] is queued on a monitor another thread holds. In the steps these tests drive,
// the only monitor a caller of the manager can queue on is its transition lock.
internal fun awaitQueuedOnTheLock(thread: Thread) = awaitThreadState(thread, Thread.State.BLOCKED)

// The mocks, scope and manager factory every SatelliteConnectionManager suite drives; the suites
// extend it so each stays under the class-size gate without repeating any of it.
@OptIn(ExperimentalCoroutinesApi::class)
open class SatelliteConnectionManagerFixture {
    protected lateinit var context: Context
    protected lateinit var discoveryRepo: DiscoveryGateway
    protected lateinit var controllerRepo: ControllerRepository
    protected lateinit var store: ConnectionStore
    protected lateinit var prefs: SharedPreferences
    protected lateinit var prefsEditor: SharedPreferences.Editor

    protected val scope = TestScope(UnconfinedTestDispatcher())
    protected val ioDispatcher = UnconfinedTestDispatcher(scope.testScheduler)
    protected val json = Json { ignoreUnknownKeys = true }

    protected val server =
        DiscoveredServer(
            name = "Pc",
            ip = "10.0.0.5",
            udpPort = 9876,
            pairPort = 9878,
            httpPort = 9877,
        )
    protected val serverId = satelliteConnectionIdFor(server)

    protected fun reply(
        status: Int,
        body: String,
    ) = HttpReply(status, body, null)

    protected fun ok(body: String) = reply(200, body)

    protected fun unreachable() = reply(0, """{"error":"request failed: connect timed out"}""")

    protected fun identityMismatch() = HttpReply(0, """{"error":"request failed: hostname not verified"}""", null, pinMismatch = true)

    protected fun sessionGrantBody(): String =
        """{"connectionId":"conn_1","token":"00000001","sessionSalt":"0102030405060708",""" +
            """"epoch":1,"protocolVersion":2,"controllers":[],"hostFeatures":{"mouseControl":{"granted":false}}}"""

    protected fun matchingViewBody(epoch: Int): String =
        """{"connectionId":"conn_1","epoch":$epoch,"controllers":""" +
            """[{"ctrlIdx":0,"active":true,"appliedType":1,"touchpadMode":"off"}],""" +
            """"hostFeatures":{"mouseControl":{"granted":false}}}"""

    protected fun stubStoredKey() {
        every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
    }

    // A satellite that grants every session PUT and a socket that opens: the shortest road to Live.
    protected fun stubLiveSession() {
        stubStoredKey()
        coEvery {
            discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ok(sessionGrantBody())
        every { controllerRepo.openSocket(any(), any()) } returns 5
    }

    protected fun connectLive(mgr: SatelliteConnectionManager) {
        mgr.connect(server)
        scope.testScheduler.runCurrent()
        assertEquals(SatelliteSessionState.Live, mgr.get(serverId)?.state?.value)
    }

    // Each session PUT waits on its own gate, in call order, so a test decides when each answers.
    protected fun gatedSessionPuts(vararg bodies: String): List<CompletableDeferred<Unit>> {
        val gates = bodies.map { CompletableDeferred<Unit>() }
        var call = 0
        coEvery {
            discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            val mine = call++
            gates[mine].await()
            ok(bodies[mine])
        }
        return gates
    }

    // The session PUT reaches the satellite whatever becomes of its caller: the satellite grants
    // on arrival, and a caller cancelled meanwhile only loses the answer, as withContext does
    // to the blocking request under it.
    protected fun sessionPutGrantedRegardless(body: String): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        coEvery {
            discoveryRepo.putSession(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            withContext(NonCancellable) { gate.await() }
            currentCoroutineContext().ensureActive()
            ok(body)
        }
        return gate
    }

    // The pair round trip answers only when the test opens its gate, with [body].
    protected fun gatedPair(body: String): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        coEvery { discoveryRepo.pair(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
            ok(body)
        }
        return gate
    }

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

    // The composer's projection, held here so a test can move it the way a caps change would.
    protected val wireProjection =
        kotlinx.coroutines.flow.MutableStateFlow(
            emptyMap<String, com.tinkernorth.dish.composer.WireProjection>(),
        )

    // One shared instance: the manager pulls wire projections through the provider on every
    // descriptor build, so per-get() mocks would be unstubbable from a test body.
    protected val capabilityComposer: CapabilityComposer =
        mockk(relaxed = true) {
            every { state } returns
                kotlinx.coroutines.flow.MutableStateFlow(
                    emptyMap<String, com.tinkernorth.dish.core.model.SlotCapabilities>(),
                )
            every { wireCapsFor(any()) } returns BASE_WIRE_CAPS
            every { touchpadWireMode(any()) } returns "off"
            every { wireProjection } returns this@SatelliteConnectionManagerFixture.wireProjection
        }

    protected val capabilityProvider = javax.inject.Provider<CapabilityComposer> { capabilityComposer }

    protected val motionBackendStatusStore = SatelliteMotionBackendStatusStore()

    protected val hostFeaturesStore = SatelliteHostFeaturesStore()

    protected fun manager(io: CoroutineDispatcher = ioDispatcher): SatelliteConnectionManager =
        SatelliteConnectionManager(
            context = context,
            scope = scope,
            discoveryRepo = discoveryRepo,
            controllerRepo = controllerRepo,
            store = store,
            json = json,
            ioDispatcher = io,
            capabilityProvider = capabilityProvider,
            hostFacts =
                SatelliteHostFacts(
                    features = hostFeaturesStore,
                    runtime = mockk(),
                    motionBackend = motionBackendStatusStore,
                    catalog = mockk(),
                    capabilities = mockk(),
                ),
        )

    protected fun runMgrTest(
        io: CoroutineDispatcher = ioDispatcher,
        block: suspend (SatelliteConnectionManager, MutableList<ConnectionEvent>) -> Unit,
    ) = runTest(scope.testScheduler) {
        val mgr = manager(io)
        val events = mutableListOf<ConnectionEvent>()
        scope.launch { mgr.events.collect { events += it } }
        try {
            block(mgr, events)
        } finally {
            // A live session's heartbeat poll reschedules itself forever, and so does a silent
            // retry chain against an unreachable satellite, so the scheduler never goes idle
            // while either exists. Cancel everything the manager started before the final
            // drain, on assertion failure too, or the drain spins virtual time into OOM. This
            // runs after the body, so it cannot hide what the body asserted.
            scope.coroutineContext.job.cancelChildren()
            scope.testScheduler.advanceUntilIdle()
        }
    }
}
