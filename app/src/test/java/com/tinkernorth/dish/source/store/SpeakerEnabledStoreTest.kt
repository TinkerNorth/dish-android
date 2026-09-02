// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.repository.mapBackedPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The per-slot controller-sound toggle. Defaults on like rumble: an emulated pad that
// carries a speaker endpoint should be heard without being asked twice.
class SpeakerEnabledStoreTest {
    @Test
    fun `default is on for an unwritten slot`() {
        val (ctx, _) = mapBackedPrefs()
        val store = SpeakerEnabledStore(ctx)
        assertTrue(store.isEnabled("never-toggled"))
        assertTrue(SpeakerEnabledStore.DEFAULT_ENABLED)
    }

    @Test
    fun `hydrates state from prefs on construction`() {
        val (ctx, _) =
            mapBackedPrefs(
                mutableMapOf(
                    "speaker_enabled:virtual" to false,
                    "speaker_enabled:9" to true,
                ),
            )
        val store = SpeakerEnabledStore(ctx)

        assertEquals(false, store.state.value["virtual"])
        assertEquals(true, store.state.value["9"])
        assertEquals(2, store.state.value.size)
    }

    @Test
    fun `setEnabled persists to prefs AND republishes state`() {
        val (ctx, backing) = mapBackedPrefs()
        val store = SpeakerEnabledStore(ctx)

        store.setEnabled("9", enabled = false)

        assertEquals(false, backing["speaker_enabled:9"])
        assertEquals(false, store.state.value["9"])
        assertFalse(store.isEnabled("9"))
    }

    @Test
    fun `setEnabled for slot A leaves slot B unchanged`() {
        val (ctx, _) = mapBackedPrefs()
        val store = SpeakerEnabledStore(ctx)

        store.setEnabled("virtual", enabled = false)
        store.setEnabled("9", enabled = true)

        assertEquals(false, store.state.value["virtual"])
        assertEquals(true, store.state.value["9"])
    }

    @Test
    fun `readAll ignores keys without the speaker_enabled prefix`() {
        val (ctx, _) =
            mapBackedPrefs(
                mutableMapOf(
                    "speaker_enabled:9" to false,
                    "mic_enabled:9" to true,
                    "unrelated" to true,
                ),
            )
        val store = SpeakerEnabledStore(ctx)

        assertEquals(false, store.state.value["9"])
        assertEquals(1, store.state.value.size)
    }

    @Test
    fun `the two audio toggles share one prefs file without colliding`() {
        // Both write into user_preferences; only their prefixes keep them apart.
        val (ctx, backing) = mapBackedPrefs()
        val mic = MicEnabledStore(ctx)
        val speaker = SpeakerEnabledStore(ctx)

        mic.setEnabled("9", enabled = true)
        speaker.setEnabled("9", enabled = false)

        assertEquals(true, backing["mic_enabled:9"])
        assertEquals(false, backing["speaker_enabled:9"])
        assertTrue(mic.isEnabled("9"))
        assertFalse(speaker.isEnabled("9"))
    }
}
