// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteSharedKeyRepositoryTest {
    @Test
    fun `put then get round-trips a key by id`() {
        val (ctx, _) = mapBackedPrefs()
        val repo = SatelliteSharedKeyRepository(ctx)
        repo.put("satellite:mid:abc", "DEADBEEF")
        assertEquals("DEADBEEF", repo.get("satellite:mid:abc"))
    }

    @Test
    fun `get for an unknown id is null`() {
        val (ctx, _) = mapBackedPrefs()
        val repo = SatelliteSharedKeyRepository(ctx)
        assertNull(repo.get("satellite:mid:nope"))
    }

    @Test
    fun `keys survive into a fresh repo over the same prefs`() {
        val (ctx, store) = mapBackedPrefs()
        SatelliteSharedKeyRepository(ctx).put("satellite:mid:abc", "KEY1")

        val repo2 = SatelliteSharedKeyRepository(mapBackedPrefs(store).first)
        assertEquals("KEY1", repo2.get("satellite:mid:abc"))
    }

    @Test
    fun `remove drops one key and leaves the others`() {
        val (ctx, _) = mapBackedPrefs()
        val repo = SatelliteSharedKeyRepository(ctx)
        repo.put("satellite:mid:a", "A")
        repo.put("satellite:mid:b", "B")

        repo.remove("satellite:mid:a")

        assertNull(repo.get("satellite:mid:a"))
        assertEquals("B", repo.get("satellite:mid:b"))
    }

    @Test
    fun `all returns only shared-key values and ignores sibling prefs entries`() {
        val (ctx, store) = mapBackedPrefs()
        // Co-tenant keys in the same connection_store prefs file must not leak into all().
        store["satellite_list"] = """[{"id":"x"}]"""
        store["bt_list"] = """[{"id":"y"}]"""
        val repo = SatelliteSharedKeyRepository(ctx)
        repo.put("satellite:mid:a", "A")
        repo.put("satellite:mid:b", "B")

        assertEquals(setOf("A", "B"), repo.all().toSet())
    }

    @Test
    fun `clear removes every shared key but preserves co-tenant prefs entries`() {
        val (ctx, store) = mapBackedPrefs()
        store["satellite_list"] = "preserved"
        val repo = SatelliteSharedKeyRepository(ctx)
        repo.put("satellite:mid:a", "A")
        repo.put("satellite:mid:b", "B")

        repo.clear()

        assertTrue(repo.all().isEmpty())
        assertEquals("preserved", store["satellite_list"])
    }
}
