// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import androidx.appcompat.app.AppCompatDelegate
import com.tinkernorth.dish.repository.mapBackedPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePreferenceStoreTest {
    @Test
    fun `every theme mode round trips through storage`() {
        for (mode in ThemeMode.entries) {
            assertEquals(mode, themeModeFromStorage(mode.toStorageValue()))
        }
    }

    @Test
    fun `nothing stored follows the system`() {
        assertEquals(ThemeMode.SYSTEM, themeModeFromStorage(null))
    }

    @Test
    fun `an unknown storage value falls back to system`() {
        assertEquals(ThemeMode.SYSTEM, themeModeFromStorage("sepia"))
    }

    @Test
    fun `the storage values are the schema every backup carries`() {
        assertEquals("system", ThemeMode.SYSTEM.toStorageValue())
        assertEquals("light", ThemeMode.LIGHT.toStorageValue())
        assertEquals("dark", ThemeMode.DARK.toStorageValue())
    }

    @Test
    fun `the store hydrates from prefs on construction`() {
        val (ctx, _) = mapBackedPrefs(mutableMapOf(ThemePreferenceStore.KEY_THEME_MODE to "dark"))

        assertEquals(ThemeMode.DARK, ThemePreferenceStore(ctx).state.value)
    }

    @Test
    fun `an unwritten preference reads as system`() {
        val (ctx, _) = mapBackedPrefs()

        assertEquals(ThemeMode.SYSTEM, ThemePreferenceStore(ctx).state.value)
    }

    @Test
    fun `setMode persists, republishes and applies the night mode`() {
        val (ctx, backing) = mapBackedPrefs()
        val store = ThemePreferenceStore(ctx)

        store.setMode(ThemeMode.LIGHT)

        assertEquals("light", backing[ThemePreferenceStore.KEY_THEME_MODE])
        assertEquals(ThemeMode.LIGHT, store.state.value)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode())
    }

    @Test
    fun `applyPersistedMode applies what was read without writing`() {
        val (ctx, backing) = mapBackedPrefs(mutableMapOf(ThemePreferenceStore.KEY_THEME_MODE to "dark"))
        val store = ThemePreferenceStore(ctx)

        store.applyPersistedMode()

        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())
        assertEquals(1, backing.size)
    }
}
