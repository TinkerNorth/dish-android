// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MouseSurfaceStoreTest {
    @Test
    fun `opening and closing a slot toggles exactly that slot`() {
        val store = MouseSurfaceStore()
        assertFalse(store.isOpen("virtual"))

        store.setOpen("virtual", true)
        assertTrue(store.isOpen("virtual"))
        assertFalse(store.isOpen("9"))
        assertEquals(setOf("virtual"), store.state.value)

        store.setOpen("9", true)
        assertEquals(setOf("virtual", "9"), store.state.value)

        store.setOpen("virtual", false)
        assertFalse(store.isOpen("virtual"))
        assertTrue(store.isOpen("9"))
    }

    @Test
    fun `closing a never-opened slot is a no-op`() {
        val store = MouseSurfaceStore()
        store.setOpen("virtual", false)
        assertEquals(emptySet<String>(), store.state.value)
    }
}
