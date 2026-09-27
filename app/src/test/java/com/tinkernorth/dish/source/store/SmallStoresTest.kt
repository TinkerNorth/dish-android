// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.core.net.moonlight.ServerInfo
import com.tinkernorth.dish.repository.mapBackedPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The persist-and-republish stores too small for a file each, plus the two in-memory note stores.
class SmallStoresTest {
    @Test
    fun `crash reporting defaults on and hydrates from prefs`() {
        assertTrue(CrashReportingStore(mapBackedPrefs().first).state.value)
        val (ctx, _) = mapBackedPrefs(mutableMapOf(CrashReportingStore.KEY_COLLECTION_ENABLED to false))
        assertFalse(CrashReportingStore(ctx).state.value)
    }

    @Test
    fun `crash reporting setEnabled persists and republishes`() {
        val (ctx, backing) = mapBackedPrefs()
        val store = CrashReportingStore(ctx)

        store.setEnabled(false)

        assertEquals(false, backing[CrashReportingStore.KEY_COLLECTION_ENABLED])
        assertFalse(store.state.value)
    }

    @Test
    fun `onboarding starts incomplete and hydrates from prefs`() {
        assertFalse(OnboardingPreferenceStore(mapBackedPrefs().first).state.value.welcomeCompleted)
        val (ctx, _) = mapBackedPrefs(mutableMapOf(OnboardingPreferenceStore.KEY_WELCOME_COMPLETED to true))
        assertTrue(OnboardingPreferenceStore(ctx).state.value.welcomeCompleted)
    }

    @Test
    fun `markWelcomeCompleted persists and republishes true`() {
        val (ctx, backing) = mapBackedPrefs()
        val store = OnboardingPreferenceStore(ctx)

        store.markWelcomeCompleted()

        assertEquals(true, backing[OnboardingPreferenceStore.KEY_WELCOME_COMPLETED])
        assertTrue(store.state.value.welcomeCompleted)
    }

    @Test
    fun `resetWelcome persists and republishes false`() {
        val (ctx, backing) = mapBackedPrefs(mutableMapOf(OnboardingPreferenceStore.KEY_WELCOME_COMPLETED to true))
        val store = OnboardingPreferenceStore(ctx)

        store.resetWelcome()

        assertEquals(false, backing[OnboardingPreferenceStore.KEY_WELCOME_COMPLETED])
        assertFalse(store.state.value.welcomeCompleted)
    }

    @Test
    fun `the banner store hydrates a dismissal and persists a new one`() {
        assertFalse(BluetoothPermissionBannerStore(mapBackedPrefs().first).state.value)
        val (ctx, backing) = mapBackedPrefs()
        val store = BluetoothPermissionBannerStore(ctx)

        store.markDismissed()

        assertEquals(true, backing[BluetoothPermissionBannerStore.KEY_BANNER_DISMISSED])
        assertTrue(store.state.value)
        assertTrue(BluetoothPermissionBannerStore(ctx).state.value)
    }

    private fun serverInfo(
        hostname: String,
        currentGame: Int,
    ) = ServerInfo(
        hostname = hostname,
        uniqueId = "u",
        pairStatus = 1,
        currentGame = currentGame,
        state = "SUNSHINE_SERVER_FREE",
        httpsPort = 47984,
        externalPort = null,
        mac = null,
        localIp = null,
        appVersion = "0.23",
        gfeVersion = null,
    )

    @Test
    fun `host facts are null before a probe and hold the latest note after`() {
        val store = MoonlightHostFactsStore()
        assertNull(store.factsFor("h"))

        store.note("h", serverInfo("PC", currentGame = 0), nowMs = 5L)
        store.note("h", serverInfo("PC", currentGame = 7), nowMs = 9L)

        val facts = store.factsFor("h")
        assertEquals("PC", facts?.hostname)
        assertEquals(7, facts?.currentGame)
        assertEquals(9L, facts?.probedAtMs)
        assertEquals("0.23", facts?.appVersion)
        assertNull(store.factsFor("other"))
    }

    @Test
    fun `feedback activity counts per slot and keeps the latest kind`() {
        val store = FeedbackActivityStore()

        store.note("slot-1", FeedbackKind.RUMBLE, nowMs = 1L)
        store.note("slot-1", FeedbackKind.LIGHTBAR, nowMs = 2L)
        store.note("slot-2", FeedbackKind.MIC_LED, nowMs = 3L)

        assertEquals(FeedbackActivity(FeedbackKind.LIGHTBAR, atMs = 2L, count = 2L), store.snapshot()["slot-1"])
        assertEquals(FeedbackActivity(FeedbackKind.MIC_LED, atMs = 3L, count = 1L), store.snapshot()["slot-2"])
    }

    @Test
    fun `feedback activity with an empty slot id records nothing`() {
        val store = FeedbackActivityStore()

        store.note("", FeedbackKind.RUMBLE, nowMs = 1L)

        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun `forget drops one slot's feedback activity`() {
        val store = FeedbackActivityStore()
        store.note("slot-1", FeedbackKind.RUMBLE, nowMs = 1L)
        store.note("slot-2", FeedbackKind.RUMBLE, nowMs = 1L)

        store.forget("slot-1")

        assertEquals(setOf("slot-2"), store.snapshot().keys)
    }

    @Test
    fun `a snapshot is a copy that later notes do not change`() {
        val store = FeedbackActivityStore()
        store.note("slot-1", FeedbackKind.RUMBLE, nowMs = 1L)
        val snapshot = store.snapshot()

        store.note("slot-1", FeedbackKind.RUMBLE, nowMs = 2L)

        assertEquals(1L, snapshot["slot-1"]?.count)
    }
}
