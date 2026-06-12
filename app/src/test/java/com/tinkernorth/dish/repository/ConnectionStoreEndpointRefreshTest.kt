// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.core.model.DiscoveredServer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionStoreEndpointRefreshTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun newStore(): ConnectionStore {
        val (ctx, _) = mapBackedPrefs()
        return ConnectionStore(
            RememberedSatelliteRepository(ctx, json),
            RememberedBtRepository(ctx, json),
            SatelliteSharedKeyRepository(ctx),
            SatellitePinRepository(ctx),
        )
    }

    private fun server(
        machineId: String,
        ip: String,
        name: String = "Pc",
        udpPort: Int = 9876,
        pairPort: Int = 9443,
        httpPort: Int = 9443,
    ) = DiscoveredServer(
        name = name,
        ip = ip,
        udpPort = udpPort,
        pairPort = pairPort,
        httpPort = httpPort,
        machineId = machineId,
    )

    @Test
    fun `a scan re-points a remembered satellite at its current address`() {
        val store = newStore()
        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.5"))

        store.refreshFromDiscovery(listOf(server(machineId = "m1", ip = "10.0.0.99")))

        val row = store.remembered().single()
        assertEquals("satellite:mid:m1", row.id)
        assertEquals("10.0.0.99", row.ip)
    }

    @Test
    fun `a scan refreshes name and ports alongside the address`() {
        val store = newStore()
        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.5", name = "Old"))

        store.refreshFromDiscovery(
            listOf(
                server(
                    machineId = "m1",
                    ip = "10.0.0.5",
                    name = "Renamed",
                    pairPort = 9444,
                    httpPort = 9445,
                ),
            ),
        )

        val row = store.remembered().single()
        assertEquals("Renamed", row.name)
        assertEquals(9444, row.pairPort)
        assertEquals(9445, row.httpPort)
    }

    @Test
    fun `a scan never adds a satellite the user has not remembered`() {
        val store = newStore()
        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.5"))

        store.refreshFromDiscovery(listOf(server(machineId = "m2", ip = "10.0.0.7")))

        assertEquals(listOf("satellite:mid:m1"), store.remembered().map { it.id })
    }

    @Test
    fun `a beacon without machineId never re-points a remembered row`() {
        val store = newStore()
        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.5"))

        store.refreshFromDiscovery(listOf(server(machineId = "", ip = "10.0.0.99")))

        assertEquals("10.0.0.5", store.remembered().single().ip)
    }

    @Test
    fun `a scan upgrades a legacy row to its stable id and carries the key`() {
        val store = newStore()
        store.rememberSatellite(server(machineId = "", ip = "10.0.0.5"))
        store.setSatelliteSharedKey("satellite:10.0.0.5:9876", "aa".repeat(32))

        store.refreshFromDiscovery(listOf(server(machineId = "m1", ip = "10.0.0.5")))

        assertEquals(listOf("satellite:mid:m1"), store.remembered().map { it.id })
        assertEquals("aa".repeat(32), store.satelliteSharedKey("satellite:mid:m1"))
        assertNull(store.satelliteSharedKey("satellite:10.0.0.5:9876"))
    }

    @Test
    fun `a scan refreshes only the rows it actually saw`() {
        val store = newStore()
        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.5"))
        store.rememberSatellite(server(machineId = "m2", ip = "10.0.0.6"))

        store.refreshFromDiscovery(listOf(server(machineId = "m1", ip = "10.0.0.99")))

        assertEquals("10.0.0.99", store.remembered().first { it.id == "satellite:mid:m1" }.ip)
        assertEquals("10.0.0.6", store.remembered().first { it.id == "satellite:mid:m2" }.ip)
    }

    @Test
    fun `an unchanged scan result leaves the row identical`() {
        val store = newStore()
        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.5"))
        val before = store.remembered().single()

        store.refreshFromDiscovery(listOf(server(machineId = "m1", ip = "10.0.0.5")))

        assertEquals(before, store.remembered().single())
    }

    @Test
    fun `the cert pin follows the box to its new address`() {
        val store = newStore()
        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.5"))
        store.satellitePins.pin("10.0.0.5", "fp-original")

        store.refreshFromDiscovery(listOf(server(machineId = "m1", ip = "10.0.0.99")))

        assertEquals("fp-original", store.satellitePins.pinnedFingerprint("10.0.0.99"))
        assertNull(store.satellitePins.pinnedFingerprint("10.0.0.5"))
    }

    @Test
    fun `a pin already trusted at the new address is not overwritten`() {
        val store = newStore()
        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.5"))
        store.satellitePins.pin("10.0.0.5", "fp-old-box")
        store.satellitePins.pin("10.0.0.99", "fp-new-box")

        store.refreshFromDiscovery(listOf(server(machineId = "m1", ip = "10.0.0.99")))

        assertEquals("fp-new-box", store.satellitePins.pinnedFingerprint("10.0.0.99"))
        assertNull(store.satellitePins.pinnedFingerprint("10.0.0.5"))
    }

    @Test
    fun `an unchanged address leaves the pin alone`() {
        val store = newStore()
        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.5"))
        store.satellitePins.pin("10.0.0.5", "fp-original")

        store.refreshFromDiscovery(listOf(server(machineId = "m1", ip = "10.0.0.5")))

        assertEquals("fp-original", store.satellitePins.pinnedFingerprint("10.0.0.5"))
    }

    @Test
    fun `remembering a successful session at a new address migrates the pin`() {
        val store = newStore()
        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.5"))
        store.satellitePins.pin("10.0.0.5", "fp-original")

        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.42"))

        assertEquals("fp-original", store.satellitePins.pinnedFingerprint("10.0.0.42"))
        assertNull(store.satellitePins.pinnedFingerprint("10.0.0.5"))
    }

    @Test
    fun `forget drops the cert pin with the row and the key`() {
        val store = newStore()
        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.5"))
        store.setSatelliteSharedKey("satellite:mid:m1", "aa".repeat(32))
        store.satellitePins.pin("10.0.0.5", "fp-original")

        store.forgetSatellite("satellite:mid:m1")

        assertNull(store.satellitePins.pinnedFingerprint("10.0.0.5"))
        assertNull(store.satelliteSharedKey("satellite:mid:m1"))
        assertEquals(0, store.remembered().size)
    }

    @Test
    fun `forget of an unknown id leaves other pins alone`() {
        val store = newStore()
        store.rememberSatellite(server(machineId = "m1", ip = "10.0.0.5"))
        store.satellitePins.pin("10.0.0.5", "fp-original")

        store.forgetSatellite("satellite:mid:other")

        assertEquals("fp-original", store.satellitePins.pinnedFingerprint("10.0.0.5"))
        assertEquals(1, store.remembered().size)
    }
}
