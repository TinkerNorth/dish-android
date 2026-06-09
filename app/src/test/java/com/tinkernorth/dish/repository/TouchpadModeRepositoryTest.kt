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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
        val (ctx, _) = fakePrefs()
        val repo = TouchpadModeRepository(ctx, json)
        repo.put(TouchpadModePreference("sat-a", TouchpadModeValue.OFF))
        repo.put(TouchpadModePreference("sat-b", TouchpadModeValue.DS4))
        repo.put(TouchpadModePreference("sat-c", TouchpadModeValue.MOUSE))

        // Fresh repo on same backing prefs proves durability (no in-memory cache shortcut).
        val (ctx2, _) = fakePrefs(seedFrom = ctx)
        val repo2 = TouchpadModeRepository(ctx2, json)
        assertEquals(TouchpadModeValue.OFF, repo2.get("sat-a")?.mode)
        assertEquals(TouchpadModeValue.DS4, repo2.get("sat-b")?.mode)
        assertEquals(TouchpadModeValue.MOUSE, repo2.get("sat-c")?.mode)
    }

    @Test
    fun `get on a satellite that was never written returns null - not a default`() {
        val (ctx, _) = fakePrefs()
        val repo = TouchpadModeRepository(ctx, json)
        assertNull(repo.get("never-written"))
    }

    @Test
    fun `corrupt JSON in prefs falls back to empty - does not crash app startup`() {
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
    fun `put of an invalid mode does not round-trip`() {
        val (ctx, _) = fakePrefs()
        val repo = TouchpadModeRepository(ctx, json)
        repo.put(TouchpadModePreference("sat-a", "garbage"))

        // Rejected at the door: nothing persisted, so a typo cannot survive a reload.
        assertNull(repo.get("sat-a"))
        assertTrue(repo.all().isEmpty())
    }

    @Test
    fun `put of an invalid mode does not overwrite an existing valid mode`() {
        val (ctx, _) = fakePrefs()
        val repo = TouchpadModeRepository(ctx, json)
        repo.put(TouchpadModePreference("sat-a", TouchpadModeValue.DS4))
        repo.put(TouchpadModePreference("sat-a", "garbage"))
        assertEquals(TouchpadModeValue.DS4, repo.get("sat-a")?.mode)
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
        // Picker UI iterates ALL; pin order so the displayed list is stable across builds.
        assertEquals(listOf("off", "ds4", "mouse"), TouchpadModeValue.ALL)
    }

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
