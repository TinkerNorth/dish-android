// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.settings

import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.store.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsMappersTest {
    @Test
    fun `every theme chip round trips through its mode`() {
        for (mode in ThemeMode.entries) {
            assertEquals(mode.name, mode, themeModeForChip(chipForThemeMode(mode)))
        }
    }

    @Test
    fun `each chip names its own mode`() {
        assertEquals(ThemeMode.LIGHT, themeModeForChip(R.id.chipThemeLight))
        assertEquals(ThemeMode.DARK, themeModeForChip(R.id.chipThemeDark))
        assertEquals(ThemeMode.SYSTEM, themeModeForChip(R.id.chipThemeSystem))
    }

    @Test
    fun `an empty selection or a foreign id is no mode`() {
        assertNull(themeModeForChip(null))
        assertNull(themeModeForChip(R.id.chipGroupTheme))
    }

    @Test
    fun `the privacy host drops the scheme and the trailing slash`() {
        assertEquals("tinkernorth.com/privacy", hostOf("https://tinkernorth.com/privacy/"))
        assertEquals("tinkernorth.com", hostOf("http://tinkernorth.com"))
    }

    @Test
    fun `a bare host is left alone`() {
        assertEquals("tinkernorth.com", hostOf("tinkernorth.com"))
    }
}
