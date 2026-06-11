// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.core.model.DiscoveredServer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Identity is machineId-only (satellite docs/contract.md §Identity): remember
// is an upsert keyed on the stable id, and the upsert itself collapses any
// legacy ip:port row (key carried forward) — there is no separate
// reconciliation pass, and one physical box can never become two rows.
class ConnectionStoreIdentityTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun newStore(): ConnectionStore {
        val (ctx, _) = mapBackedPrefs()
        val satellites = RememberedSatelliteRepository(ctx, json)
        val bt = RememberedBtRepository(ctx, json)
        val keys = SatelliteSharedKeyRepository(ctx)
        return ConnectionStore(satellites, bt, keys, SatellitePinRepository(ctx))
    }

    private fun server(
        machineId: String,
        ip: String,
        name: String = "Pc",
    ) = DiscoveredServer(name = name, ip = ip, udpPort = 9876, machineId = machineId)

    @Test
    fun `the same machine at a new IP stays one row under its stable id`() {
        val store = newStore()
        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.5"))
        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.99"))

        assertEquals(1, store.remembered().size)
        assertEquals("satellite:mid:m1", store.remembered().single().id)
        assertEquals("10.0.0.99", store.remembered().single().ip)
    }

    @Test
    fun `two different machines stay two rows`() {
        val store = newStore()
        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.5", name = "A"))
        store.rememberSatellite(server(machineId = "m2", ip = "10.0.0.5", name = "B"))

        assertEquals(2, store.remembered().size)
    }

    @Test
    fun `the pairing key is keyed on the same stable id and dies with forget`() {
        val store = newStore()
        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.5"))
        store.setSatelliteSharedKey("satellite:mid:m1", "aa".repeat(32))
        assertEquals("aa".repeat(32), store.satelliteSharedKey("satellite:mid:m1"))

        store.forgetSatellite("satellite:mid:m1")

        assertNull(store.satelliteSharedKey("satellite:mid:m1"))
        assertEquals(0, store.remembered().size)
    }

    @Test
    fun `identity upgrade migrates the legacy row's pairing key so no re-pair is forced`() {
        val store = newStore()
        // Box first remembered before it advertised a machineId (legacy ip:port id).
        store.rememberSatellite(server(machineId = "", ip = "10.0.0.5"))
        val legacyId = "satellite:10.0.0.5:9876"
        store.setSatelliteSharedKey(legacyId, "aa".repeat(32))

        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.5"))

        assertEquals(listOf("satellite:mid:m1"), store.remembered().map { it.id })
        assertEquals("aa".repeat(32), store.satelliteSharedKey("satellite:mid:m1"))
        assertNull(store.satelliteSharedKey(legacyId))
    }

    @Test
    fun `identity upgrade keeps the stable row's own key and purges the legacy one`() {
        val store = newStore()
        store.rememberSatellite(server(machineId = "", ip = "10.0.0.5"))
        val legacyId = "satellite:10.0.0.5:9876"
        store.setSatelliteSharedKey(legacyId, "bb".repeat(32))
        // The box was meanwhile (re-)paired under its stable id: that key wins.
        store.setSatelliteSharedKey("satellite:mid:m1", "aa".repeat(32))

        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.5"))

        assertEquals(listOf("satellite:mid:m1"), store.remembered().map { it.id })
        assertEquals("aa".repeat(32), store.satelliteSharedKey("satellite:mid:m1"))
        assertNull(store.satelliteSharedKey(legacyId))
    }

    @Test
    fun `a beacon without machineId refreshes the known stable row instead of minting a ghost`() {
        val store = newStore()
        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.5", name = "Old"))

        store.rememberSatellite(server(machineId = "", ip = "10.0.0.5", name = "New"))

        assertEquals(listOf("satellite:mid:m1"), store.remembered().map { it.id })
        assertEquals("New", store.remembered().single().name)
    }
}
