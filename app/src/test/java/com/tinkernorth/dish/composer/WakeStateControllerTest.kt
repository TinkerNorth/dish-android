// SPDX-License-Identifier: LGPL-3.0-or-later

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
        bindingsFlow.value = emptyMap()
        connectionsFlow.value = emptyList()
        wakeLock =
            mockk(relaxed = true) {
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
            // Timeout overload required for lint's WakelockTimeout check.
            verify(exactly = 1) { wakeLock.acquire(any<Long>()) }
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

            bindingsFlow.value = mapOf("virtual" to "s:1", "5" to "s:1")
            scope.testScheduler.runCurrent()

            assertEquals(2, controller.streamingSlotCount.value)
            assertTrue(controller.shouldKeepScreenOn.value)
            verify(exactly = 1) { powerManager.newWakeLock(any(), any()) }
            verify(exactly = 1) { wakeLock.acquire(any<Long>()) }
        }

    @Test
    fun `ON_STOP releases the wake lock and zeros both flows`() =
        runTest(scope.testScheduler) {
            val (controller, owner) = buildAndStart()
            connectionsFlow.value = listOf(summary("s:1", LinkState.Connected))
            bindingsFlow.value = mapOf("virtual" to "s:1")
            scope.testScheduler.runCurrent()
            assertTrue(controller.shouldKeepScreenOn.value)

            owner.registry.currentState = Lifecycle.State.CREATED
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

            // onStop nulls the cached wakeLock so onStart goes through newWakeLock again.
            assertTrue(controller.shouldKeepScreenOn.value)
            assertEquals(1, controller.streamingSlotCount.value)
            verify(exactly = 2) { powerManager.newWakeLock(any(), any()) }
        }

    @Test
    fun `ON_STOP without an active wake lock is a no-op on release`() =
        runTest(scope.testScheduler) {
            val (_, owner) = buildAndStart()
            owner.registry.currentState = Lifecycle.State.CREATED
            scope.testScheduler.runCurrent()

            verify(exactly = 0) { wakeLock.acquire() }
            verify(exactly = 0) { wakeLock.release() }
        }

    @Test
    fun `an upstream change after ON_STOP does not repopulate the streaming count`() =
        runTest(scope.testScheduler) {
            val (controller, owner) = buildAndStart()
            connectionsFlow.value = listOf(summary("s:1", LinkState.Connected))
            bindingsFlow.value = mapOf("virtual" to "s:1")
            scope.testScheduler.runCurrent()
            assertEquals(1, controller.streamingSlotCount.value)

            owner.registry.currentState = Lifecycle.State.CREATED
            scope.testScheduler.runCurrent()
            assertEquals(0, controller.streamingSlotCount.value)

            // Once stopped, a late upstream change must keep the count at zero so the foreground service
            // is not restarted while the app is backgrounded.
            connectionsFlow.value = listOf(summary("s:1", LinkState.Connected))
            bindingsFlow.value = mapOf("virtual" to "s:1", "5" to "s:1")
            scope.testScheduler.runCurrent()
            assertEquals(0, controller.streamingSlotCount.value)
        }
}
