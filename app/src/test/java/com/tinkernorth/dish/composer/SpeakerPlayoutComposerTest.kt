// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.source.audio.PadAudioRoute
import com.tinkernorth.dish.source.audio.PadAudioRoutes
import com.tinkernorth.dish.source.audio.SlotAudioRoutes
import com.tinkernorth.dish.source.audio.SpeakerPlayoutPlan
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The facts behind controller sound, folded into one plan. What is asserted here is the FOLDING:
 * that each input reaches the rule, that the addressing a frame carries (session handle plus
 * controller index) is resolved off the hot path, and that the connection kinds which cannot carry
 * controller audio are excluded before the rule ever sees them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeakerPlayoutComposerTest {
    private val scope = TestScope(StandardTestDispatcher())

    private val bindings = MutableStateFlow(mapOf(SLOT to CONN))
    private val connections = MutableStateFlow(listOf(summary(LinkState.Connected)))
    private val caps = MutableStateFlow(mapOf(SLOT to capsWithSpeaker(on = true)))
    private val slots = MutableStateFlow(mapOf(SLOT to binding(registered = true)))
    private val routeTable = MutableStateFlow<Map<Int, PadAudioRoute>>(emptyMap())
    private val padRoutes = HashMap<String, PadAudioRoute>()

    private val hub =
        mockk<ConnectionCoordinator> {
            every { this@mockk.bindings } returns this@SpeakerPlayoutComposerTest.bindings
            every { this@mockk.connections } returns this@SpeakerPlayoutComposerTest.connections
        }

    private val capabilities = mockk<CapabilityComposer> { every { state } returns caps }

    private val connection =
        mockk<SatelliteConnection> {
            every { handle } returns HANDLE
            every { this@mockk.slots } returns this@SpeakerPlayoutComposerTest.slots
        }

    private val satellite =
        mockk<SatelliteConnectionManager> {
            every { this@mockk.connections } returns MutableStateFlow(mapOf(CONN to connection))
        }

    private val routing =
        object : SlotAudioRoutes {
            override val changes get() = routeTable

            override fun forSlot(slotId: String) = padRoutes[slotId] ?: PadAudioRoute.NONE
        }

    private val composer = SpeakerPlayoutComposer(hub, capabilities, satellite, routing, scope)

    /**
     * Settle the composer and read it. Touching the state is what starts the eager collection (it
     * is lazy), so the touch has to happen before the scheduler runs or the first emission never
     * lands.
     */
    private fun plan(): SpeakerPlayoutPlan {
        composer.state
        scope.testScheduler.runCurrent()
        return composer.state.value
    }

    private fun voice() = plan().voices[SpeakerPlayoutPlan.routeKey(HANDLE, CTRL_IDX)]

    @Test
    fun `a live satellite slot with controller sound on plays, addressed the way frames arrive`() =
        runTest(scope.testScheduler) {
            val target = voice()!!
            assertEquals(SLOT, target.slotId)
            assertEquals(HANDLE, target.sessionHandle)
            assertEquals(CTRL_IDX, target.controllerIndex)
        }

    @Test
    fun `an unstable link keeps playing, the way the gamepad reports do`() =
        runTest(scope.testScheduler) {
            connections.value = listOf(summary(LinkState.Unstable))
            assertTrue(plan().playing)
        }

    @Test
    fun `a link that is not up closes the output`() =
        runTest(scope.testScheduler) {
            connections.value = listOf(summary(LinkState.Connecting))
            assertTrue(plan().voices.isEmpty())
        }

    @Test
    fun `only a satellite carries controller audio`() =
        runTest(scope.testScheduler) {
            for (kind in listOf(ConnectionKind.MOONLIGHT, ConnectionKind.BLUETOOTH)) {
                connections.value = listOf(summary(LinkState.Connected, kind = kind))
                assertTrue("$kind must not open an output", plan().voices.isEmpty())
            }
        }

    @Test
    fun `the capability model gates it, toggle and runtime alike`() =
        runTest(scope.testScheduler) {
            caps.value = mapOf(SLOT to capsWithSpeaker(on = false))
            assertTrue("a slot whose toggle is off never plays", plan().voices.isEmpty())

            // `live` and not `enabled`: a feature the runtime probe says is down must not play
            // either, even with the toggle on.
            caps.value = mapOf(SLOT to capsWithSpeaker(on = true, down = true))
            assertTrue("a runtime-down speaker never plays", plan().voices.isEmpty())

            caps.value = emptyMap()
            assertTrue("a slot with no capability entry contributes nothing", plan().voices.isEmpty())
        }

    @Test
    fun `an unregistered slot has no emulated pad yet and gets no output`() =
        runTest(scope.testScheduler) {
            slots.value = mapOf(SLOT to binding(registered = false))
            assertTrue(plan().voices.isEmpty())
        }

    @Test
    fun `unbinding the slot empties the plan`() =
        runTest(scope.testScheduler) {
            assertTrue(plan().playing)
            bindings.value = emptyMap()
            assertTrue(plan().voices.isEmpty())
        }

    @Test
    fun `a controller index change re-addresses the voice`() =
        runTest(scope.testScheduler) {
            assertTrue(plan().playing)
            slots.value = mapOf(SLOT to binding(registered = true, index = CTRL_IDX + 1))
            assertNull("the old address must stop playing", voice())
            assertEquals(
                SLOT,
                plan().voices[SpeakerPlayoutPlan.routeKey(HANDLE, CTRL_IDX + 1)]!!.slotId,
            )
        }

    @Test
    fun `the pad's own endpoint rides the plan and moves with the route table`() =
        runTest(scope.testScheduler) {
            assertEquals(0, voice()!!.playbackDeviceId)

            // The plan itself does not change when a route does, so the table has to be an input.
            padRoutes[SLOT] = PadAudioRoute(microphone = false, speaker = true, playbackDeviceId = PAD_ENDPOINT)
            routeTable.value = mapOf(PadAudioRoutes.key(0x054C, 0x0CE6) to padRoutes[SLOT]!!)
            assertEquals(PAD_ENDPOINT, voice()!!.playbackDeviceId)
        }

    private companion object {
        const val SLOT = "virtual"
        const val CONN = "satellite:abc"
        const val HANDLE = 7
        const val CTRL_IDX = 0
        const val PAD_ENDPOINT = 11

        fun binding(
            registered: Boolean,
            index: Int = CTRL_IDX,
        ) = SatelliteConnection.SlotBinding(
            controllerIndex = index,
            controllerType = 2,
            registered = registered,
        )

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
        fun capsWithSpeaker(
            on: Boolean,
            down: Boolean = false,
        ): SlotCapabilities {
            val speaker = CapabilitySet.of(Feature.SPEAKER)
            return SlotCapabilities(
                controller = speaker,
                transport = speaker,
                type = speaker,
                host = speaker,
                userEnabled = if (on) speaker else CapabilitySet.EMPTY,
                runtimeDown = if (down) speaker else CapabilitySet.EMPTY,
            )
        }
    }
}
