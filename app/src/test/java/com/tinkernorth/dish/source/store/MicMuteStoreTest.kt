// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-slot mute holder. One store for both mute controls, because a user who muted with the
 * pad's own button and then looked at the on-screen one must not find two different answers.
 */
class MicMuteStoreTest {
    private val store = MicMuteStore()

    @Test
    fun `a slot nobody muted is live`() {
        assertFalse(store.isMuted("virtual"))
        assertFalse(MicMuteStore.DEFAULT_MUTED)
        assertEquals(emptyMap<String, Boolean>(), store.state.value)
    }

    @Test
    fun `toggle flips and returns the new state`() {
        assertTrue(store.toggle("virtual"))
        assertTrue(store.isMuted("virtual"))
        assertFalse(store.toggle("virtual"))
        assertFalse(store.isMuted("virtual"))
    }

    @Test
    fun `the on-screen button and the pads own button write the same slot state`() {
        // A Direct-claimed pad's slot id IS its synthetic device id, so the two controls meet in
        // one entry rather than in two views of the same pad.
        store.setPadMuted(deviceId = -1000, muted = true)
        assertTrue(store.isMuted("-1000"))
        assertEquals(true, store.state.value["-1000"])

        // And the app-side control reads and clears exactly that entry.
        assertFalse(store.toggle("-1000"))
        assertFalse(store.isMuted("-1000"))
    }

    @Test
    fun `slots are independent`() {
        store.setMuted("virtual", true)
        assertTrue(store.isMuted("virtual"))
        assertFalse(store.isMuted("-1000"))
    }

    @Test
    fun `an unchanged write does not republish`() {
        store.setMuted("virtual", true)
        val published = store.state.value
        store.setMuted("virtual", true)
        assertTrue("a repeated mute is not a new state", published === store.state.value)
    }

    @Test
    fun `state is observable so the capture engine can react to a mute`() {
        val seen = mutableListOf<Map<String, Boolean>>()
        seen += store.state.value
        store.setMuted("virtual", true)
        seen += store.state.value
        store.setMuted("virtual", false)
        seen += store.state.value
        assertEquals(
            listOf(emptyMap(), mapOf("virtual" to true), mapOf("virtual" to false)),
            seen,
        )
    }

    @Test
    fun `forgetting a slot returns it to live rather than leaving it muted forever`() {
        store.setMuted("-1000", true)
        store.forget("-1000")
        assertFalse(store.isMuted("-1000"))
        assertEquals(emptyMap<String, Boolean>(), store.state.value)
    }
}
