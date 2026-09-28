// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.architecture.testing

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

    private companion object {
        // Enough calls, twenty million in all, that C2 compiles the burst well before the last one.
        const val BURSTS = 2000
        const val CALLS_PER_BURST = 10_000

        // A header and two ints come to more than this on any 64-bit HotSpot layout.
        const val SMALLEST_OBJECT_BYTES = 16L
    }
}
