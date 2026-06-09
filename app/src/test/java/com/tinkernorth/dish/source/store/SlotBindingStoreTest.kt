// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CyclicBarrier

@OptIn(ExperimentalCoroutinesApi::class)
class SlotBindingStoreTest {
    @Test
    fun `bind records the slot to connection mapping`() {
        val store = SlotBindingStore()
        store.bind("slot-A", "conn-1")
        assertEquals("conn-1", store.connectionFor("slot-A"))
        assertEquals(mapOf("slot-A" to "conn-1"), store.bindings.value)
    }

    @Test
    fun `connectionFor an unbound slot is null`() {
        val store = SlotBindingStore()
        assertNull(store.connectionFor("ghost"))
    }

    @Test
    fun `slotsFor returns every slot bound to a connection`() {
        val store = SlotBindingStore()
        store.bind("slot-A", "conn-1")
        store.bind("slot-B", "conn-1")
        store.bind("slot-C", "conn-2")
        assertEquals(setOf("slot-A", "slot-B"), store.slotsFor("conn-1").toSet())
        assertEquals(listOf("slot-C"), store.slotsFor("conn-2"))
        assertEquals(emptyList<String>(), store.slotsFor("conn-unknown"))
    }

    @Test
    fun `binding the same slot to a new connection overwrites the prior connection`() {
        val store = SlotBindingStore()
        store.bind("slot-A", "conn-1")
        store.bind("slot-A", "conn-2")
        assertEquals("conn-2", store.connectionFor("slot-A"))
        assertEquals(emptyList<String>(), store.slotsFor("conn-1"))
    }

    @Test
    fun `unbind removes the slot`() {
        val store = SlotBindingStore()
        store.bind("slot-A", "conn-1")
        store.unbind("slot-A")
        assertNull(store.connectionFor("slot-A"))
        assertEquals(emptyMap<String, String>(), store.bindings.value)
    }

    @Test
    fun `unbind of an unbound slot is a no-op`() {
        val store = SlotBindingStore()
        store.bind("slot-A", "conn-1")
        store.unbind("ghost")
        assertEquals(mapOf("slot-A" to "conn-1"), store.bindings.value)
    }

    @Test
    fun `replace returns the prior connection and installs the new one`() {
        val store = SlotBindingStore()
        store.bind("slot-A", "conn-1")
        val prior = store.replace("slot-A", "conn-2")
        assertEquals("conn-1", prior)
        assertEquals("conn-2", store.connectionFor("slot-A"))
    }

    @Test
    fun `replace on an unbound slot returns null`() {
        val store = SlotBindingStore()
        val prior = store.replace("slot-A", "conn-1")
        assertNull(prior)
        assertEquals("conn-1", store.connectionFor("slot-A"))
    }

    @Test
    fun `re-binding a slot to the identical connection does not emit a new state`() =
        runTest {
            val store = SlotBindingStore()
            val seen = mutableListOf<Map<String, String>>()
            val job = launch(UnconfinedTestDispatcher(testScheduler)) { store.state.collect { seen += it } }

            store.bind("slot-A", "conn-1")
            store.bind("slot-A", "conn-1")
            runCurrent()
            job.cancel()

            // StateFlow conflates structurally-equal values, so the redundant bind is dropped.
            assertEquals(listOf(emptyMap(), mapOf("slot-A" to "conn-1")), seen)
        }

    @Test
    fun `concurrent binds for distinct slots never lose an update`() {
        val store = SlotBindingStore()
        val threads = 16
        val perThread = 500
        val barrier = CyclicBarrier(threads)

        val workers =
            (0 until threads).map { t ->
                Thread {
                    runCatching { barrier.await() }
                    repeat(perThread) { i -> store.bind("slot-$t-$i", "conn-$t") }
                }
            }
        workers.forEach(Thread::start)
        workers.forEach(Thread::join)

        assertEquals(
            "a concurrent bind was lost: read-modify-write is not atomic",
            threads * perThread,
            store.bindings.value.size,
        )
    }
}
