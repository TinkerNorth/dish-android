// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyProfilingStoreTest {
    private fun storeBackedBy(persisted: Boolean): Pair<LatencyProfilingStore, SharedPreferences.Editor> {
        val editor: SharedPreferences.Editor = mockk(relaxed = true)
        val prefs: SharedPreferences =
            mockk {
                every { getBoolean(LatencyProfilingStore.KEY, LatencyProfilingStore.DEFAULT_ENABLED) } returns persisted
                every { edit() } returns editor
            }
        val context: Context = mockk { every { getSharedPreferences(any(), any()) } returns prefs }
        return LatencyProfilingStore(context) to editor
    }

    @Test
    fun `a fresh install starts off so the hot path stays measurement-free`() {
        val (store, _) = storeBackedBy(persisted = false)
        assertFalse(store.state.value)
    }

    @Test
    fun `a previously armed session re-arms from the persisted flag`() {
        val (store, _) = storeBackedBy(persisted = true)
        assertTrue(store.state.value)
    }

    @Test
    fun `setEnabled updates the state and persists the flag`() {
        val (store, editor) = storeBackedBy(persisted = false)
        store.setEnabled(true)
        assertTrue(store.state.value)
        verify { editor.putBoolean(LatencyProfilingStore.KEY, true) }

        store.setEnabled(false)
        assertFalse(store.state.value)
        verify { editor.putBoolean(LatencyProfilingStore.KEY, false) }
    }
}
