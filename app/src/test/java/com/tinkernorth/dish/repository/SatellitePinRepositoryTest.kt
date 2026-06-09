// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SatellitePinRepositoryTest {
    @Test
    fun `pin then read round-trips a fingerprint by satellite id`() {
        val (ctx, _) = mapBackedPrefs()
        val repo = SatellitePinRepository(ctx)
        repo.pin("satellite:mid:abc", "deadbeef")
        assertEquals("deadbeef", repo.pinnedFingerprint("satellite:mid:abc"))
    }

    @Test
    fun `pinnedFingerprint for an unknown satellite is null`() {
        val (ctx, _) = mapBackedPrefs()
        val repo = SatellitePinRepository(ctx)
        assertNull(repo.pinnedFingerprint("satellite:mid:nope"))
    }

    @Test
    fun `pins survive into a fresh repo over the same prefs`() {
        val (ctx, store) = mapBackedPrefs()
        SatellitePinRepository(ctx).pin("satellite:mid:abc", "AABB")

        val repo2 = SatellitePinRepository(mapBackedPrefs(store).first)
        assertEquals("AABB", repo2.pinnedFingerprint("satellite:mid:abc"))
    }

    @Test
    fun `forget drops one pin and leaves the others`() {
        val (ctx, _) = mapBackedPrefs()
        val repo = SatellitePinRepository(ctx)
        repo.pin("satellite:mid:a", "AA")
        repo.pin("satellite:mid:b", "BB")

        repo.forget("satellite:mid:a")

        assertNull(repo.pinnedFingerprint("satellite:mid:a"))
        assertEquals("BB", repo.pinnedFingerprint("satellite:mid:b"))
    }

    @Test
    fun `distinct satellites keep isolated pins`() {
        val (ctx, _) = mapBackedPrefs()
        val repo = SatellitePinRepository(ctx)
        repo.pin("10.0.0.1", "1111")
        repo.pin("10.0.0.2", "2222")

        assertEquals("1111", repo.pinnedFingerprint("10.0.0.1"))
        assertEquals("2222", repo.pinnedFingerprint("10.0.0.2"))
    }

    @Test
    fun `pin does not leak into a sibling shared-key repo over the same prefs`() {
        // Both repos co-tenant the connection_store prefs file under different key prefixes.
        val (ctx, store) = mapBackedPrefs()
        SatellitePinRepository(ctx).pin("satellite:mid:a", "CAFE")

        val keys = SatelliteSharedKeyRepository(mapBackedPrefs(store).first)
        assertNull("cert pin must not surface as a shared key", keys.get("satellite:mid:a"))
        assertNotNull(SatellitePinRepository(mapBackedPrefs(store).first).pinnedFingerprint("satellite:mid:a"))
    }
}
