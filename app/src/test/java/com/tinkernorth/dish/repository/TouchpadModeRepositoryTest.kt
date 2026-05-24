// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behaviour tests for [TouchpadModeRepository] — pins the JSON shape,
 * the absence-means-default contract, and the resilience-on-corrupt-prefs
 * fallback that matters for an app upgrade landing on a stale prefs file.
 *
 * Mirror of the [MotionPreferenceRepositoryTest] surface, because the two
 * repositories share the same JSON-list-in-SharedPreferences storage
 * pattern.
 */
class TouchpadModeRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun mockLog() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun unmockLog() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `all three valid modes round-trip through SharedPreferences`() {
        // The repository must persist the string verbatim — `off` is the
        // safe baseline default the server also uses, but the client may
        // legitimately persist `mouse` or `ds4` for a satellite that
        // supports those. Round-trip all three to pin the shape.
        val (ctx, _) = fakePrefs()
        val repo = TouchpadModeRepository(ctx, json)
        repo.put(TouchpadModePreference("sat-a", TouchpadModeValue.OFF))
        repo.put(TouchpadModePreference("sat-b", TouchpadModeValue.DS4))
        repo.put(TouchpadModePreference("sat-c", TouchpadModeValue.MOUSE))

        // Re-read through a fresh repo on the same backing prefs to prove
        // durability (no in-memory cache shortcut).
        val (ctx2, _) = fakePrefs(seedFrom = ctx)
        val repo2 = TouchpadModeRepository(ctx2, json)
        assertEquals(TouchpadModeValue.OFF, repo2.get("sat-a")?.mode)
        assertEquals(TouchpadModeValue.DS4, repo2.get("sat-b")?.mode)
        assertEquals(TouchpadModeValue.MOUSE, repo2.get("sat-c")?.mode)
    }

    @Test
    fun `get on a satellite that was never written returns null - not a default`() {
        // The repository never invents `off` for an unknown satellite —
        // that's the responsibility of the caller (TouchpadModeComposer or
        // the UI's default-fold). Pinning this keeps "never picked" and
        // "explicitly off" distinguishable, which matters for prompting
        // first-time users to pick a mode.
        val (ctx, _) = fakePrefs()
        val repo = TouchpadModeRepository(ctx, json)
        assertNull(repo.get("never-written"))
    }

    @Test
    fun `corrupt JSON in prefs falls back to empty - does not crash app startup`() {
        // An app crash mid-write, or a sideloaded build overwriting prefs,
        // could leave invalid JSON. The repo must tolerate it (every saved
        // mode reverts to default at the next call site) rather than
        // crashing app startup.
        val (ctx, store) = fakePrefs()
        store["preferences"] = "{not valid json"
        val repo = TouchpadModeRepository(ctx, json)
        assertTrue(repo.all().isEmpty())
        assertNull(repo.get("anything"))
    }

    @Test
    fun `corrupt JSON logs a WARN so a silent loss has a breadcrumb`() {
        val (ctx, store) = fakePrefs()
        store["preferences"] = "{not valid json"
        val repo = TouchpadModeRepository(ctx, json)
        repo.all()

        verify(atLeast = 1) {
            Log.w(
                eq("TouchpadModeRepository"),
                match<String> { it.contains("Failed to decode touchpad-mode list") },
            )
        }
    }

    @Test
    fun `remove of one satellite does not disturb other satellites' modes`() {
        val (ctx, _) = fakePrefs()
        val repo = TouchpadModeRepository(ctx, json)
        repo.put(TouchpadModePreference("sat-a", TouchpadModeValue.MOUSE))
        repo.put(TouchpadModePreference("sat-b", TouchpadModeValue.DS4))
        repo.remove("sat-b")

        assertEquals(TouchpadModeValue.MOUSE, repo.get("sat-a")?.mode)
        assertNull(repo.get("sat-b"))
    }

    @Test
    fun `put with same satellite id replaces in place - list never grows`() {
        // Catches a regression where put would append rather than replace,
        // producing duplicate entries and an unstable read order.
        val (ctx, _) = fakePrefs()
        val repo = TouchpadModeRepository(ctx, json)
        repo.put(TouchpadModePreference("sat-a", TouchpadModeValue.OFF))
        repo.put(TouchpadModePreference("sat-a", TouchpadModeValue.MOUSE))
        repo.put(TouchpadModePreference("sat-a", TouchpadModeValue.DS4))
        assertEquals(1, repo.all().size)
        assertEquals(TouchpadModeValue.DS4, repo.get("sat-a")?.mode)
    }

    @Test
    fun `TouchpadModeValue isValid accepts only the three wire strings`() {
        // The string-as-wire choice means the picker validates at the
        // boundary; this helper is that boundary. Catch a regression that
        // accidentally accepts e.g. "Pad" (label-case) or empty.
        assertTrue(TouchpadModeValue.isValid("off"))
        assertTrue(TouchpadModeValue.isValid("ds4"))
        assertTrue(TouchpadModeValue.isValid("mouse"))
        assertFalse(TouchpadModeValue.isValid(""))
        assertFalse(TouchpadModeValue.isValid(null))
        assertFalse(TouchpadModeValue.isValid("Pad"))
        assertFalse(TouchpadModeValue.isValid("DS4"))
        assertFalse(TouchpadModeValue.isValid("MOUSE"))
        assertFalse(TouchpadModeValue.isValid("pad"))
        assertFalse(TouchpadModeValue.isValid("touchpad"))
    }

    @Test
    fun `TouchpadModeValue ALL list is the three modes in canonical order`() {
        // Some callers iterate ALL to populate the picker; pin the order
        // so the picker UI is stable across builds.
        assertEquals(listOf("off", "ds4", "mouse"), TouchpadModeValue.ALL)
    }

    // ── Test fixtures ────────────────────────────────────────────────────

    private fun fakePrefs(seedFrom: Context? = null): Pair<Context, MutableMap<String, Any?>> {
        val store: MutableMap<String, Any?> =
            (seedFrom?.getSharedPreferences("touchpad_mode_preferences", 0)?.all as? Map<String, Any?>)
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
