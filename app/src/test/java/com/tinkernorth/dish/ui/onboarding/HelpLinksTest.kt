// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class HelpLinksTest {
    @Test
    fun `an https address loses its scheme and trailing slash`() {
        assertEquals("tinkernorth.com/dish/privacy", hostLabel("https://tinkernorth.com/dish/privacy/"))
    }

    @Test
    fun `an http address loses its scheme too`() {
        assertEquals("github.com/TinkerNorth/dish-android", hostLabel("http://github.com/TinkerNorth/dish-android"))
    }

    @Test
    fun `a bare host is left alone`() {
        assertEquals("tinkernorth.com", hostLabel("tinkernorth.com"))
    }
}
