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
 * Inherits the standard CRUD contract from [AbstractRepositoryContract]:
 * get on empty, all on empty, get-after-put, replace-on-same-key, remove,
 * all-contains-every-put, clear, remove-absent-is-noop.
 *
 * Per-implementation behaviour (corrupt-JSON tolerance, multi-entry
 * persistence after a re-read) is in the sibling `MotionPreferenceRepositoryTest`.
 *
 * SharedPreferences is mocked into a `MutableMap` so this stays a pure
 * JVM test — same pattern as
 * [RememberedSatelliteRepositoryContractTest].
 */
class MotionPreferenceRepositoryContractTest : AbstractRepositoryContract<String, MotionPreference>() {
    override fun newRepository(): Repository<String, MotionPreference> =
        MotionPreferenceRepository(
            context = fakeContextBackedByMap(),
            json = Json { ignoreUnknownKeys = true },
        )

    override fun newKey(): String = "slot-${Random.nextLong()}"

    /** Same key ⇒ same value. The contract test compares sets of values. */
    override fun newValue(key: String): MotionPreference =
        MotionPreference(slotId = key, enabled = key.hashCode() and 1 == 0)

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
