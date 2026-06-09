// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RememberedBtRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun entry(
        id: String,
        name: String = "Pad",
        mac: String = "AA:BB",
        profile: String = "XBOX",
    ) = RememberedBt(id = id, name = name, mac = mac, profileName = profile)

    @Before
    fun mockLog() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun unmockLog() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `put then get round-trips an entry by id`() {
        val (ctx, _) = mapBackedPrefs()
        val repo = RememberedBtRepository(ctx, json)
        repo.put(entry(id = "bt:AA:BB", profile = "PLAYSTATION"))
        assertEquals("PLAYSTATION", repo.get("bt:AA:BB")?.profileName)
    }

    @Test
    fun `keyOf reads the id off the value`() {
        val repo = RememberedBtRepository(mapBackedPrefs().first, json)
        assertEquals("bt:Z", repo.keyOf(entry(id = "bt:Z")))
    }

    @Test
    fun `put with the same id replaces in place so the list never grows`() {
        val (ctx, _) = mapBackedPrefs()
        val repo = RememberedBtRepository(ctx, json)
        repo.put(entry(id = "bt:AA:BB", name = "First"))
        repo.put(entry(id = "bt:AA:BB", name = "Second"))
        assertEquals(1, repo.all().size)
        assertEquals("Second", repo.get("bt:AA:BB")?.name)
    }

    @Test
    fun `remove of one host leaves the others`() {
        val (ctx, _) = mapBackedPrefs()
        val repo = RememberedBtRepository(ctx, json)
        repo.put(entry(id = "bt:A"))
        repo.put(entry(id = "bt:B"))
        repo.remove("bt:A")
        assertNull(repo.get("bt:A"))
        assertEquals(listOf("bt:B"), repo.all().map { it.id })
    }

    @Test
    fun `entries survive into a fresh repo over the same prefs`() {
        val (_, store) = mapBackedPrefs()
        RememberedBtRepository(mapBackedPrefs(store).first, json).put(entry(id = "bt:A", name = "Durable"))
        val repo2 = RememberedBtRepository(mapBackedPrefs(store).first, json)
        assertEquals("Durable", repo2.get("bt:A")?.name)
    }

    @Test
    fun `corrupt JSON falls back to empty without crashing`() {
        val (ctx, store) = mapBackedPrefs()
        store["bt_list"] = "{not valid json"
        val repo = RememberedBtRepository(ctx, json)
        assertTrue(repo.all().isEmpty())
        assertNull(repo.get("anything"))
    }

    @Test
    fun `corrupt JSON is dropped silently with no WARN breadcrumb`() {
        val (ctx, store) = mapBackedPrefs()
        store["bt_list"] = "{not valid json"
        val repo = RememberedBtRepository(ctx, json)
        repo.all()

        // Unlike MotionPreferenceRepository, this repo logs nothing on a decode failure.
        // See summary: a corrupt blob forgets every paired controller with no trace.
        verify(exactly = 0) { Log.w(any<String>(), any<String>()) }
        verify(exactly = 0) { Log.w(any<String>(), any<String>(), any<Throwable>()) }
    }
}
