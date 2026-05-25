// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.source.sensor.BatteryValidator
import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CyclicBarrier

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
        val store = BatteryStatusStore()
        val threads = 16
        val perThread = 500
        val barrier = CyclicBarrier(threads)

        val workers =
            (0 until threads).map { t ->
                Thread {
                    runCatching { barrier.await() }
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
