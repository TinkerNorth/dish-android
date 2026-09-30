// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import androidx.appcompat.app.AppCompatDelegate
import com.tinkernorth.dish.repository.mapBackedPrefs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ThemePreferenceStoreTest {
    // The night mode is process-wide and cannot be put back: setDefaultNightMode refuses the
    // unspecified default. So no test here sets it for real, and each one checks that none has.
    @Before
    fun standInForTheNightMode() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_UNSPECIFIED, AppCompatDelegate.getDefaultNightMode())
        mockkStatic(AppCompatDelegate::class)
        every { AppCompatDelegate.setDefaultNightMode(any()) } just runs
    }

    @After
    fun releaseTheNightMode() {
        unmockkStatic(AppCompatDelegate::class)
    }

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
        val (ctx, _) = mapBackedPrefs(mutableMapOf(PERSISTED_KEY to "dark"))

        assertEquals(ThemeMode.DARK, ThemePreferenceStore(ctx).state.value)
    }

    @Test
    fun `the mode lives in the cloud-backed user preferences file`() {
        val (ctx, _) = mapBackedPrefs()

        ThemePreferenceStore(ctx).setMode(ThemeMode.DARK)

        verifyOnlyPrefsFile(ctx, USER_PREFERENCES_FILE)
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

        assertEquals("light", backing[PERSISTED_KEY])
        assertEquals(ThemeMode.LIGHT, store.state.value)
        verify(exactly = 1) { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO) }
    }

    @Test
    fun `applyPersistedMode applies what was read without writing`() {
        val (ctx, backing) = mapBackedPrefs(mutableMapOf(PERSISTED_KEY to "dark"))
        val store = ThemePreferenceStore(ctx)

        store.applyPersistedMode()

        verify(exactly = 1) { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) }
        assertEquals(1, backing.size)
    }

    private companion object {
        const val PERSISTED_KEY = "theme_mode"
    }
}
