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

// Behavioral coverage for the satellite list repository beyond the generic AbstractRepositoryContract:
// in particular the corrupt-decode fallback and its (current) silence. Mirrors RememberedBtRepositoryTest
// so the two list repositories document the same trace-less-loss behavior side by side.
class RememberedSatelliteRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun sat(
        id: String,
        name: String = "Box",
        ip: String = "10.0.0.1",
    ) = RememberedSatellite(
        id = id,
        name = name,
        ip = ip,
        udpPort = 9876,
        pairPort = 9443,
        httpPort = 9443,
        machineId = "",
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
        val repo = RememberedSatelliteRepository(ctx, json)
        repo.put(sat(id = "satellite:mid:abc", name = "Den"))
        assertEquals("Den", repo.get("satellite:mid:abc")?.name)
    }

    @Test
    fun `entries survive into a fresh repo over the same prefs`() {
        val (_, store) = mapBackedPrefs()
        RememberedSatelliteRepository(mapBackedPrefs(store).first, json).put(sat(id = "satellite:mid:abc"))
        val repo2 = RememberedSatelliteRepository(mapBackedPrefs(store).first, json)
        assertEquals("Box", repo2.get("satellite:mid:abc")?.name)
    }

    @Test
    fun `corrupt JSON falls back to empty without crashing`() {
        val (ctx, store) = mapBackedPrefs()
        store["satellite_list"] = "{not valid json"
        val repo = RememberedSatelliteRepository(ctx, json)
        assertTrue(repo.all().isEmpty())
        assertNull(repo.get("anything"))
    }

    @Test
    fun `corrupt JSON is dropped silently with no WARN breadcrumb`() {
        val (ctx, store) = mapBackedPrefs()
        store["satellite_list"] = "{not valid json"
        val repo = RememberedSatelliteRepository(ctx, json)
        repo.all()

        // Unlike MotionPreferenceRepository / TouchpadModeRepository, this repo logs nothing on a
        // decode failure, so a corrupt blob forgets every remembered satellite with no trace.
        // See summary: add a WARN breadcrumb for parity.
        verify(exactly = 0) { Log.w(any<String>(), any<String>()) }
        verify(exactly = 0) { Log.w(any<String>(), any<String>(), any<Throwable>()) }
    }
}
