// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.repository

import android.util.Log
import com.tinkernorth.dish.core.net.moonlight.AUTO
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.core.net.moonlight.RememberedMoonlight
import io.mockk.every
import io.mockk.mockkStatic
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

// Behavioural coverage beyond the keyed contract, mirroring RememberedSatelliteRepositoryTest: the
// observable mirror, the corrupt-decode fallback and its breadcrumb, and the paired migration default.
class RememberedMoonlightRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun host(
        id: String,
        name: String = "PC",
        paired: Boolean = true,
    ) = RememberedMoonlight(
        id = id,
        name = name,
        address = "10.0.0.9",
        uniqueId = id.removePrefix("moonlight:uid:"),
        paired = paired,
    )

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
    fun `put then get round-trips by id`() {
        val (ctx, _) = mapBackedPrefs()
        val repo = RememberedMoonlightRepository(ctx, json)
        repo.put(host(id = "moonlight:uid:abc", name = "Den"))
        assertEquals("Den", repo.get("moonlight:uid:abc")?.name)
    }

    @Test
    fun `entries follows put remove and clear`() {
        val (ctx, _) = mapBackedPrefs()
        val repo = RememberedMoonlightRepository(ctx, json)
        assertEquals(emptyList<RememberedMoonlight>(), repo.entries.value)
        repo.put(host(id = "moonlight:uid:a"))
        repo.put(host(id = "moonlight:uid:b"))
        assertEquals(listOf("moonlight:uid:a", "moonlight:uid:b"), repo.entries.value.map { it.id })
        repo.remove("moonlight:uid:a")
        assertEquals(listOf("moonlight:uid:b"), repo.entries.value.map { it.id })
        repo.clear()
        assertEquals(emptyList<RememberedMoonlight>(), repo.entries.value)
    }

    @Test
    fun `entries survive into a fresh repo over the same prefs`() {
        val (_, store) = mapBackedPrefs()
        RememberedMoonlightRepository(mapBackedPrefs(store).first, json).put(host(id = "moonlight:uid:abc"))
        val repo2 = RememberedMoonlightRepository(mapBackedPrefs(store).first, json)
        assertEquals("PC", repo2.get("moonlight:uid:abc")?.name)
    }

    @Test
    fun `an interest row keeps paired false across a reload`() {
        val (_, store) = mapBackedPrefs()
        RememberedMoonlightRepository(mapBackedPrefs(store).first, json).put(host(id = "moonlight:uid:abc", paired = false))
        val repo2 = RememberedMoonlightRepository(mapBackedPrefs(store).first, json)
        assertFalse(repo2.get("moonlight:uid:abc")?.paired == true)
    }

    @Test
    fun `a record written before paired existed reads back as paired`() {
        val (ctx, store) = mapBackedPrefs()
        store["moonlight_host_list"] = """[{"id":"moonlight:uid:x","name":"PC","address":"10.0.0.9","uniqueId":"x"}]"""
        val row = RememberedMoonlightRepository(ctx, json).all().single()
        assertTrue(row.paired)
        assertEquals(AUTO, row.emulatedType)
        assertEquals(MoonlightHost.DEFAULT_HTTP_PORT, row.httpPort)
        assertEquals(MoonlightHost.DEFAULT_HTTPS_PORT, row.httpsPort)
    }

    @Test
    fun `corrupt JSON falls back to empty without crashing`() {
        val (ctx, store) = mapBackedPrefs()
        store["moonlight_host_list"] = "{not valid json"
        val repo = RememberedMoonlightRepository(ctx, json)
        assertTrue(repo.all().isEmpty())
        assertNull(repo.get("anything"))
    }

    @Test
    fun `corrupt JSON logs a WARN breadcrumb`() {
        val (ctx, store) = mapBackedPrefs()
        store["moonlight_host_list"] = "{not valid json"
        val repo = RememberedMoonlightRepository(ctx, json)
        repo.all()

        verify(atLeast = 1) {
            Log.w(
                any<String>(),
                match<String> { it.contains("Failed to decode moonlight host list") },
            )
        }
    }
}
