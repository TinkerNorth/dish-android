// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.core.model.DiscoveredServer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

// Guards the observable seam that lets ConnectionsComposer derive the connections list purely from
// flows. Before this, the composer read store.remembered()/rememberedBt() out of band, so a
// remember/forget with no coincident upstream tick left a stale or ghost row.
class ConnectionStoreFlowTest {
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

    private fun discovered(
        ip: String,
        name: String,
        machineId: String,
    ) = DiscoveredServer(name = name, ip = ip, udpPort = 9876, machineId = machineId)

    @Test
    fun `rememberedSatellitesFlow tracks remember and forget`() {
        val store = newStore()
        assertEquals(emptyList<RememberedSatellite>(), store.rememberedSatellitesFlow.value)

        store.rememberSatellite(discovered("10.0.0.1", "Box", "abc"))
        assertEquals(listOf("satellite:mid:abc"), store.rememberedSatellitesFlow.value.map { it.id })

        store.forgetSatellite("satellite:mid:abc")
        assertEquals(emptyList<RememberedSatellite>(), store.rememberedSatellitesFlow.value)
    }

    @Test
    fun `rememberedBtFlow tracks remember and forget`() {
        val store = newStore()
        assertEquals(emptyList<RememberedBt>(), store.rememberedBtFlow.value)

        store.rememberBt(RememberedBt(id = "bt:AA", name = "Pad", mac = "AA", profileName = "XBOX"))
        assertEquals(listOf("bt:AA"), store.rememberedBtFlow.value.map { it.id })

        store.forgetBt("bt:AA")
        assertEquals(emptyList<RememberedBt>(), store.rememberedBtFlow.value)
    }

    @Test
    fun `the satellite flow reflects a reconciliation that collapses a ghost`() {
        val store = newStore()
        store.rememberSatellite(discovered("10.0.0.1", "Box", "")) // legacy per-ip row
        assertEquals(1, store.rememberedSatellitesFlow.value.size)

        store.rememberSatellite(discovered("10.0.0.1", "Box", "abc")) // same box, now with a machineId
        assertEquals(listOf("satellite:mid:abc"), store.rememberedSatellitesFlow.value.map { it.id })
    }
}
