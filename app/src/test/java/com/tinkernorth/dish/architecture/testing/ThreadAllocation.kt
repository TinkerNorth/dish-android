// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.architecture.testing

import java.lang.management.ManagementFactory

// Read once here: the first read of the counter may set up the bean's own state, which no
// measurement should count.
private val threads =
    (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).also { it.currentThreadAllocatedBytes }

/**
 * What the calling thread allocated while [block] ran, less what reading the counter costs. The
 * test JVM runs with escape analysis off, so this counts every allocation the source makes,
 * whichever JIT tier earlier test classes left the code in (ThreadAllocationTest pins that).
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
