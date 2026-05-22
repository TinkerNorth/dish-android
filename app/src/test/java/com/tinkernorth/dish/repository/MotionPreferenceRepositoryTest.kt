// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.repository

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour-specific tests for [MotionPreferenceRepository] — the CRUD
 * contract is covered by [MotionPreferenceRepositoryContractTest]; this
 * file pins the JSON serialization edges and the multi-write durability
 * that matter for upgrades.
 */
class MotionPreferenceRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `enabled true and false round-trip through SharedPreferences`() {
        // The repository must persist the boolean verbatim — a sloppy
        // implementation that conflates `null` (unset) with `false` would
        // destroy the "I haven't decided yet" signal the store relies on
        // for its default. Pin both true and false through a write/read.
        val (ctx, _) = fakePrefs()
        val repo = MotionPreferenceRepository(ctx, json)
        repo.put(MotionPreference("virtual", enabled = true))
        repo.put(MotionPreference("9", enabled = false))

        // Re-read through a fresh repo on the same backing prefs to prove
        // durability (no in-memory cache shortcut).
        val (ctx2, _) = fakePrefs(seedFrom = ctx)
        val repo2 = MotionPreferenceRepository(ctx2, json)
        assertEquals(true, repo2.get("virtual")?.enabled)
        assertEquals(false, repo2.get("9")?.enabled)
    }

    @Test
    fun `get on a slot that was never written returns null — not a default boolean`() {
        // The repository never invents a default. The store layer
        // collapses null onto MotionEnabledStore.DEFAULT_ENABLED; the repo
        // must stay honest so the store can distinguish "user has not
        // decided" from "user explicitly enabled".
        val (ctx, _) = fakePrefs()
        val repo = MotionPreferenceRepository(ctx, json)
        assertNull(repo.get("never-written"))
    }

    @Test
    fun `corrupt JSON in prefs falls back to empty — does not crash app startup`() {
        // A crash during a previous write, or a sideloaded apk overwriting
        // the prefs, could leave invalid JSON behind. The repo must
        // tolerate it (the user just loses their motion preferences for
        // every slot) rather than crash the app on first read.
        val (ctx, store) = fakePrefs()
        store["preferences"] = "{not valid json"
        val repo = MotionPreferenceRepository(ctx, json)
        assertTrue(repo.all().isEmpty())
        assertNull(repo.get("anything"))
    }

    @Test
    fun `remove of one slot does not disturb other slots' preferences`() {
        // Per-slot removal must touch only the named entry — important
        // when a physical pad is unbound but a virtual slot's preference
        // must survive.
        val (ctx, _) = fakePrefs()
        val repo = MotionPreferenceRepository(ctx, json)
        repo.put(MotionPreference("virtual", enabled = true))
        repo.put(MotionPreference("9", enabled = false))
        repo.remove("9")

        assertEquals(true, repo.get("virtual")?.enabled)
        assertNull(repo.get("9"))
    }

    @Test
    fun `put with same slot replaces in place — list never grows`() {
        // The contract test exercises this generically; this version pins
        // the absolute size to catch a regression where put accidentally
        // append-only's the list and persists duplicate entries.
        val (ctx, _) = fakePrefs()
        val repo = MotionPreferenceRepository(ctx, json)
        repo.put(MotionPreference("virtual", enabled = true))
        repo.put(MotionPreference("virtual", enabled = false))
        repo.put(MotionPreference("virtual", enabled = true))
        assertEquals(1, repo.all().size)
        assertEquals(true, repo.get("virtual")?.enabled)
    }

    // ── Test fixtures ────────────────────────────────────────────────────

    /**
     * Returns a Context + the underlying mutable map. Pass [seedFrom] to
     * mirror an earlier prefs' state (testing durability across a fresh
     * repo instance on the same prefs key).
     */
    private fun fakePrefs(seedFrom: Context? = null): Pair<Context, MutableMap<String, Any?>> {
        val store: MutableMap<String, Any?> =
            (seedFrom?.getSharedPreferences("motion_preferences", 0)?.all as? Map<String, Any?>)
                ?.toMutableMap()
                ?: mutableMapOf()

        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val keySlot = slot<String>()
        val strSlot = slot<String?>()
        every { editor.putString(capture(keySlot), captureNullable(strSlot)) } answers {
            store[keySlot.captured] = strSlot.captured
            editor
        }
        every { editor.remove(capture(keySlot)) } answers {
            store.remove(keySlot.captured)
            editor
        }
        every { editor.apply() } answers { /* no-op */ }

        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } answers {
            val k = firstArg<String>()
            val default = secondArg<String?>()
            (store[k] as? String) ?: default
        }
        every { prefs.edit() } returns editor
        every { prefs.all } answers { store.toMap() }

        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getSharedPreferences(any(), any()) } returns prefs
        return ctx to store
    }
}
