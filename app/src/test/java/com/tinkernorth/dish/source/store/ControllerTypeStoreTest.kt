// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControllerTypeStoreTest {
    private val xbox = 0
    private val playstation = 1

    @Test
    fun `setType then typeFor round-trips the value`() {
        val store = ControllerTypeStore()
        store.setType("conn-1", "slot-A", playstation)
        assertEquals(playstation, store.typeFor("conn-1", "slot-A"))
    }

    @Test
    fun `typeFor an unset connection-slot is null`() {
        val store = ControllerTypeStore()
        assertNull(store.typeFor("conn-1", "slot-A"))
    }

    @Test
    fun `setType overwrites an existing value`() {
        val store = ControllerTypeStore()
        store.setType("conn-1", "slot-A", xbox)
        store.setType("conn-1", "slot-A", playstation)
        assertEquals(playstation, store.typeFor("conn-1", "slot-A"))
    }

    @Test
    fun `setTypeIfAbsent writes when the key is unset`() {
        val store = ControllerTypeStore()
        store.setTypeIfAbsent("conn-1", "slot-A", playstation)
        assertEquals(playstation, store.typeFor("conn-1", "slot-A"))
    }

    @Test
    fun `setTypeIfAbsent keeps the existing value when present`() {
        val store = ControllerTypeStore()
        store.setType("conn-1", "slot-A", playstation)
        store.setTypeIfAbsent("conn-1", "slot-A", xbox)
        assertEquals(playstation, store.typeFor("conn-1", "slot-A"))
    }

    @Test
    fun `the same slot id under different connections does not collide`() {
        val store = ControllerTypeStore()
        store.setType("conn-1", "slot-A", xbox)
        store.setType("conn-2", "slot-A", playstation)
        assertEquals(xbox, store.typeFor("conn-1", "slot-A"))
        assertEquals(playstation, store.typeFor("conn-2", "slot-A"))
    }

    @Test
    fun `slotTypesFor returns only bound slots that carry a type`() {
        val store = ControllerTypeStore()
        store.setType("conn-1", "slot-A", xbox)
        store.setType("conn-1", "slot-B", playstation)
        store.setType("conn-2", "slot-A", playstation)

        val types = store.slotTypesFor("conn-1", listOf("slot-A", "slot-B", "slot-unbound"))

        assertEquals(mapOf("slot-A" to xbox, "slot-B" to playstation), types)
    }

    @Test
    fun `clear removes only the exact connection-slot entry, leaving the connection's other slots`() {
        val store = ControllerTypeStore()
        store.setType("conn-1", "slot-A", xbox)
        store.setType("conn-1", "slot-B", playstation)

        store.clear("conn-1", "slot-A")

        assertNull(store.typeFor("conn-1", "slot-A"))
        // No bulk clearConnection(connectionId) exists, so slot-B's type survives the connection going away.
        assertEquals(playstation, store.typeFor("conn-1", "slot-B"))
    }

    @Test
    fun `clear of an unset connection-slot is a no-op`() {
        val store = ControllerTypeStore()
        store.setType("conn-1", "slot-A", xbox)
        store.clear("conn-1", "slot-missing")
        assertEquals(xbox, store.typeFor("conn-1", "slot-A"))
        assertEquals(1, store.state.value.size)
    }
}
