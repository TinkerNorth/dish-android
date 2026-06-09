// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.architecture.interfaces.Repository
import com.tinkernorth.dish.architecture.testing.AbstractRepositoryContract
import kotlinx.serialization.json.Json
import kotlin.random.Random

class RememberedBtRepositoryContractTest : AbstractRepositoryContract<String, RememberedBt>() {
    override fun newRepository(): Repository<String, RememberedBt> =
        RememberedBtRepository(
            context = mapBackedPrefs().first,
            json = Json { ignoreUnknownKeys = true },
        )

    override fun newKey(): String = "bt:${Random.nextInt(0, 256)}:${Random.nextInt(0, 256)}"

    // Must be deterministic for a given key: the contract recomputes expected via newValue(k).
    override fun newValue(key: String): RememberedBt {
        val seed = key.hashCode()
        return RememberedBt(
            id = key,
            name = "Pad-${key.takeLast(4)}",
            mac = key.removePrefix("bt:"),
            profileName = if (seed and 1 == 0) "XBOX" else "PLAYSTATION",
        )
    }
}
