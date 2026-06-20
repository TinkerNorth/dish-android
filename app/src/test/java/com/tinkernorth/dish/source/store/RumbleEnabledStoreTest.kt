// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.repository.mapBackedPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// First coverage for the per-slot rumble on/off store. Uses the shared map-backed
// SharedPreferences double so one backing map models the on-disk user_preferences file.
class RumbleEnabledStoreTest {
    @Test
    fun `hydrates state from prefs on construction`() {
        val (ctx, _) =
            mapBackedPrefs(
                mutableMapOf(
                    "rumble_enabled:virtual" to true,
                    "rumble_enabled:9" to false,
                ),
            )
        val store = RumbleEnabledStore(ctx)

        assertEquals(true, store.state.value["virtual"])
        assertEquals(false, store.state.value["9"])
        assertEquals(2, store.state.value.size)
    }

    @Test
    fun `default is on for an unwritten slot`() {
        val (ctx, _) = mapBackedPrefs()
        val store = RumbleEnabledStore(ctx)
        assertTrue(store.isEnabled("never-toggled"))
        assertTrue(RumbleEnabledStore.DEFAULT_ENABLED)
    }

    @Test
    fun `setEnabled persists to prefs AND republishes state`() {
        val (ctx, backing) = mapBackedPrefs()
        val store = RumbleEnabledStore(ctx)

        store.setEnabled("9", enabled = false)

        assertEquals(false, backing["rumble_enabled:9"])
        assertEquals(false, store.state.value["9"])
        assertFalse(store.isEnabled("9"))
    }

    @Test
    fun `setEnabled then setEnabled with opposite value flips both layers`() {
        val (ctx, backing) = mapBackedPrefs()
        val store = RumbleEnabledStore(ctx)
        store.setEnabled("9", enabled = false)
        store.setEnabled("9", enabled = true)
        store.setEnabled("9", enabled = false)

        assertEquals(false, backing["rumble_enabled:9"])
        assertEquals(false, store.state.value["9"])
        assertFalse(store.isEnabled("9"))
    }

    @Test
    fun `setEnabled for slot A leaves slot B unchanged`() {
        val (ctx, _) = mapBackedPrefs()
        val store = RumbleEnabledStore(ctx)

        store.setEnabled("virtual", enabled = true)
        store.setEnabled("9", enabled = false)
        store.setEnabled("virtual", enabled = false)

        assertEquals(false, store.state.value["virtual"])
        assertEquals(false, store.state.value["9"])
    }

    @Test
    fun `isEnabled for an explicitly disabled slot returns false`() {
        val (ctx, _) = mapBackedPrefs(mutableMapOf("rumble_enabled:9" to false))
        val store = RumbleEnabledStore(ctx)
        assertFalse(store.isEnabled("9"))
    }

    @Test
    fun `readAll ignores keys without the rumble_enabled prefix`() {
        val (ctx, _) =
            mapBackedPrefs(
                mutableMapOf(
                    "rumble_enabled:9" to false,
                    "motion_enabled:9" to true,
                    "unrelated" to true,
                ),
            )
        val store = RumbleEnabledStore(ctx)

        assertEquals(false, store.state.value["9"])
        assertEquals(1, store.state.value.size)
    }
}
