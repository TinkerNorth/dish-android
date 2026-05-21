// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [WakeStateController].
 *
 * The controller derives [WakeStateController.streamingSlotCount] +
 * [WakeStateController.shouldKeepScreenOn] from [ConnectionHub.bindings] ×
 * [ConnectionHub.connections] while ProcessLifecycle is STARTED, and owns the
 * partial wake lock. The tests drive the lifecycle through a stand-alone
 * [LifecycleRegistry] (createUnsafe so we don't need a Looper) and the hub
 * via [MutableStateFlow] stubs.
 *
 * PowerManager + WakeLock are mocked so we can assert acquire/release calls
 * without touching the real Android power service.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeStateControllerTest {
    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private lateinit var context: Context
    private lateinit var powerManager: PowerManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var hub: ConnectionHub
    private lateinit var scope: TestScope

    private val bindingsFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    private val connectionsFlow = MutableStateFlow<List<ConnectionSummary>>(emptyList())

    @Before
    fun setUp() {
        // Reset the shared upstream flows so a value leaked by an earlier test
        // can't pollute the next one. With the Composer-based derivation, the
        // forwarded `streamingSlotCount` is eager (it reflects whatever the
        // upstream holds the moment the composer is built); the prior
        // implementation hid leakage by only emitting after onStart.
        bindingsFlow.value = emptyMap()
        connectionsFlow.value = emptyList()
        wakeLock =
            mockk(relaxed = true) {
                // The controller checks isHeld inside both `acquire()` (to
                // short-circuit double acquisition) and `release()` (so we
                // don't release a lock that isn't held). For the test we
                // always return true: the controller already gates a fresh
                // acquire on `wakeLock == null`, and we want release() to
                // actually fire so we can verify the lifecycle.
                every { isHeld } returns true
            }
        powerManager =
            mockk {
                every { newWakeLock(any(), any()) } returns wakeLock
            }
        context =
            mockk {
                every { getSystemService(Context.POWER_SERVICE) } returns powerManager
            }
        hub =
            mockk(relaxed = true) {
                every { bindings } returns bindingsFlow
                every { connections } returns connectionsFlow
            }
        scope = TestScope(StandardTestDispatcher())
    }

    private fun buildAndStart(): Pair<WakeStateController, TestOwner> {
        val controller = WakeStateController(context, WakeStateComposer(hub, scope), scope)
        val owner = TestOwner()
        owner.registry.addObserver(controller)
        owner.registry.currentState = Lifecycle.State.STARTED
        scope.testScheduler.runCurrent()
        return controller to owner
    }

    private fun summary(
        id: String,
        live: LinkState,
    ) = ConnectionSummary(
        id = id,
        kind = ConnectionKind.SATELLITE,
        label = id,
        detail = "",
        live = live,
        boundSlotIds = emptyList(),
    )

    // ── Initial state ──────────────────────────────────────────────────────

    @Test
    fun `before ON_START both flows hold their initial defaults`() {
        val controller = WakeStateController(context, WakeStateComposer(hub, scope), scope)
        assertEquals(0, controller.streamingSlotCount.value)
        assertFalse(controller.shouldKeepScreenOn.value)
    }

    @Test
    fun `ON_START with empty hub leaves shouldKeepScreenOn false`() =
        runTest(scope.testScheduler) {
            val (controller, _) = buildAndStart()

            assertEquals(0, controller.streamingSlotCount.value)
            assertFalse(controller.shouldKeepScreenOn.value)
            verify(exactly = 0) { powerManager.newWakeLock(any(), any()) }
        }

    // ── Streaming-state derivation ─────────────────────────────────────────

    @Test
    fun `bound slot on CONNECTED connection sets shouldKeepScreenOn`() =
        runTest(scope.testScheduler) {
            val (controller, _) = buildAndStart()

            connectionsFlow.value = listOf(summary("s:1", LinkState.Connected))
            bindingsFlow.value = mapOf("virtual" to "s:1")
            scope.testScheduler.runCurrent()

            assertEquals(1, controller.streamingSlotCount.value)
            assertTrue(controller.shouldKeepScreenOn.value)
            verify(exactly = 1) {
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, any())
            }
            verify(exactly = 1) { wakeLock.acquire() }
        }

    @Test
    fun `bound slot on CONNECTING connection does NOT count as streaming`() =
        runTest(scope.testScheduler) {
            val (controller, _) = buildAndStart()

            connectionsFlow.value = listOf(summary("s:1", LinkState.Connecting))
            bindingsFlow.value = mapOf("virtual" to "s:1")
            scope.testScheduler.runCurrent()

            assertEquals(0, controller.streamingSlotCount.value)
            assertFalse(controller.shouldKeepScreenOn.value)
            verify(exactly = 0) { powerManager.newWakeLock(any(), any()) }
        }

    @Test
    fun `bound slot whose connection is missing from summaries does not count`() =
        runTest(scope.testScheduler) {
            val (controller, _) = buildAndStart()

            // Stale binding to a connection that no longer appears in
            // hub.connections (e.g. forgotten satellite): no streaming.
            bindingsFlow.value = mapOf("virtual" to "s:gone")
            scope.testScheduler.runCurrent()

            assertEquals(0, controller.streamingSlotCount.value)
            assertFalse(controller.shouldKeepScreenOn.value)
        }

    @Test
    fun `multiple bound slots on one CONNECTED connection count separately`() =
        runTest(scope.testScheduler) {
            val (controller, _) = buildAndStart()

            connectionsFlow.value = listOf(summary("s:1", LinkState.Connected))
            bindingsFlow.value = mapOf("virtual" to "s:1", "5" to "s:1")
            scope.testScheduler.runCurrent()

            assertEquals(2, controller.streamingSlotCount.value)
            assertTrue(controller.shouldKeepScreenOn.value)
        }

    @Test
    fun `releasing the last bound connection turns shouldKeepScreenOn back off`() =
        runTest(scope.testScheduler) {
            val (controller, _) = buildAndStart()

            connectionsFlow.value = listOf(summary("s:1", LinkState.Connected))
            bindingsFlow.value = mapOf("virtual" to "s:1")
            scope.testScheduler.runCurrent()
            assertTrue(controller.shouldKeepScreenOn.value)

            // Connection drops back to IDLE — the controller should release.
            connectionsFlow.value = listOf(summary("s:1", LinkState.Saved))
            scope.testScheduler.runCurrent()

            assertEquals(0, controller.streamingSlotCount.value)
            assertFalse(controller.shouldKeepScreenOn.value)
            verify(exactly = 1) { wakeLock.release() }
        }

    @Test
    fun `repeated CONNECTED state does not re-acquire the wake lock`() =
        runTest(scope.testScheduler) {
            val (controller, _) = buildAndStart()

            connectionsFlow.value = listOf(summary("s:1", LinkState.Connected))
            bindingsFlow.value = mapOf("virtual" to "s:1")
            scope.testScheduler.runCurrent()

            // Trigger another emission that doesn't change the keep-on
            // boolean — count flips from 1 to 2 but shouldKeepScreenOn stays
            // true. No second acquire.
            bindingsFlow.value = mapOf("virtual" to "s:1", "5" to "s:1")
            scope.testScheduler.runCurrent()

            assertEquals(2, controller.streamingSlotCount.value)
            assertTrue(controller.shouldKeepScreenOn.value)
            verify(exactly = 1) { powerManager.newWakeLock(any(), any()) }
            verify(exactly = 1) { wakeLock.acquire() }
        }

    // ── ON_STOP ────────────────────────────────────────────────────────────

    @Test
    fun `ON_STOP releases the wake lock and zeros both flows`() =
        runTest(scope.testScheduler) {
            val (controller, owner) = buildAndStart()
            connectionsFlow.value = listOf(summary("s:1", LinkState.Connected))
            bindingsFlow.value = mapOf("virtual" to "s:1")
            scope.testScheduler.runCurrent()
            assertTrue(controller.shouldKeepScreenOn.value)

            owner.registry.currentState = Lifecycle.State.CREATED // ON_STOP
            scope.testScheduler.runCurrent()

            assertEquals(0, controller.streamingSlotCount.value)
            assertFalse(controller.shouldKeepScreenOn.value)
            verify(exactly = 1) { wakeLock.release() }
        }

    @Test
    fun `re-START after STOP starts a fresh collector and re-derives state`() =
        runTest(scope.testScheduler) {
            val (controller, owner) = buildAndStart()
            connectionsFlow.value = listOf(summary("s:1", LinkState.Connected))
            bindingsFlow.value = mapOf("virtual" to "s:1")
            scope.testScheduler.runCurrent()

            owner.registry.currentState = Lifecycle.State.CREATED
            scope.testScheduler.runCurrent()
            assertFalse(controller.shouldKeepScreenOn.value)

            owner.registry.currentState = Lifecycle.State.STARTED
            scope.testScheduler.runCurrent()

            // Same hub state — collector picks up the still-CONNECTED summary
            // and shouldKeepScreenOn flips back to true. Because release() in
            // onStop nulls the cached `wakeLock` reference, the next acquire
            // path goes through `powerManager.newWakeLock` a second time.
            assertTrue(controller.shouldKeepScreenOn.value)
            assertEquals(1, controller.streamingSlotCount.value)
            verify(exactly = 2) { powerManager.newWakeLock(any(), any()) }
        }

    @Test
    fun `ON_STOP without an active wake lock is a no-op on release`() =
        runTest(scope.testScheduler) {
            val (_, owner) = buildAndStart()
            // No streaming ever happened — no lock acquired.
            owner.registry.currentState = Lifecycle.State.CREATED
            scope.testScheduler.runCurrent()

            verify(exactly = 0) { wakeLock.acquire() }
            verify(exactly = 0) { wakeLock.release() }
        }
}
