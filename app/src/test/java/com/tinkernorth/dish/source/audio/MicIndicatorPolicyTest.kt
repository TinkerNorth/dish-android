// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The app-wide indicator rule and its one control, pinned over the whole plan matrix. The rule
 * reads only [MicCapturePlan]: mute is already folded into `delivering` by the composer, so the
 * matrix here is armed x delivering, not armed x per-slot mute.
 */
class MicIndicatorPolicyTest {
    private val a = MicCaptureTarget("virtual", "satellite:abc")
    private val b = MicCaptureTarget("-1000", "satellite:abc")

    private fun plan(
        armed: Set<MicCaptureTarget>,
        delivering: Set<MicCaptureTarget>,
    ) = MicCapturePlan(armed = armed, delivering = delivering)

    @Test
    fun `nothing armed is hidden, whatever else the plan says`() {
        assertEquals(MicIndicatorState.HIDDEN, MicIndicatorPolicy.of(MicCapturePlan.IDLE))
    }

    @Test
    fun `any delivering slot makes the microphone live`() {
        assertEquals(MicIndicatorState.LIVE, MicIndicatorPolicy.of(plan(setOf(a), setOf(a))))
        // Mixed: one slot muted, one delivering. The device still has a hot microphone, and
        // "some of it is muted" must never read as safe.
        assertEquals(MicIndicatorState.LIVE, MicIndicatorPolicy.of(plan(setOf(a, b), setOf(b))))
    }

    @Test
    fun `armed with nothing delivering is muted`() {
        assertEquals(MicIndicatorState.MUTED, MicIndicatorPolicy.of(plan(setOf(a), emptySet())))
        assertEquals(MicIndicatorState.MUTED, MicIndicatorPolicy.of(plan(setOf(a, b), emptySet())))
    }

    @Test
    fun `toggle-all on a live plan mutes every armed slot, muted ones included`() {
        // One state for the whole device: a tap on LIVE leaves nothing delivering, so a mixed
        // set converges instead of ping-ponging slot by slot.
        val order = MicIndicatorPolicy.toggleAll(plan(setOf(a, b), setOf(b)))
        assertEquals(setOf("virtual", "-1000"), order?.slotIds)
        assertEquals(true, order?.muted)
    }

    @Test
    fun `toggle-all on a muted plan unmutes every armed slot`() {
        val order = MicIndicatorPolicy.toggleAll(plan(setOf(a, b), emptySet()))
        assertEquals(setOf("virtual", "-1000"), order?.slotIds)
        assertEquals(false, order?.muted)
    }

    @Test
    fun `toggle-all with nothing armed orders nothing`() {
        // A stale tap (a notification action racing a teardown) must not write mutes for slots
        // that no longer capture.
        assertNull(MicIndicatorPolicy.toggleAll(MicCapturePlan.IDLE))
    }

    @Test
    fun `two taps from live land back on live`() {
        val live = plan(setOf(a, b), setOf(a, b))
        val first = MicIndicatorPolicy.toggleAll(live)!!
        assertEquals(true, first.muted)
        // Apply the first order: everything armed, nothing delivering.
        val muted = plan(setOf(a, b), emptySet())
        assertEquals(MicIndicatorState.MUTED, MicIndicatorPolicy.of(muted))
        val second = MicIndicatorPolicy.toggleAll(muted)!!
        assertEquals(false, second.muted)
        assertEquals(first.slotIds, second.slotIds)
    }
}
