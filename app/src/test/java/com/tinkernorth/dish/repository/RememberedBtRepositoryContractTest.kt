// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.architecture.interfaces.KeyedRepository
import com.tinkernorth.dish.architecture.testing.AbstractKeyedRepositoryContract
import kotlinx.serialization.json.Json

class RememberedBtRepositoryContractTest : AbstractKeyedRepositoryContract<String, RememberedBt>() {
    override fun newKeyedRepository(): KeyedRepository<String, RememberedBt> =
        RememberedBtRepository(
            context = mapBackedPrefs().first,
            json = Json { ignoreUnknownKeys = true },
        )

    override fun keyFor(index: Int): String = "bt:$index"

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
