// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Exhaustive table tests for the pure placeholderTransition reducer extracted from
// PhysicalGamepadRegistry. The reducer is the single source of truth for how the transient
// placeholder booleans evolve; the imperative mutators only pick which devices it is applied to.
class PhysicalGamepadRegistryPlaceholderTest {
    private fun live(isUsbSynthetic: Boolean = false) =
        PlaceholderState(
            transitioning = false,
            needsReplug = false,
            restoreStuck = false,
            disconnectingTimeLeftSec = null,
            isUsbSynthetic = isUsbSynthetic,
        )

    // HoldAsTransitioning

    @Test
    fun `hold sets transitioning and clears the disconnect countdown`() {
        val start = live().copy(disconnectingTimeLeftSec = 3)
        val next = placeholderTransition(start, PlaceholderEvent.HoldAsTransitioning)
        assertTrue(next.transitioning)
        assertNull(next.disconnectingTimeLeftSec)
        assertFalse(next.needsReplug)
        assertFalse(next.restoreStuck)
    }

    @Test
    fun `hold from a clean live state just raises transitioning`() {
        val next = placeholderTransition(live(), PlaceholderEvent.HoldAsTransitioning)
        assertEquals(
            live().copy(transitioning = true),
            next,
        )
    }

    @Test
    fun `hold leaves needsReplug untouched`() {
        val start = live().copy(needsReplug = true)
        val next = placeholderTransition(start, PlaceholderEvent.HoldAsTransitioning)
        assertTrue(next.needsReplug)
        assertTrue(next.transitioning)
    }

    // SetSyntheticTransitioning

    @Test
    fun `setSyntheticTransitioning true raises only the loader flag`() {
        val next = placeholderTransition(live(isUsbSynthetic = true), PlaceholderEvent.SetSyntheticTransitioning(true))
        assertEquals(
            live(isUsbSynthetic = true).copy(transitioning = true),
            next,
        )
    }

    @Test
    fun `setSyntheticTransitioning false lowers only the loader flag`() {
        val start = live(isUsbSynthetic = true).copy(transitioning = true)
        val next = placeholderTransition(start, PlaceholderEvent.SetSyntheticTransitioning(false))
        assertFalse(next.transitioning)
    }

    @Test
    fun `setSyntheticTransitioning does not disturb restoreStuck`() {
        val start = live(isUsbSynthetic = true).copy(restoreStuck = true)
        val next = placeholderTransition(start, PlaceholderEvent.SetSyntheticTransitioning(true))
        assertTrue(next.restoreStuck)
        assertTrue(next.transitioning)
    }

    // MarkNeedsReplug

    @Test
    fun `markNeedsReplug settles transitioning into needsReplug`() {
        val start = live().copy(transitioning = true)
        val next = placeholderTransition(start, PlaceholderEvent.MarkNeedsReplug)
        assertFalse(next.transitioning)
        assertTrue(next.needsReplug)
    }

    @Test
    fun `markNeedsReplug guards the contradictory transitioning plus needsReplug combo`() {
        // Even if a caller somehow applied this to an already-stuck state, the result is never both
        // transitioning and needsReplug.
        val start = live().copy(transitioning = true, needsReplug = true)
        val next = placeholderTransition(start, PlaceholderEvent.MarkNeedsReplug)
        assertFalse(next.transitioning)
        assertTrue(next.needsReplug)
    }

    // MarkRestoreStuck (synthetic-only)

    @Test
    fun `markRestoreStuck on a synthetic clears transitioning and raises restoreStuck`() {
        val start = live(isUsbSynthetic = true).copy(transitioning = true)
        val next = placeholderTransition(start, PlaceholderEvent.MarkRestoreStuck)
        assertFalse(next.transitioning)
        assertTrue(next.restoreStuck)
    }

    @Test
    fun `markRestoreStuck on a non-synthetic is a no-op`() {
        val start = live(isUsbSynthetic = false).copy(transitioning = true)
        val next = placeholderTransition(start, PlaceholderEvent.MarkRestoreStuck)
        // restoreStuck must never appear on a framework device.
        assertSame(start, next)
        assertFalse(next.restoreStuck)
    }

    // ClearRestoreStuck (synthetic-only)

    @Test
    fun `clearRestoreStuck on a synthetic returns to the held-loader look`() {
        val start = live(isUsbSynthetic = true).copy(restoreStuck = true)
        val next = placeholderTransition(start, PlaceholderEvent.ClearRestoreStuck)
        assertFalse(next.restoreStuck)
        assertTrue(next.transitioning)
    }

    @Test
    fun `clearRestoreStuck on a non-synthetic is a no-op`() {
        val start = live(isUsbSynthetic = false).copy(restoreStuck = true)
        val next = placeholderTransition(start, PlaceholderEvent.ClearRestoreStuck)
        assertSame(start, next)
        assertFalse(next.transitioning)
    }

    // Round trips that mirror the real call sequences.

    @Test
    fun `hold then markNeedsReplug matches the framework loader to replug path`() {
        val held = placeholderTransition(live().copy(disconnectingTimeLeftSec = 4), PlaceholderEvent.HoldAsTransitioning)
        val replug = placeholderTransition(held, PlaceholderEvent.MarkNeedsReplug)
        assertEquals(
            live().copy(needsReplug = true),
            replug,
        )
    }

    @Test
    fun `markRestoreStuck then clearRestoreStuck round-trips a synthetic back to transitioning`() {
        val base = live(isUsbSynthetic = true).copy(transitioning = true)
        val stuck = placeholderTransition(base, PlaceholderEvent.MarkRestoreStuck)
        val cleared = placeholderTransition(stuck, PlaceholderEvent.ClearRestoreStuck)
        assertEquals(base, cleared)
    }

    // Identity preservation: the reducer never flips isUsbSynthetic.

    @Test
    fun `every event preserves the isUsbSynthetic identity flag`() {
        val events =
            listOf(
                PlaceholderEvent.HoldAsTransitioning,
                PlaceholderEvent.SetSyntheticTransitioning(true),
                PlaceholderEvent.SetSyntheticTransitioning(false),
                PlaceholderEvent.MarkNeedsReplug,
                PlaceholderEvent.MarkRestoreStuck,
                PlaceholderEvent.ClearRestoreStuck,
            )
        for (synthetic in listOf(true, false)) {
            for (event in events) {
                val next = placeholderTransition(live(isUsbSynthetic = synthetic), event)
                assertEquals(
                    "event $event must not change isUsbSynthetic",
                    synthetic,
                    next.isUsbSynthetic,
                )
            }
        }
    }
}
