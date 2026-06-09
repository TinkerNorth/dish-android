// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.architecture.interfaces.Repository
import com.tinkernorth.dish.architecture.testing.AbstractRepositoryContract
import kotlinx.serialization.json.Json
import kotlin.random.Random

class TouchpadModeRepositoryContractTest : AbstractRepositoryContract<String, TouchpadModePreference>() {
    override fun newRepository(): Repository<String, TouchpadModePreference> =
        TouchpadModeRepository(
            context = mapBackedPrefs().first,
            json = Json { ignoreUnknownKeys = true },
        )

    override fun newKey(): String = "sat-${Random.nextLong()}"

    // Must be deterministic AND a valid wire mode: put() now rejects unknown modes,
    // so a garbage value here would silently make the contract's round-trip assertions fail.
    override fun newValue(key: String): TouchpadModePreference {
        val mode = TouchpadModeValue.ALL[(key.hashCode().mod(TouchpadModeValue.ALL.size))]
        return TouchpadModePreference(satelliteId = key, mode = mode)
    }
}
