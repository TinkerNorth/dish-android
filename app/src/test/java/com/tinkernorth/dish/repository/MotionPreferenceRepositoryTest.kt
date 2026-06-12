// SPDX-License-Identifier: LGPL-3.0-or-later

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MotionPreferenceRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `enabled true and false round-trip through SharedPreferences`() {
        val (ctx, _) = fakePrefs()
        val repo = MotionPreferenceRepository(ctx, json)
        repo.put(MotionPreference("virtual", enabled = true))
        repo.put(MotionPreference("9", enabled = false))

        // Fresh repo on same backing prefs proves durability (no in-memory cache shortcut).
        val (ctx2, _) = fakePrefs(seedFrom = ctx)
        val repo2 = MotionPreferenceRepository(ctx2, json)
        assertEquals(true, repo2.get("virtual")?.enabled)
        assertEquals(false, repo2.get("9")?.enabled)
    }

    @Test
    fun `get on a slot that was never written returns null, not a default boolean`() {
        // Repo stays honest so store can distinguish "undecided" from "explicitly enabled".
        val (ctx, _) = fakePrefs()
        val repo = MotionPreferenceRepository(ctx, json)
        assertNull(repo.get("never-written"))
    }

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
    fun `corrupt JSON in prefs falls back to empty, does not crash app startup`() {
        val (ctx, store) = fakePrefs()
        store["preferences"] = "{not valid json"
        val repo = MotionPreferenceRepository(ctx, json)
        assertTrue(repo.all().isEmpty())
        assertNull(repo.get("anything"))
    }

    @Test
    fun `corrupt JSON logs a WARN so the silent toggle-loss has a breadcrumb`() {
        val (ctx, store) = fakePrefs()
        store["preferences"] = "{not valid json"
        val repo = MotionPreferenceRepository(ctx, json)
        repo.all()

        verify(atLeast = 1) {
            Log.w(
                eq("MotionPreferenceRepository"),
                match<String> { it.contains("Failed to decode motion-preference list") },
            )
        }
    }

    @Test
    fun `remove of one slot does not disturb other slots' preferences`() {
        val (ctx, _) = fakePrefs()
        val repo = MotionPreferenceRepository(ctx, json)
        repo.put(MotionPreference("virtual", enabled = true))
        repo.put(MotionPreference("9", enabled = false))
        repo.remove("9")

        assertEquals(true, repo.get("virtual")?.enabled)
        assertNull(repo.get("9"))
    }

    @Test
    fun `put with same slot replaces in place, list never grows`() {
        val (ctx, _) = fakePrefs()
        val repo = MotionPreferenceRepository(ctx, json)
        repo.put(MotionPreference("virtual", enabled = true))
        repo.put(MotionPreference("virtual", enabled = false))
        repo.put(MotionPreference("virtual", enabled = true))
        assertEquals(1, repo.all().size)
        assertEquals(true, repo.get("virtual")?.enabled)
    }

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
        every { editor.apply() } answers { }

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
