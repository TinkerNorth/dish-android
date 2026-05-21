// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.source.sensor.BatteryValidator
import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample
import com.tinkernorth.dish.source.sensor.PhysicalBatterySource
import com.tinkernorth.dish.source.sensor.VirtualBatterySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CyclicBarrier

/**
 * Unit tests for [BatteryStatusStore] — the process-scoped per-slot battery
 * cache the dashboard renders from.
 *
 * The store is documented as written by two process-scoped sources
 * ([VirtualBatterySource] and [PhysicalBatterySource]), whose poll loops and
 * charging-state receivers run on different threads. The headline test is
 * [concurrent puts for distinct slots never lose an update]: a plain
 * `value = value + entry` read-modify-write on a `MutableStateFlow` is not
 * atomic, so two writers racing for different slots can clobber each other.
 */
class BatteryStatusStoreTest {
    private fun sample(level: Int) = BatterySample(level, BatteryValidator.STATUS_DISCHARGING)

    @Test
    fun `put then read surfaces the sample`() {
        val store = BatteryStatusStore()
        store.put("9", sample(72))
        assertEquals(72, store.samples.value["9"]?.level)
    }

    @Test
    fun `a second put for the same slot overwrites`() {
        val store = BatteryStatusStore()
        store.put("9", sample(72))
        store.put("9", sample(40))
        assertEquals(40, store.samples.value["9"]?.level)
        assertEquals(1, store.samples.value.size)
    }

    @Test
    fun `clear drops the slot`() {
        val store = BatteryStatusStore()
        store.put("9", sample(72))
        store.clear("9")
        assertNull(store.samples.value["9"])
    }

    @Test
    fun `clear of an unknown slot is a no-op`() {
        val store = BatteryStatusStore()
        store.put("9", sample(72))
        store.clear("does-not-exist")
        assertEquals(1, store.samples.value.size)
    }

    @Test
    fun `concurrent puts for distinct slots never lose an update`() {
        // Two process-scoped sources write this store from different threads.
        // `_samples.value = _samples.value + entry` is a non-atomic
        // read-modify-write: writer A and writer B both read the old map, then
        // each writes its own +1 — and the second write silently drops the
        // first writer's entry. With 16 contended threads and 8 000 distinct
        // keys a lost update is a near-certainty unless put() is atomic.
        val store = BatteryStatusStore()
        val threads = 16
        val perThread = 500
        val barrier = CyclicBarrier(threads)

        val workers =
            (0 until threads).map { t ->
                Thread {
                    runCatching { barrier.await() } // start all writers together
                    repeat(perThread) { i -> store.put("slot-$t-$i", sample(t)) }
                }
            }
        workers.forEach(Thread::start)
        workers.forEach(Thread::join)

        assertEquals(
            "a concurrent put was lost — read-modify-write is not atomic",
            threads * perThread,
            store.samples.value.size,
        )
    }

    @Test
    fun `concurrent clears never resurrect a removed slot`() {
        // The clear() path has the same non-atomic read-modify-write. Pre-seed
        // every slot, then clear them all from many threads at once: a lost
        // update here leaves a slot that should be gone still in the map.
        val store = BatteryStatusStore()
        val threads = 16
        val perThread = 500
        repeat(threads) { t -> repeat(perThread) { i -> store.put("slot-$t-$i", sample(t)) } }
        val barrier = CyclicBarrier(threads)

        val workers =
            (0 until threads).map { t ->
                Thread {
                    runCatching { barrier.await() }
                    repeat(perThread) { i -> store.clear("slot-$t-$i") }
                }
            }
        workers.forEach(Thread::start)
        workers.forEach(Thread::join)

        assertEquals(
            "a concurrent clear was lost — read-modify-write is not atomic",
            0,
            store.samples.value.size,
        )
    }
}
