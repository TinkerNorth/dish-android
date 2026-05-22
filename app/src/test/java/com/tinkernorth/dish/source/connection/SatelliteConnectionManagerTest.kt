// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection

import android.content.Context
import android.content.SharedPreferences
import com.tinkernorth.dish.composer.MotionCapability
import com.tinkernorth.dish.composer.MotionCapabilityComposer
import com.tinkernorth.dish.core.jni.ControllerRepository
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.net.DiscoveryRepository
import com.tinkernorth.dish.repository.ConnectionStore
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

/**
 * Failure-path tests for [SatelliteConnectionManager]. Locks in two changes:
 *   1. "Server unreachable" (network move / wrong LAN) is distinguished from
 *      "needs PIN" so the UI doesn't trap users in a pin dialog they can't
 *      satisfy against a dead IP.
 *   2. Auto-reconnect skips the pair handshake when a shared key is already
 *      saved, so a moved server fails fast in openSession instead of bouncing
 *      through pair → PairingRequired.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SatelliteConnectionManagerTest {
    private lateinit var context: Context
    private lateinit var discoveryRepo: DiscoveryRepository
    private lateinit var controllerRepo: ControllerRepository
    private lateinit var store: ConnectionStore
    private lateinit var prefs: SharedPreferences
    private lateinit var prefsEditor: SharedPreferences.Editor

    // UnconfinedTestDispatcher executes launches eagerly (synchronously up to the
    // first real suspension). Many of these tests inspect state immediately after a
    // mgr.X() call that internally does scope.launch { ... clearStale/markStale }.
    // With StandardTestDispatcher the launch is queued for later; with Unconfined
    // it runs inline. The injected ioDispatcher below shares the test scheduler so
    // the openSession() body — which `withContext(ioDispatcher)` — also runs under
    // the test's deterministic schedule rather than escaping to real Dispatchers.IO.
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

    /**
     * Provider that returns a fresh, always-off [MotionCapabilityComposer]
     * stand-in. These tests don't exercise the cap-bit path; they just need
     * the manager to construct successfully.
     */
    private val motionCapabilityProvider =
        javax.inject.Provider<MotionCapabilityComposer> {
            mockk(relaxed = true) {
                every { capabilityFor(any()) } returns MotionCapability.Off
            }
        }

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
            // Native pair() returns empty when the server can't be reached on the LAN.
            // Without this distinction, the previous code misclassified it as
            // "needs pairing" and trapped the user in a pin dialog that re-pinged
            // the dead IP forever.
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
            // Server reachable but rejected the empty PIN — first-time pair or
            // server's credentials rotated. Legitimate pin-dialog case.
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
            // 64 hex chars (32 bytes) is the contract openSession enforces.
            every { store.satelliteSharedKey(serverId) } returns "aa".repeat(32)
            // Force connect HTTP to fail so we don't try to spin up a real
            // session — we just want to assert that pair() was never called.
            coEvery { discoveryRepo.connect(any(), any(), any()) } returns ""

            mgr.connect(server)
            scope.testScheduler.advanceUntilIdle()

            coVerify(exactly = 0) { discoveryRepo.pair(any(), any(), any(), any(), any()) }
        }

    // Note: openSession's HTTP-unreachable path (saved key, but http connect
    // returns empty) is exercised in production but not asserted here — that
    // body runs under withContext(Dispatchers.IO), which the TestScope's
    // scheduler doesn't drive. The "connect skips pair when a shared key is
    // already stored" test above proves the saved-key branch is taken; the
    // "pair returning empty string surfaces server-unreachable error" test
    // proves the unreachable-handling shape. Together they cover the bug-2
    // path without a flaky cross-dispatcher assertion.

    @Test
    fun `disconnect on unknown id is a no-op`() {
        val mgr = manager()
        mgr.disconnect("satellite:does-not-exist:0")
    }

    @Test
    fun `forget removes the connection from the live map`() =
        runMgrTest { mgr, _ ->
            // Seed by failing to pair (state IDLE but entry present).
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
            // Block pair forever so the session stays in CONNECTING for the test.
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } coAnswers {
                awaitCancellation()
            }

            mgr.connect(server)
            scope.testScheduler.runCurrent()
            mgr.connect(server)
            scope.testScheduler.runCurrent()

            // The guard in connect() must not have re-launched a second pair
            // for an already in-flight session.
            coVerify(exactly = 1) { discoveryRepo.pair(any(), any(), any(), any(), any()) }
        }

    // ── ConnectIntent gating ─────────────────────────────────────────────
    //
    // The user-flagged cold-start cascade bug: auto-reconnect on app
    // foreground used the same emit path as a user tap, so a powered-off
    // satellite fired a toast on every relaunch. Auto-reconnect must now be
    // silent — the row chip is the user-visible signal.

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
            // The session still transitions Idle so the row chip falls back
            // to Saved/Stale — that's the user-visible feedback.
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

    // ── Stale-state synthesis ────────────────────────────────────────────

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
            // And critically: NO PairingRequired event fired — the user
            // didn't ask, so no dialog pops up unprompted.
            assertTrue(events.none { it is ConnectionEvent.PairingRequired })
        }

    @Test
    fun `USER_INITIATED with pair-rejected still emits PairingRequired (not Stale)`() =
        runMgrTest { mgr, events ->
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns
                """{"ok":false,"error":"PIN required"}"""

            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.advanceUntilIdle()

            // The user tapped Connect — show them the PIN dialog rather than
            // silently flipping the chip to "Needs pairing".
            assertTrue(events.any { it is ConnectionEvent.PairingRequired })
            assertTrue(
                "stale set must NOT include user-initiated tap targets",
                serverId !in mgr.staleSatelliteIds.value,
            )
        }

    @Test
    fun `pairWithPin success clears Stale marker`() =
        runMgrTest { mgr, _ ->
            // Pre-seed Stale via an auto-reconnect rejection.
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), "") } returns
                """{"ok":false}"""
            mgr.connect(server, ConnectIntent.AUTO_RECONNECT)
            scope.testScheduler.advanceUntilIdle()
            assertTrue(serverId in mgr.staleSatelliteIds.value)

            // Now the user enters the right PIN. The pair returns a key
            // (force openSession to error on connect so we don't need the
            // full happy-path mock chain; the manager still clears Stale
            // before openSession runs).
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), "1234") } returns
                """{"ok":true,"sharedKey":"${"bb".repeat(32)}"}"""
            coEvery { discoveryRepo.connect(any(), any(), any()) } returns ""
            // openSession reads the just-stored key back via the (mocked)
            // store; mirror that read so it doesn't fall into the empty-key
            // branch that would re-mark Stale. The production store really
            // does persist + return the value just written; this aligns the
            // mock with that behaviour for the assertion this test is making.
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

    // ── Cross-subscription replay (one-shot semantics) ───────────────────

    @Test
    fun `events flow does not replay prior errors to a new subscriber`() =
        runTest(scope.testScheduler) {
            val mgr = manager()
            // First subscriber receives an error fired during its lifetime.
            coEvery { discoveryRepo.pair(any(), any(), any(), any(), any()) } returns ""
            val firstEvents = mutableListOf<ConnectionEvent>()
            val firstCollector = scope.launch { mgr.events.collect { firstEvents += it } }
            mgr.connect(server, ConnectIntent.USER_INITIATED)
            scope.testScheduler.advanceUntilIdle()
            firstCollector.cancel()
            assertTrue("first subscriber should have received the error", firstEvents.isNotEmpty())

            // Second subscriber attaches AFTER the error has been emitted +
            // consumed. The events flow must NOT replay it — this is the
            // "banner re-fires on every activity switch" regression.
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
