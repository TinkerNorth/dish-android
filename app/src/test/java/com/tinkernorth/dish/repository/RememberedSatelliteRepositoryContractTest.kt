// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.architecture.interfaces.KeyedRepository
import com.tinkernorth.dish.architecture.testing.AbstractKeyedRepositoryContract
import kotlinx.serialization.json.Json
import kotlin.random.Random

class RememberedSatelliteRepositoryContractTest : AbstractKeyedRepositoryContract<String, RememberedSatellite>() {
    override fun newKeyedRepository(): KeyedRepository<String, RememberedSatellite> =
        RememberedSatelliteRepository(
            context = mapBackedPrefs().first,
            json = Json { ignoreUnknownKeys = true },
        )

    override fun newKey(): String = "satellite:1.1.1.${Random.nextInt(2, 250)}:${Random.nextInt(1, 65000)}"

    // Must be deterministic for a given key: contract recomputes expected via newValue(k).
    override fun newValue(key: String): RememberedSatellite {
        val seed = key.hashCode()
        return RememberedSatellite(
            id = key,
            name = "PC-${key.takeLast(4)}",
            ip = "1.1.1.${((seed and 0xFF) % 248) + 2}",
            udpPort = (seed and 0x7FFF) + 1,
            pairPort = ((seed shr 1) and 0x7FFF) + 1,
            httpPort = ((seed shr 2) and 0x7FFF) + 1,
        )
    }
}
