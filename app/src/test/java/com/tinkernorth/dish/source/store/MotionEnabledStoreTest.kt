// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.repository.MotionPreference
import com.tinkernorth.dish.repository.MotionPreferenceRepository
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MotionEnabledStore] — the reactive mirror over the
 * durable [MotionPreferenceRepository].
 *
 * Headline invariants pinned here:
 *
 *  - **Hydration on construction:** the store seeds its `state` from
 *    `repo.all()` at construction time, so a fresh process restart sees
 *    yesterday's toggles without an extra round trip.
 *  - **Default policy:** an absent slot's [MotionEnabledStore.isEnabled]
 *    returns [MotionEnabledStore.DEFAULT_ENABLED] (true). The composer and
 *    pill consume this default; if the policy changes it changes here.
 *  - **Write-through:** [MotionEnabledStore.setEnabled] always persists
 *    AND republishes — neither side is allowed to silently win. If a
 *    future refactor drops the repo write, the durability test fails.
 *  - **Forget restores default:** removing a slot's entry causes
 *    [MotionEnabledStore.isEnabled] to flip back to the default.
 */
class MotionEnabledStoreTest {
    @Test
    fun `hydrates state from the repository on construction`() {
        // Process restart scenario: the durable repository already holds
        // yesterday's toggles. The store must surface them immediately —
        // no race window where state reads emptyMap and isEnabled
        // returns the default for a slot the user explicitly turned off.
        val repo =
            mockk<MotionPreferenceRepository> {
                every { all() } returns
                    listOf(
                        MotionPreference("virtual", enabled = true),
                        MotionPreference("9", enabled = false),
                    )
            }
        val store = MotionEnabledStore(repo)

        assertEquals(true, store.state.value["virtual"])
        assertEquals(false, store.state.value["9"])
        assertEquals(2, store.state.value.size)
    }

    @Test
    fun `default is on for an unwritten slot`() {
        // The product decision is "motion is on unless I turn it off."
        // Pin the default through the public isEnabled getter so a future
        // refactor can't silently flip the polarity.
        val repo = repoWithEmpty()
        val store = MotionEnabledStore(repo)
        assertTrue(store.isEnabled("never-toggled"))
        assertTrue(MotionEnabledStore.DEFAULT_ENABLED) // constant pin
    }

    @Test
    fun `setEnabled persists to the repository AND republishes state`() {
        // The store must be both reactive (state flow) AND durable (repo
        // write). A regression that only updates the in-memory cache
        // would lose the toggle on app restart.
        val repo = repoWithEmpty()
        val store = MotionEnabledStore(repo)

        store.setEnabled("9", enabled = false)

        verify { repo.put(MotionPreference("9", enabled = false)) }
        assertEquals(false, store.state.value["9"])
        assertFalse(store.isEnabled("9"))
    }

    @Test
    fun `setEnabled then setEnabled with opposite value flips both layers`() {
        // Toggling the switch on then off then on again must leave the
        // store in the final state across both the durable and reactive
        // layers — no half-updated state.
        val repo = repoWithEmpty()
        val store = MotionEnabledStore(repo)
        store.setEnabled("9", enabled = false)
        store.setEnabled("9", enabled = true)
        store.setEnabled("9", enabled = false)

        verify { repo.put(MotionPreference("9", enabled = false)) }
        verify { repo.put(MotionPreference("9", enabled = true)) }
        assertEquals(false, store.state.value["9"])
        assertFalse(store.isEnabled("9"))
    }

    @Test
    fun `forget removes the entry from state AND from the repository`() {
        // Per-slot forget is used when a physical pad is unbound
        // permanently — keeping a stale entry forever would silently
        // override the default for a future device that reused the same
        // InputDevice id.
        val repo =
            mockk<MotionPreferenceRepository> {
                every { all() } returns listOf(MotionPreference("9", enabled = false))
                justRun { remove("9") }
            }
        val store = MotionEnabledStore(repo)
        assertFalse(store.isEnabled("9"))

        store.forget("9")

        verify { repo.remove("9") }
        // After forget, the slot returns to the default, NOT the prior
        // explicit value — that's the whole point.
        assertTrue(store.isEnabled("9"))
        assertEquals(null, store.state.value["9"])
    }

    @Test
    fun `setEnabled for slot A leaves slot B unchanged`() {
        // The state map is a single MutableStateFlow; a sloppy reducer
        // could overwrite the whole map and drop other slots. Pin
        // isolation across slots.
        val repo = repoWithEmpty()
        val store = MotionEnabledStore(repo)

        store.setEnabled("virtual", enabled = true)
        store.setEnabled("9", enabled = false)
        store.setEnabled("virtual", enabled = false)

        assertEquals(false, store.state.value["virtual"])
        assertEquals(false, store.state.value["9"])
    }

    @Test
    fun `isEnabled for an explicitly disabled slot returns false`() {
        // The store's isEnabled has two paths: present-in-map and absent.
        // Cover the present path explicitly so a regression that
        // collapses both onto the default would fail here.
        val repo =
            mockk<MotionPreferenceRepository> {
                every { all() } returns listOf(MotionPreference("9", enabled = false))
            }
        val store = MotionEnabledStore(repo)
        assertFalse(store.isEnabled("9"))
    }

    private fun repoWithEmpty(): MotionPreferenceRepository =
        mockk(relaxed = true) {
            every { all() } returns emptyList()
        }
}
