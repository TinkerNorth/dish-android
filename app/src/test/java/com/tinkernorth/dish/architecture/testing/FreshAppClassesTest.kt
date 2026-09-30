// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.architecture.testing

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.function.LongSupplier

// An app class with a method that allocates nothing.
internal class Counter {
    private var count = 0L

    fun next(): Long {
        count += 1
        return count
    }
}

// Calls the counter; an app class, so the fresh loader defines it, and Counter with it.
internal class CounterCalls :
    Runnable,
    LongSupplier {
    private val counter = Counter()
    private var last = 0L

    override fun run() {
        repeat(CALLS) { last = counter.next() }
    }

    override fun getAsLong(): Long = last

    companion object {
        const val CALLS = 1000
    }
}

// What an allocation test can rely on when it measures an app class other test classes mock.
class FreshAppClassesTest {
    private fun mockTheCounterClass() {
        val mock = mockk<Counter>()
        every { mock.next() } returns 0L
        mock.next()
    }

    @Test
    fun `a mocked class allocates on every call to a real instance`() {
        mockTheCounterClass()
        val counter = Counter()
        val bytes = allocatedBytesDuring { repeat(CounterCalls.CALLS) { counter.next() } }
        assertTrue("$bytes bytes over ${CounterCalls.CALLS} calls", bytes >= CounterCalls.CALLS * SMALLEST_OBJECT_BYTES)
    }

    @Test
    fun `a fresh copy of a mocked class allocates nothing`() {
        mockTheCounterClass()
        val calls = freshAppInstanceOf(CounterCalls::class.java)
        val cycle = calls as Runnable
        cycle.run()
        val bytes = allocatedBytesDuring(cycle::run)
        assertEquals(2L * CounterCalls.CALLS, (calls as LongSupplier).asLong)
        assertTrue("$bytes bytes over ${CounterCalls.CALLS} calls", bytes < CounterCalls.CALLS * BYTES_PER_CALL_BOUND)
    }

    @Test
    fun `app classes are defined afresh and everything else is shared`() {
        val calls = freshAppInstanceOf(CounterCalls::class.java)
        assertNotSame(CounterCalls::class.java, calls.javaClass)
        assertSame(Runnable::class.java, calls.javaClass.interfaces.first { it.name == Runnable::class.java.name })
        assertSame(Unit::class.java, Class.forName(Unit::class.java.name, false, calls.javaClass.classLoader))
    }

    private companion object {
        // A header and an array length or a field come to at least this on any 64-bit HotSpot.
        const val SMALLEST_OBJECT_BYTES = 16L

        // Half of that: one allocation a call is caught, one-off class set-up is not.
        const val BYTES_PER_CALL_BOUND = 8L
    }
}
