// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.architecture.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// A point that never leaves the function that makes it: what C2's escape analysis turns into two
// registers, and the kind of allocation an allocation test is there to see.
private class Point(
    val x: Int,
    val y: Int,
)

private fun sumThroughAPoint(
    x: Int,
    y: Int,
): Int {
    val point = Point(x, y)
    return point.x + point.y
}

// A method of its own, so the JIT compiles the loop whole with the point inlined into it.
private fun burst(calls: Int): Int {
    var sum = 0
    for (call in 0 until calls) sum += sumThroughAPoint(call, 1)
    return sum
}

// An enum the measured code reads the ordinal of, the way a `when` over an enum does.
private enum class Step {
    FIRST,
    SECOND,
}

// The allocation tests compare what a hot path allocates against zero or against a baseline, in a
// JVM that earlier test classes have already warmed. That only means what it would on the phone if
// the JIT cannot remove an allocation ART makes, which is what the test task's flags promise.
class ThreadAllocationTest {
    private var total = 0

    private fun runBurst() {
        total += burst(CALLS_PER_BURST)
    }

    @Test
    fun `an allocation escape analysis would remove is still counted once the code is compiled`() {
        var fewest = Long.MAX_VALUE
        repeat(BURSTS) {
            val bytes = allocatedBytesDuring(::runBurst)
            fewest = minOf(fewest, bytes)
        }
        assertTrue("fewest bytes in one burst: $fewest (sum $total)", fewest >= CALLS_PER_BURST * SMALLEST_OBJECT_BYTES)
    }

    private var runsSoFar = 0
    private var kept: LongArray? = null

    private var oneOffRun = 0

    private fun allocateOnTheOneOffRunOnly() {
        if (runsSoFar == oneOffRun) kept = LongArray(ARRAY_LONGS)
        runsSoFar++
    }

    private fun allocateEveryRun() {
        kept = LongArray(ARRAY_LONGS)
    }

    private fun fewestWithAOneOffOn(run: Int): Long {
        oneOffRun = run
        val fewest = fewestAllocatedBytesDuring(RUNS, ::allocateOnTheOneOffRunOnly)
        assertEquals(RUNS, runsSoFar)
        assertEquals(ARRAY_LONGS, kept?.size)
        return fewest
    }

    @Test
    fun `a one-off allocation in the first run is not the fewest`() {
        val fewest = fewestWithAOneOffOn(0)
        assertTrue("fewest bytes: $fewest", fewest < ARRAY_BYTES)
    }

    @Test
    fun `a one-off allocation in the last run is not the fewest`() {
        val fewest = fewestWithAOneOffOn(RUNS - 1)
        assertTrue("fewest bytes: $fewest", fewest < ARRAY_BYTES)
    }

    @Test
    fun `an allocation in every run is the fewest`() {
        val fewest = fewestAllocatedBytesDuring(RUNS, ::allocateEveryRun)
        assertEquals(ARRAY_LONGS, kept?.size)
        assertTrue("fewest bytes: $fewest", fewest >= ARRAY_BYTES)
    }

    private var ordinals = 0

    private fun readOrdinals() {
        repeat(ORDINAL_READS) { ordinals += Step.entries[it % 2].ordinal }
    }

    // A relaxed MockK mock that hands out an enum anywhere in the suite rewrites java.lang.Enum
    // for the rest of the JVM, and every ordinal() then allocates. Which test class ran first must
    // not decide whether a hot path's `when` over an enum passes, so every measurement is taken
    // with Enum already rewritten, even in a test class run alone.
    @Test
    fun `reading an enum's ordinal is counted, whichever test class ran first`() {
        readOrdinals()

        val fewest = fewestAllocatedBytesDuring(RUNS, ::readOrdinals)

        assertTrue("fewest bytes: $fewest over $ORDINAL_READS reads", fewest >= ORDINAL_READS * SMALLEST_OBJECT_BYTES)
    }

    private companion object {
        const val ORDINAL_READS = 1000

        // Enough calls, twenty million in all, that C2 compiles the burst well before the last one.
        const val BURSTS = 2000
        const val CALLS_PER_BURST = 10_000

        // A header and two ints come to more than this on any 64-bit HotSpot layout.
        const val SMALLEST_OBJECT_BYTES = 16L

        const val RUNS = 3
        const val ARRAY_LONGS = 128

        // The array's payload alone, before its header.
        const val ARRAY_BYTES = ARRAY_LONGS * Long.SIZE_BYTES
    }
}
