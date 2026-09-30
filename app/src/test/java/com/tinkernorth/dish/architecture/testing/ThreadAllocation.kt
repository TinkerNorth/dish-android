// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.architecture.testing

import io.mockk.mockk
import java.lang.management.ManagementFactory

// An enum only for MockK to mock.
private enum class RewrittenByMockK {
    ONLY,
}

// A relaxed MockK mock that hands out an enum rewrites java.lang.Enum for the rest of the JVM, and
// every ordinal() (a `when` over an enum reads it) then allocates. A JDK class has no fresh copy to
// measure instead (freshAppInstanceOf), so every measurement is taken with Enum rewritten: whether
// one passes cannot then depend on whether such a test class happened to run first.
private fun rewriteEnumAsMockKWould() {
    mockk<RewrittenByMockK>(relaxed = true).ordinal
}

// Read once here: the first read of the counter may set up the bean's own state, which no
// measurement should count.
private val threads =
    (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).also {
        rewriteEnumAsMockKWould()
        it.currentThreadAllocatedBytes
    }

/**
 * What the calling thread allocated while [block] ran, less what reading the counter costs. The
 * test JVM runs with escape analysis off, so this counts every allocation the source makes,
 * whichever JIT tier earlier test classes left the code in (ThreadAllocationTest pins that). It
 * also counts what MockK's rewrite of a class another test mocked allocates on every call, so a
 * test of an app class that is mocked anywhere measures a copy from [freshAppInstanceOf]. It is
 * always taken with java.lang.Enum as MockK rewrites it, so an enum's ordinal() counts, whichever
 * test class ran first.
 */
fun allocatedBytesDuring(block: () -> Unit): Long {
    val readOnlyStart = threads.currentThreadAllocatedBytes
    val readOnlyEnd = threads.currentThreadAllocatedBytes
    val readCost = readOnlyEnd - readOnlyStart
    val start = threads.currentThreadAllocatedBytes
    block()
    val end = threads.currentThreadAllocatedBytes
    return end - start - readCost
}

/**
 * The fewest bytes [block] allocated in any of [runs] runs. An allocation the code makes every time
 * shows in every run; a one-off (a class the first run loads, a method the JIT recompiles
 * mid-run) shows in one, and must not fail a test that asks whether the code allocates.
 */
fun fewestAllocatedBytesDuring(
    runs: Int,
    block: () -> Unit,
): Long {
    var fewest = Long.MAX_VALUE
    repeat(runs) { fewest = minOf(fewest, allocatedBytesDuring(block)) }
    return fewest
}
