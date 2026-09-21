// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.update

import com.tinkernorth.dish.repository.mapBackedPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePreferenceStoreTest {
    @Test
    fun `a fresh install checks automatically and has skipped nothing`() {
        val (ctx, _) = mapBackedPrefs()
        val store = UpdatePreferenceStore(ctx)
        assertTrue(store.state.value.checksEnabled)
        assertEquals("", store.state.value.skippedVersion)
        assertEquals(0L, store.lastCheckMs())
    }

    @Test
    fun `the switch and the skip are persisted and published`() {
        val (ctx, backing) = mapBackedPrefs()
        val store = UpdatePreferenceStore(ctx)
        store.setChecksEnabled(false)
        store.setSkippedVersion("2.1.0")
        assertFalse(store.state.value.checksEnabled)
        assertEquals("2.1.0", store.state.value.skippedVersion)
        assertEquals(false, backing[UpdatePreferenceStore.KEY_CHECKS_ENABLED])
        assertEquals("2.1.0", backing[UpdatePreferenceStore.KEY_SKIPPED_VERSION])

        val reopened = UpdatePreferenceStore(ctx)
        assertFalse(reopened.state.value.checksEnabled)
        assertEquals("2.1.0", reopened.state.value.skippedVersion)
    }

    @Test
    fun `the last check time round-trips`() {
        val (ctx, backing) = mapBackedPrefs()
        val store = UpdatePreferenceStore(ctx)
        store.recordLastCheck(1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, store.lastCheckMs())
        assertEquals(1_700_000_000_000L, backing[UpdatePreferenceStore.KEY_LAST_CHECK_UTC_MS])
    }
}
