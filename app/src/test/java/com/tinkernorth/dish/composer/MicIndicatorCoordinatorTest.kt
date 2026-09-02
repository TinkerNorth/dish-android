// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.source.audio.MicCapturePlan
import com.tinkernorth.dish.source.audio.MicCaptureTarget
import com.tinkernorth.dish.source.audio.MicIndicatorState
import com.tinkernorth.dish.source.store.MicMuteStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The write half of the app-wide mic surfaces: one tap, every armed slot. The chip and the
 * notification action both land here, so what is pinned is that a tap converges the whole
 * device onto one mute state and that a stale tap with nothing armed writes nothing.
 */
class MicIndicatorCoordinatorTest {
    private val virtual = MicCaptureTarget("virtual", "satellite:abc")
    private val pad = MicCaptureTarget("-1000", "satellite:abc")

    private val plans = MutableStateFlow(MicCapturePlan.IDLE)
    private val mute = MicMuteStore()
    private val coordinator =
        MicIndicatorCoordinator(
            micCapture = mockk<MicCaptureComposer> { every { state } returns plans },
            micMute = mute,
        )

    @Test
    fun `a tap while live mutes all armed slots at once`() {
        plans.value = MicCapturePlan(armed = setOf(virtual, pad), delivering = setOf(virtual, pad))
        coordinator.toggleAll()
        assertTrue(mute.isMuted("virtual"))
        assertTrue(mute.isMuted("-1000"))
    }

    @Test
    fun `a mixed set converges onto muted rather than flipping per slot`() {
        mute.setMuted("-1000", true)
        plans.value = MicCapturePlan(armed = setOf(virtual, pad), delivering = setOf(virtual))
        coordinator.toggleAll()
        assertTrue("the live slot muted", mute.isMuted("virtual"))
        assertTrue("the muted slot stayed muted", mute.isMuted("-1000"))
    }

    @Test
    fun `a tap while muted unmutes all armed slots`() {
        mute.setMuted("virtual", true)
        mute.setMuted("-1000", true)
        plans.value = MicCapturePlan(armed = setOf(virtual, pad), delivering = emptySet())
        coordinator.toggleAll()
        assertFalse(mute.isMuted("virtual"))
        assertFalse(mute.isMuted("-1000"))
    }

    @Test
    fun `a tap with nothing armed writes no mute at all`() {
        // A notification action can outlive the session it was posted for; acting on it must
        // not leave mutes behind for the next session to trip over.
        mute.setMuted("virtual", true)
        plans.value = MicCapturePlan.IDLE
        coordinator.toggleAll()
        assertTrue("the stale mute is left exactly as it was", mute.isMuted("virtual"))
    }

    @Test
    fun `the exposed state is the indicator rule over the current plan`() =
        runTest {
            plans.value = MicCapturePlan.IDLE
            assertEquals(MicIndicatorState.HIDDEN, coordinator.state.first())
            plans.value = MicCapturePlan(armed = setOf(virtual), delivering = setOf(virtual))
            assertEquals(MicIndicatorState.LIVE, coordinator.state.first())
            plans.value = MicCapturePlan(armed = setOf(virtual), delivering = emptySet())
            assertEquals(MicIndicatorState.MUTED, coordinator.state.first())
        }
}
