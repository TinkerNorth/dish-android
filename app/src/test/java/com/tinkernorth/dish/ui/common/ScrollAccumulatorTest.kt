// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollAccumulatorTest {
    @Test
    fun `a full step emits one notch`() {
        val acc = ScrollAccumulator(stepPx = 32f)
        assertEquals(1, acc.add(32f))
    }

    @Test
    fun `sub-step drags accumulate across calls`() {
        val acc = ScrollAccumulator(stepPx = 32f)
        assertEquals(0, acc.add(20f))
        assertEquals(1, acc.add(20f))
        assertEquals(0, acc.add(20f))
        assertEquals(1, acc.add(12f))
    }

    @Test
    fun `direction is signed and the remainder keeps its sign`() {
        val acc = ScrollAccumulator(stepPx = 32f)
        assertEquals(-1, acc.add(-40f))
        assertEquals(0, acc.add(-20f))
        assertEquals(-1, acc.add(-4f))
    }

    @Test
    fun `a big fling emits several notches at once`() {
        val acc = ScrollAccumulator(stepPx = 32f)
        assertEquals(4, acc.add(130f))
        assertEquals(0, acc.add(29f))
    }

    @Test
    fun `reset drops the remainder so a new gesture starts clean`() {
        val acc = ScrollAccumulator(stepPx = 32f)
        assertEquals(0, acc.add(30f))
        acc.reset()
        assertEquals(0, acc.add(30f))
        assertEquals(1, acc.add(2f))
    }

    @Test
    fun `reversing direction eats the opposite remainder first`() {
        val acc = ScrollAccumulator(stepPx = 32f)
        assertEquals(0, acc.add(30f))
        assertEquals(0, acc.add(-30f))
        assertEquals(-1, acc.add(-32f))
    }
}
