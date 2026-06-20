// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

// Map-backed SharedPreferences double shared by the connection_store repository tests.
// One backing map per Context models the single connection_store prefs file that
// RememberedSatelliteRepository, RememberedBtRepository and SatelliteSharedKeyRepository co-tenant.
internal fun mapBackedPrefs(seed: MutableMap<String, Any?>? = null): Pair<Context, MutableMap<String, Any?>> {
    val store: MutableMap<String, Any?> = seed ?: mutableMapOf()

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
    val boolSlot = slot<Boolean>()
    every { editor.putBoolean(capture(keySlot), capture(boolSlot)) } answers {
        store[keySlot.captured] = boolSlot.captured
        editor
    }
    every { editor.apply() } answers { }

    val prefs = mockk<SharedPreferences>(relaxed = true)
    every { prefs.getString(any(), any()) } answers {
        val k = firstArg<String>()
        val default = secondArg<String?>()
        (store[k] as? String) ?: default
    }
    every { prefs.getBoolean(any(), any()) } answers {
        val k = firstArg<String>()
        val default = secondArg<Boolean>()
        (store[k] as? Boolean) ?: default
    }
    every { prefs.edit() } returns editor
    every { prefs.all } answers { store.toMap() }

    val ctx = mockk<Context>(relaxed = true)
    every { ctx.getSharedPreferences(any(), any()) } returns prefs
    return ctx to store
}
