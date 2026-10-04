// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.architecture.interfaces.KeyedRepository
import com.tinkernorth.dish.architecture.testing.AbstractKeyedRepositoryContract
import kotlinx.serialization.json.Json

class MoonlightBindingRepositoryContractTest : AbstractKeyedRepositoryContract<String, RememberedBinding>() {
    override fun newKeyedRepository(): KeyedRepository<String, RememberedBinding> =
        MoonlightBindingRepository(
            context = mapBackedPrefs().first,
            json = Json { ignoreUnknownKeys = true },
        )

    override fun keyFor(index: Int): String = "pad:$index"

    // Deterministic per key: the contract recomputes the expected value through newValue(k).
    override fun newValue(key: String): RememberedBinding =
        RememberedBinding(
            descriptor = key,
            hostId = "moonlight:10.0.0.${(key.hashCode() and 0xFF) % 248 + 2}",
            controllerType = key.hashCode() and 3,
        )
}
