// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteSharedKeyRepositoryTest {
    private fun repoOver(
        store: MutableMap<String, Any?>? = null,
        sealer: SecretSealer = ReversingSealer(),
    ): Pair<SatelliteSharedKeyRepository, MutableMap<String, Any?>> {
        val (ctx, backing) = mapBackedPrefs(store)
        return SatelliteSharedKeyRepository(ctx, sealer) to backing
    }

    @Test
    fun `put then get round-trips a key by id`() {
        val (repo, _) = repoOver()
        repo.put("satellite:mid:abc", "DEADBEEF")
        assertEquals("DEADBEEF", repo.get("satellite:mid:abc"))
    }

    @Test
    fun `get for an unknown id is null`() {
        val (repo, _) = repoOver()
        assertNull(repo.get("satellite:mid:nope"))
    }

    @Test
    fun `keys survive into a fresh repo over the same prefs`() {
        val (repo, store) = repoOver()
        repo.put("satellite:mid:abc", "KEY1")
        val (repo2, _) = repoOver(store)
        assertEquals("KEY1", repo2.get("satellite:mid:abc"))
    }

    @Test
    fun `remove drops one key and leaves the others`() {
        val (repo, _) = repoOver()
        repo.put("satellite:mid:a", "A")
        repo.put("satellite:mid:b", "B")
        repo.remove("satellite:mid:a")
        assertNull(repo.get("satellite:mid:a"))
        assertEquals("B", repo.get("satellite:mid:b"))
    }

    @Test
    fun `all returns only shared-key values and ignores sibling prefs entries`() {
        val (repo, store) = repoOver()
        // Co-tenant keys in the same connection_store prefs file must not leak into all().
        store["satellite_list"] = """[{"id":"x"}]"""
        store["bt_list"] = """[{"id":"y"}]"""
        repo.put("satellite:mid:a", "A")
        repo.put("satellite:mid:b", "B")
        assertEquals(setOf("A", "B"), repo.all().toSet())
    }

    @Test
    fun `clear removes every shared key but preserves co-tenant prefs entries`() {
        val (repo, store) = repoOver()
        store["satellite_list"] = "preserved"
        repo.put("satellite:mid:a", "A")
        repo.put("satellite:mid:b", "B")
        repo.clear()
        assertTrue(repo.all().isEmpty())
        assertEquals("preserved", store["satellite_list"])
    }

    @Test
    fun `a stored key is sealed, never the key itself`() {
        val (repo, store) = repoOver()
        repo.put("satellite:mid:a", "DEADBEEF")
        val stored = store["satellite_shared_key:satellite:mid:a"] as String
        assertFalse("the prefs hold the sealed form", stored.contains("DEADBEEF"))
        assertTrue(stored.startsWith("sealed:"))
    }

    @Test
    fun `a key an older build stored in the clear is read as it is, and sealed on that read`() {
        val (repo, store) = repoOver(mutableMapOf("satellite_shared_key:satellite:mid:old" to "CAFEBABE"))
        assertEquals("CAFEBABE", repo.get("satellite:mid:old"))
        val stored = store["satellite_shared_key:satellite:mid:old"] as String
        assertTrue("sealed on first read", stored.startsWith("sealed:"))
        assertEquals("still readable through the seal", "CAFEBABE", repo.get("satellite:mid:old"))
    }

    @Test
    fun `all seals the keys it reads in the clear too`() {
        val (repo, store) = repoOver(mutableMapOf("satellite_shared_key:satellite:mid:old" to "CAFEBABE"))
        assertEquals(listOf("CAFEBABE"), repo.all())
        assertTrue((store["satellite_shared_key:satellite:mid:old"] as String).startsWith("sealed:"))
    }

    @Test
    fun `a sealed key that no longer opens reads as absent and is dropped`() {
        val (writer, store) = repoOver()
        writer.put("satellite:mid:a", "DEADBEEF")
        val (repo, _) = repoOver(store, ReversingSealer(refusing = true))
        assertNull(repo.get("satellite:mid:a"))
        assertTrue(repo.all().isEmpty())
        assertFalse(store.containsKey("satellite_shared_key:satellite:mid:a"))
    }
}
