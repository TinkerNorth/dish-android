// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.repository.mapBackedPrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The donate pill's direct read of the supporter flag and the store agree on the file and the key. */
class SupporterPlanStoreTest {
    @Test
    fun `a fresh install is not a supporter`() {
        val (ctx, _) = mapBackedPrefs()

        assertFalse(storedSupporterActive(ctx))
    }

    @Test
    fun `the flag the store records is the one the startup read sees`() {
        val (ctx, _) = mapBackedPrefs()
        val store = SupporterPlanStore(ctx)

        store.supporterActive = true

        assertTrue(storedSupporterActive(ctx))
    }

    @Test
    fun `a lapsed supporter reads as not supporting`() {
        val (ctx, _) = mapBackedPrefs()
        val store = SupporterPlanStore(ctx)
        store.supporterActive = true

        store.supporterActive = false

        assertFalse(storedSupporterActive(ctx))
    }
}
