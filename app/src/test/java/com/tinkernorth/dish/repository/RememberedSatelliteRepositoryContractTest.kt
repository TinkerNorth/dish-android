// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import android.content.Context
import android.content.SharedPreferences
import com.tinkernorth.dish.architecture.interfaces.Repository
import com.tinkernorth.dish.architecture.testing.AbstractRepositoryContract
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.Json
import kotlin.random.Random

class RememberedSatelliteRepositoryContractTest : AbstractRepositoryContract<String, RememberedSatellite>() {
    override fun newRepository(): Repository<String, RememberedSatellite> =
        RememberedSatelliteRepository(
            context = fakeContextBackedByMap(),
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

    private fun fakeContextBackedByMap(): Context {
        val store = mutableMapOf<String, Any?>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val keySlot = slot<String>()
        val strSlot = slot<String?>()
        every { editor.putString(capture(keySlot), captureNullable(strSlot)) } answers {
            store[keySlot.captured] = strSlot.captured
            editor
        }
        every { editor.remove(capture(keySlot)) } answers {
            store.remove(keySlot.captured)
            editor
        }
        every { editor.apply() } answers { }

        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } answers {
            val k = firstArg<String>()
            val default = secondArg<String?>()
            (store[k] as? String) ?: default
        }
        every { prefs.edit() } returns editor
        every { prefs.all } answers { store.toMap() }

        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return context
    }
}
