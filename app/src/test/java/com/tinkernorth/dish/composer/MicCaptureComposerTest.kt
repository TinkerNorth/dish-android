// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.source.audio.MicCapturePlan
import com.tinkernorth.dish.source.audio.MicCaptureTarget
import com.tinkernorth.dish.source.store.MicMuteStore
import com.tinkernorth.dish.source.system.MicPermissionGate
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four moving facts folded into one plan. What is asserted here is the FOLDING: that each
 * input reaches the rule, and that the connection kinds which cannot carry controller audio are
 * excluded before the rule ever sees them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MicCaptureComposerTest {
    private val scope = TestScope(StandardTestDispatcher())

    private val bindings = MutableStateFlow(mapOf(SLOT to CONN))
    private val connections = MutableStateFlow(listOf(summary(LinkState.Connected)))
    private val caps = MutableStateFlow(mapOf(SLOT to capsWithMic(on = true)))
    private val granted = MutableStateFlow(true)
    private val mute = MicMuteStore()

    private val hub =
        mockk<ConnectionCoordinator> {
            every { this@mockk.bindings } returns this@MicCaptureComposerTest.bindings
            every { this@mockk.connections } returns this@MicCaptureComposerTest.connections
        }

    private val capabilities = mockk<CapabilityComposer> { every { state } returns caps }

    // Qualified: MicPermissionGate has its own `granted` member, which would shadow the field.
    private val permission =
        mockk<MicPermissionGate> { every { state } returns this@MicCaptureComposerTest.granted }

    private val composer = MicCaptureComposer(hub, capabilities, permission, mute, scope)

    /**
     * Settle the composer and read it. Touching [MicCaptureComposer.state] is what starts the
     * eager collection (it is lazy), so the touch has to happen before the scheduler runs or the
     * first emission never lands.
     */
    private fun plan(): MicCapturePlan {
        composer.state
        scope.testScheduler.runCurrent()
        return composer.state.value
    }

    @Test
    fun `a live satellite slot with the microphone on and granted is delivering`() =
        runTest(scope.testScheduler) {
            assertEquals(setOf(MicCaptureTarget(SLOT, CONN)), plan().armed)
            assertEquals(setOf(MicCaptureTarget(SLOT, CONN)), plan().delivering)
        }

    @Test
    fun `an unstable link still streams, the way the gamepad reports do`() =
        runTest(scope.testScheduler) {
            connections.value = listOf(summary(LinkState.Unstable))
            assertTrue(plan().capturing)
        }

    @Test
    fun `a link that is not up stops the microphone`() =
        runTest(scope.testScheduler) {
            connections.value = listOf(summary(LinkState.Connecting))
            assertEquals(setOf<MicCaptureTarget>(), plan().armed)
        }

    @Test
    fun `only a satellite carries controller audio`() =
        runTest(scope.testScheduler) {
            // The Moonlight control protocol has no microphone channel and a Bluetooth HID pad has
            // no audio endpoints to be, so neither can arm one however the toggles read.
            for (kind in listOf(ConnectionKind.MOONLIGHT, ConnectionKind.BLUETOOTH)) {
                connections.value = listOf(summary(LinkState.Connected, kind = kind))
                assertEquals("$kind must not arm a microphone", emptySet<MicCaptureTarget>(), plan().armed)
            }
        }

    @Test
    fun `revoking the permission at runtime stops an already running capture`() =
        runTest(scope.testScheduler) {
            assertTrue(plan().capturing)
            granted.value = false
            assertEquals("a revoked grant disarms, it does not just stop delivery", emptySet<MicCaptureTarget>(), plan().armed)
        }

    @Test
    fun `muting keeps the slot armed and empties delivery`() =
        runTest(scope.testScheduler) {
            mute.setMuted(SLOT, true)
            assertEquals(setOf(MicCaptureTarget(SLOT, CONN)), plan().armed)
            assertTrue(plan().delivering.isEmpty())

            mute.setMuted(SLOT, false)
            assertTrue(plan().capturing)
        }

    @Test
    fun `the capability model gates it, toggle and runtime alike`() =
        runTest(scope.testScheduler) {
            caps.value = mapOf(SLOT to capsWithMic(on = false))
            assertTrue("a slot whose toggle is off never arms", plan().armed.isEmpty())

            // `live` and not `enabled`: a feature the runtime probe says is down must not capture
            // either, even with the toggle on.
            caps.value = mapOf(SLOT to capsWithMic(on = true, down = true))
            assertTrue("a runtime-down microphone never arms", plan().armed.isEmpty())
        }

    @Test
    fun `a slot with no capability entry at all contributes nothing`() =
        runTest(scope.testScheduler) {
            caps.value = emptyMap()
            assertTrue(plan().armed.isEmpty())
        }

    @Test
    fun `unbinding the slot empties the plan`() =
        runTest(scope.testScheduler) {
            assertTrue(plan().capturing)
            bindings.value = emptyMap()
            assertTrue(plan().armed.isEmpty())
        }

    private companion object {
        const val SLOT = "virtual"
        const val CONN = "satellite:abc"

        fun summary(
            live: LinkState,
            kind: ConnectionKind = ConnectionKind.SATELLITE,
        ) = ConnectionSummary(
            id = CONN,
            kind = kind,
            label = "Desk PC",
            detail = "",
            live = live,
            boundSlotIds = listOf(SLOT),
        )

        // Every layer permissive; the toggle and the runtime probe are what the tests move.
        fun capsWithMic(
            on: Boolean,
            down: Boolean = false,
        ): SlotCapabilities {
            val mic = CapabilitySet.of(Feature.MIC)
            return SlotCapabilities(
                controller = mic,
                transport = mic,
                type = mic,
                host = mic,
                userEnabled = if (on) mic else CapabilitySet.EMPTY,
                runtimeDown = if (down) mic else CapabilitySet.EMPTY,
            )
        }
    }
}
