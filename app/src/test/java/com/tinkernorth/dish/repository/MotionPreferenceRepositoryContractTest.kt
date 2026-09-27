// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.architecture.interfaces.KeyedRepository
import com.tinkernorth.dish.architecture.testing.AbstractKeyedRepositoryContract
import kotlinx.serialization.json.Json
import kotlin.random.Random

class MotionPreferenceRepositoryContractTest : AbstractKeyedRepositoryContract<String, MotionPreference>() {
    override fun newKeyedRepository(): KeyedRepository<String, MotionPreference> =
        MotionPreferenceRepository(
            context = mapBackedPrefs().first,
            json = Json { ignoreUnknownKeys = true },
        )

    override fun newKey(): String = "slot-${Random.nextLong()}"

    // Contract compares value sets; same key must yield equal value.
    override fun newValue(key: String): MotionPreference = MotionPreference(slotId = key, enabled = key.hashCode() and 1 == 0)
}
