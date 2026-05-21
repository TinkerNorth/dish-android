// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

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

/**
 * Inherits the standard CRUD contract from [RepositoryContract]. The contract
 * covers: get on empty, all on empty, get-after-put, replace-on-same-key, remove,
 * all-contains-every-put, clear, remove-absent-is-noop.
 *
 * **Per-implementation tests** (serialization edge cases, legacy migration, etc.)
 * still belong in a sibling test file — this contract test only pins the shape.
 *
 * The repository uses SharedPreferences; this test fixtures it with an in-memory
 * fake so no Android runtime is needed.
 */
class RememberedSatelliteRepositoryContractTest : AbstractRepositoryContract<String, RememberedSatellite>() {
    override fun newRepository(): Repository<String, RememberedSatellite> =
        RememberedSatelliteRepository(
            context = fakeContextBackedByMap(),
            json = Json { ignoreUnknownKeys = true },
        )

    override fun newKey(): String = "satellite:1.1.1.${Random.nextInt(2, 250)}:${Random.nextInt(1, 65000)}"

    /**
     * Deterministic for a given key — the contract test calls `newValue(k)` more than
     * once (e.g. `keys.map(::newValue)` to recompute expected values) and the equality
     * check would fail if the same key produced different `RememberedSatellite`s.
     * Derive ip/port from the key's hashCode so two calls with the same key always
     * return equal records.
     */
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

    /**
     * Build a Context whose only purpose is to return a SharedPreferences that
     * lives entirely in-memory. The MockK-backed prefs behave like the real
     * thing for the get/put/remove/all paths the repository actually exercises.
     */
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
        every { editor.apply() } answers { /* no-op */ }

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
