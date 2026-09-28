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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before

// What wireCaps resolves for a pad with nothing else on.
internal const val BASE_WIRE_CAPS =
    com.tinkernorth.dish.core.net.ControllerDescriptor.CAP_ANALOG_TRIGGERS or
        com.tinkernorth.dish.core.net.ControllerDescriptor.CAP_RUMBLE

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

    protected fun manager(): SatelliteConnectionManager =
        SatelliteConnectionManager(
            context = context,
            scope = scope,
            discoveryRepo = discoveryRepo,
            controllerRepo = controllerRepo,
            store = store,
            json = json,
            ioDispatcher = ioDispatcher,
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

    protected fun runMgrTest(block: suspend (SatelliteConnectionManager, MutableList<ConnectionEvent>) -> Unit) =
        runTest(scope.testScheduler) {
            val mgr = manager()
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
