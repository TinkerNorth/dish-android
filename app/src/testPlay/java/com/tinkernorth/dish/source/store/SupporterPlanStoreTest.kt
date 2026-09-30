// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.repository.mapBackedPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The donate pill's direct read of the supporter flag and the store agree on the file and the key,
 * and both are the backup schema's own spelling: a renamed file or key would orphan every supporter.
 */
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
    fun `the flag lives under the backed-up key in the user preferences file`() {
        val (ctx, backing) = mapBackedPrefs()

        SupporterPlanStore(ctx).supporterActive = true

        assertEquals(true, backing["supporter_active"])
        verifyOnlyPrefsFile(ctx, USER_PREFERENCES_FILE)
    }

    @Test
    fun `the startup read looks under the backed-up key`() {
        val (ctx, _) = mapBackedPrefs(mutableMapOf("supporter_active" to true))

        assertTrue(storedSupporterActive(ctx))
        verifyOnlyPrefsFile(ctx, USER_PREFERENCES_FILE)
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
