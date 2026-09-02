// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.repository.mapBackedPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The per-slot microphone toggle. Same shape as the rumble store, opposite default: the
// microphone is opt-IN, and a slot nobody switched on must never end up advertising one.
class MicEnabledStoreTest {
    @Test
    fun `default is OFF for an unwritten slot`() {
        val (ctx, _) = mapBackedPrefs()
        val store = MicEnabledStore(ctx)
        assertFalse(store.isEnabled("never-toggled"))
        assertFalse(MicEnabledStore.DEFAULT_ENABLED)
    }

    @Test
    fun `hydrates state from prefs on construction`() {
        val (ctx, _) =
            mapBackedPrefs(
                mutableMapOf(
                    "mic_enabled:virtual" to true,
                    "mic_enabled:9" to false,
                ),
            )
        val store = MicEnabledStore(ctx)

        assertEquals(true, store.state.value["virtual"])
        assertEquals(false, store.state.value["9"])
        assertEquals(2, store.state.value.size)
    }

    @Test
    fun `setEnabled persists to prefs AND republishes state`() {
        val (ctx, backing) = mapBackedPrefs()
        val store = MicEnabledStore(ctx)

        store.setEnabled("9", enabled = true)

        assertEquals(true, backing["mic_enabled:9"])
        assertEquals(true, store.state.value["9"])
        assertTrue(store.isEnabled("9"))
    }

    @Test
    fun `turning a slot back off persists the off, it does not just forget`() {
        // An explicit off has to survive a restart, or a mic the user silenced would come
        // back on the next launch through the absent-means-default path.
        val (ctx, backing) = mapBackedPrefs()
        val store = MicEnabledStore(ctx)
        store.setEnabled("9", enabled = true)
        store.setEnabled("9", enabled = false)

        assertEquals(false, backing["mic_enabled:9"])
        assertFalse(store.isEnabled("9"))
    }

    @Test
    fun `setEnabled for slot A leaves slot B unchanged`() {
        val (ctx, _) = mapBackedPrefs()
        val store = MicEnabledStore(ctx)

        store.setEnabled("virtual", enabled = true)
        store.setEnabled("9", enabled = false)

        assertEquals(true, store.state.value["virtual"])
        assertEquals(false, store.state.value["9"])
    }

    @Test
    fun `readAll ignores keys without the mic_enabled prefix`() {
        val (ctx, _) =
            mapBackedPrefs(
                mutableMapOf(
                    "mic_enabled:9" to true,
                    "speaker_enabled:9" to true,
                    "rumble_enabled:9" to false,
                    "unrelated" to true,
                ),
            )
        val store = MicEnabledStore(ctx)

        assertEquals(true, store.state.value["9"])
        assertEquals(1, store.state.value.size)
    }
}
