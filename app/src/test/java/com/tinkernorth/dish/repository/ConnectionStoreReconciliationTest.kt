// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.core.model.DiscoveredServer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Exercises ConnectionStore.reconcileLegacyGhosts, the machineId migration that collapses
// per-IP "ghost" rows (the duplicate-connection bug) into one stable id. All three repositories
// share a single backing prefs map, the way they share the real connection_store file.
class ConnectionStoreReconciliationTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun newStore(): Pair<ConnectionStore, MutableMap<String, Any?>> {
        val (ctx, backing) = mapBackedPrefs()
        val satellites = RememberedSatelliteRepository(ctx, json)
        val bt = RememberedBtRepository(ctx, json)
        val keys = SatelliteSharedKeyRepository(ctx)
        return ConnectionStore(satellites, bt, keys) to backing
    }

    private fun legacy(
        ip: String,
        name: String,
        udpPort: Int = 9876,
    ) = RememberedSatellite(
        id = "satellite:$ip:$udpPort",
        name = name,
        ip = ip,
        udpPort = udpPort,
        pairPort = 9443,
        httpPort = 9443,
        machineId = "",
    )

    private fun discovered(
        ip: String,
        name: String,
        machineId: String,
        udpPort: Int = 9876,
    ) = DiscoveredServer(name = name, ip = ip, udpPort = udpPort, machineId = machineId)

    @Test
    fun `remembering the same box under its machineId adopts the legacy shared key and drops the ghost`() {
        val (store, _) = newStore()
        store.satellites.put(legacy(ip = "10.0.0.1", name = "Box"))
        store.setSatelliteSharedKey("satellite:10.0.0.1:9876", "OLDKEY")

        store.rememberSatellite(discovered(ip = "10.0.0.1", name = "Box", machineId = "abc"))

        val all = store.remembered()
        assertEquals(1, all.size)
        assertEquals("satellite:mid:abc", all.single().id)
        assertEquals("OLDKEY", store.satelliteSharedKey("satellite:mid:abc"))
        assertNull(store.satelliteSharedKey("satellite:10.0.0.1:9876"))
    }

    @Test
    fun `a name-matched ghost stranded at a dead IP is collapsed into the stable id`() {
        val (store, _) = newStore()
        store.satellites.put(legacy(ip = "192.168.1.50", name = "Box"))

        store.rememberSatellite(discovered(ip = "10.0.0.1", name = "Box", machineId = "abc"))

        val all = store.remembered()
        assertEquals(1, all.size)
        assertEquals("satellite:mid:abc", all.single().id)
    }

    @Test
    fun `reconciliation never removes a different, stably-identified satellite that shares a name`() {
        val (store, _) = newStore()
        store.satellites.put(
            RememberedSatellite(
                id = "satellite:mid:other",
                name = "Box",
                ip = "1.2.3.4",
                udpPort = 9876,
                pairPort = 9443,
                httpPort = 9443,
                machineId = "other",
            ),
        )

        store.rememberSatellite(discovered(ip = "10.0.0.1", name = "Box", machineId = "abc"))

        val ids = store.remembered().map { it.id }.toSet()
        assertEquals(setOf("satellite:mid:other", "satellite:mid:abc"), ids)
    }

    @Test
    fun `an existing key on the stable id is not overwritten by the legacy key`() {
        val (store, _) = newStore()
        store.satellites.put(legacy(ip = "10.0.0.1", name = "Box"))
        store.setSatelliteSharedKey("satellite:10.0.0.1:9876", "OLDKEY")
        store.setSatelliteSharedKey("satellite:mid:abc", "NEWKEY")

        store.rememberSatellite(discovered(ip = "10.0.0.1", name = "Box", machineId = "abc"))

        assertEquals("NEWKEY", store.satelliteSharedKey("satellite:mid:abc"))
    }

    @Test
    fun `remembering a satellite without a machineId never reconciles, so legacy per-IP rows still accrue`() {
        val (store, _) = newStore()
        store.satellites.put(legacy(ip = "10.0.0.1", name = "Box"))

        // No machineId: the migration is skipped entirely, reproducing the original per-IP duplication.
        store.rememberSatellite(discovered(ip = "10.0.0.2", name = "Box", machineId = ""))

        assertEquals(2, store.remembered().size)
    }

    @Test
    fun `KNOWN RISK a legacy row sharing only a display name is forgotten even if it is a different box`() {
        val (store, _) = newStore()
        // A genuinely different machine the user happened to name the same before machineIds existed.
        store.satellites.put(legacy(ip = "172.16.0.9", name = "Living Room PC"))

        store.rememberSatellite(discovered(ip = "10.0.0.1", name = "Living Room PC", machineId = "abc"))

        // The unrelated legacy entry is collapsed purely on the name match. See summary: over-forget risk.
        assertTrue(store.remembered().none { it.id == "satellite:172.16.0.9:9876" })
        assertEquals(1, store.remembered().size)
    }

    @Test
    fun `a name-matched ghost at a stale IP is collapsed and its key is adopted onto the stable id`() {
        val (store, _) = newStore()
        // The remembered row (and its key) live at an OLD ip, different from where the box is seen now.
        store.satellites.put(legacy(ip = "192.168.1.50", name = "Box"))
        store.setSatelliteSharedKey("satellite:192.168.1.50:9876", "OLDKEY")

        // The box now advertises a machineId at a NEW ip. Reconciliation adopts the ghost's key onto the
        // stable id before dropping the row, so the already-paired box is not forced to re-pair.
        store.rememberSatellite(discovered(ip = "10.0.0.1", name = "Box", machineId = "abc"))

        assertEquals(1, store.remembered().size)
        assertEquals("satellite:mid:abc", store.remembered().single().id)
        assertEquals("OLDKEY", store.satelliteSharedKey("satellite:mid:abc"))
        assertNull(store.satelliteSharedKey("satellite:192.168.1.50:9876"))
    }
}
