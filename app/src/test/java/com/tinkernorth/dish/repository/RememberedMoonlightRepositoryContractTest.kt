// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.architecture.interfaces.KeyedRepository
import com.tinkernorth.dish.architecture.testing.AbstractKeyedRepositoryContract
import com.tinkernorth.dish.core.net.moonlight.RememberedMoonlight
import kotlinx.serialization.json.Json

class RememberedMoonlightRepositoryContractTest : AbstractKeyedRepositoryContract<String, RememberedMoonlight>() {
    override fun newKeyedRepository(): KeyedRepository<String, RememberedMoonlight> =
        RememberedMoonlightRepository(
            context = mapBackedPrefs().first,
            json = Json { ignoreUnknownKeys = true },
        )

    override fun keyFor(index: Int): String = "moonlight:uid:$index"

    // Must be deterministic for a given key: the contract recomputes expected via newValue(k).
    override fun newValue(key: String): RememberedMoonlight {
        val seed = key.hashCode()
        return RememberedMoonlight(
            id = key,
            name = "PC-${key.takeLast(4)}",
            address = "10.0.0.${((seed and 0xFF) % 248) + 2}",
            uniqueId = key.removePrefix("moonlight:uid:"),
            paired = seed and 1 == 0,
        )
    }
}
