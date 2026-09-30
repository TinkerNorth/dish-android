// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.update

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.core.update.ASSET_URL_PREFIX
import com.tinkernorth.dish.core.update.BACKOFF_BASE_MS
import com.tinkernorth.dish.core.update.FUTURE_SKEW_ESCAPE_MS
import com.tinkernorth.dish.core.update.MANUAL_MIN_GAP_MS
import com.tinkernorth.dish.core.update.PERIODIC_INTERVAL_MS
import com.tinkernorth.dish.core.update.RECONNECT_CHECK_DELAY_MS
import com.tinkernorth.dish.core.update.STARTUP_DELAY_MS
import com.tinkernorth.dish.core.update.UpdateAsset
import com.tinkernorth.dish.core.update.UpdateError
import com.tinkernorth.dish.core.update.UpdateManifest
import com.tinkernorth.dish.core.update.UpdatePhase
import com.tinkernorth.dish.core.update.jitteredDelayMs
import com.tinkernorth.dish.repository.mapBackedPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The reducer is pinned in UpdateMachineTest; these pin what the coordinator
// adds around it: the timers, the min-gap rule at start, the manual rate
// limit, the jittered retry, persistence of a skip, and that nothing runs
// while the app is off screen. Virtual time throughout; no network.
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCoordinatorTest {
    private class FakeGateway : UpdateManifestGateway {
        val replies = ArrayDeque<ManifestFetch>()
        var fetches = 0

        override suspend fun fetchLatest(): ManifestFetch {
            fetches++
            return replies.removeFirstOrNull() ?: ManifestFetch.Failed(UpdateError.Http)
        }
    }

    // The coordinator takes an owner but never reads its lifecycle; the error says so rather
    // than handing over a registry that would quietly make a missed read pass.
    private class UnusedOwner : LifecycleOwner {
        override val lifecycle: Lifecycle get() = error("not used by the coordinator")
    }

    private val owner = UnusedOwner()

    private val notes = "https://github.com/TinkerNorth/dish-android/releases/tag/2.1.0"

    private fun manifest(
        version: String = "2.1.0",
        minimum: String = "0.1.0",
    ) = ManifestFetch.Ok(
        UpdateManifest(
            version = version,
            minimumSupportedVersion = minimum,
            publishedAt = "2026-09-21T00:00:00Z",
            releaseNotesUrl = notes,
            apkAsset = UpdateAsset(ASSET_URL_PREFIX + "$version/dish.apk", "a".repeat(64), 1L),
        ),
    )

    private class Rig(
        val store: UpdatePreferenceStore,
        val gateway: FakeGateway,
        val online: MutableStateFlow<Boolean>,
        val coordinator: UpdateCoordinator,
    )

    private fun TestScope.rig(
        currentVersion: String = "2.0.0",
        lastCheckMs: Long? = null,
        checksEnabled: Boolean = true,
        online: Boolean = true,
        jitter: Double = 0.5,
    ): Rig {
        val (ctx, _) = mapBackedPrefs()
        val store = UpdatePreferenceStore(ctx)
        if (!checksEnabled) store.setChecksEnabled(false)
        if (lastCheckMs != null) store.recordLastCheck(lastCheckMs)
        val gateway = FakeGateway()
        val onlineFlow = MutableStateFlow(online)
        val coordinator =
            UpdateCoordinator(
                store = store,
                gateway = gateway,
                online = onlineFlow,
                scope = backgroundScope,
                currentVersion = currentVersion,
                nowMs = { EPOCH_MS + testScheduler.currentTime },
                jitterUnit = { jitter },
            )
        return Rig(store, gateway, onlineFlow, coordinator)
    }

    @Test
    fun `the first start checks after the startup delay and remembers when`() =
        runTest {
            val rig = rig()
            rig.gateway.replies.add(manifest())
            rig.coordinator.onStart(owner)
            advanceTimeBy(STARTUP_DELAY_MS - 1)
            runCurrent()
            assertEquals(0, rig.gateway.fetches)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, rig.gateway.fetches)
            assertEquals(UpdatePhase.Available, rig.coordinator.machineStatus.value.phase)
            assertEquals(UpdateNoticePhase.Available, rig.coordinator.status.value.phase)
            assertEquals("2.1.0", rig.coordinator.status.value.availableVersion)
            assertEquals(notes, rig.coordinator.status.value.downloadUrl)
            assertEquals(EPOCH_MS + STARTUP_DELAY_MS, rig.store.lastCheckMs())
        }

    @Test
    fun `a start within the gap waits for the periodic tick`() =
        runTest {
            val rig = rig(lastCheckMs = EPOCH_MS - 10 * 60 * 1000)
            rig.coordinator.onStart(owner)
            advanceTimeBy(STARTUP_DELAY_MS + 1)
            runCurrent()
            assertEquals(0, rig.gateway.fetches)
            advanceTimeBy(PERIODIC_INTERVAL_MS)
            runCurrent()
            assertEquals(1, rig.gateway.fetches)
        }

    @Test
    fun `a last-check time from the future is a moved clock, not a recent check`() =
        runTest {
            val rig = rig(lastCheckMs = EPOCH_MS + 2 * FUTURE_SKEW_ESCAPE_MS)
            rig.coordinator.onStart(owner)
            advanceTimeBy(STARTUP_DELAY_MS + 1)
            runCurrent()
            assertEquals(1, rig.gateway.fetches)
        }

    @Test
    fun `checks off means no request, and back on is a cold start`() =
        runTest {
            val rig = rig(checksEnabled = false)
            assertEquals(UpdateNoticePhase.Disabled, rig.coordinator.status.value.phase)
            rig.coordinator.onStart(owner)
            advanceTimeBy(24L * 60 * 60 * 1000)
            runCurrent()
            assertEquals(0, rig.gateway.fetches)
            rig.coordinator.checkNow()
            runCurrent()
            assertEquals(0, rig.gateway.fetches)

            rig.coordinator.setChecksEnabled(true)
            runCurrent()
            assertEquals(UpdatePhase.Idle, rig.coordinator.machineStatus.value.phase)
            advanceTimeBy(STARTUP_DELAY_MS + 1)
            runCurrent()
            assertEquals(1, rig.gateway.fetches)
        }

    @Test
    fun `the Settings button checks at once and is rate limited`() =
        runTest {
            val rig = rig()
            rig.coordinator.onStart(owner)
            rig.coordinator.checkNow()
            runCurrent()
            assertEquals(1, rig.gateway.fetches)
            advanceTimeBy(MANUAL_MIN_GAP_MS / 2)
            rig.coordinator.checkNow()
            runCurrent()
            assertEquals(1, rig.gateway.fetches)
            advanceTimeBy(MANUAL_MIN_GAP_MS / 2 + 1)
            rig.coordinator.checkNow()
            runCurrent()
            assertEquals(2, rig.gateway.fetches)
        }

    @Test
    fun `a skip mutes the offered version, persists, and outlives the next check`() =
        runTest {
            val rig = rig()
            rig.gateway.replies.add(manifest())
            rig.gateway.replies.add(manifest())
            rig.coordinator.onStart(owner)
            advanceTimeBy(STARTUP_DELAY_MS + 1)
            runCurrent()
            assertEquals(UpdateNoticePhase.Available, rig.coordinator.status.value.phase)

            rig.coordinator.skipAvailableVersion()
            runCurrent()
            assertEquals(UpdateNoticePhase.UpToDate, rig.coordinator.status.value.phase)
            assertEquals("2.1.0", rig.store.state.value.skippedVersion)

            advanceTimeBy(PERIODIC_INTERVAL_MS + 1)
            runCurrent()
            assertEquals(2, rig.gateway.fetches)
            assertEquals(UpdateNoticePhase.UpToDate, rig.coordinator.status.value.phase)
        }

    @Test
    fun `a required update cannot be skipped and nothing is persisted`() =
        runTest {
            val rig = rig(currentVersion = "1.0.0")
            rig.gateway.replies.add(manifest(minimum = "2.0.0"))
            rig.coordinator.onStart(owner)
            advanceTimeBy(STARTUP_DELAY_MS + 1)
            runCurrent()
            assertTrue(rig.coordinator.status.value.required)

            rig.coordinator.skipAvailableVersion()
            runCurrent()
            assertEquals(UpdateNoticePhase.Available, rig.coordinator.status.value.phase)
            assertEquals("", rig.store.state.value.skippedVersion)
        }

    @Test
    fun `a failed check retries on the jittered ladder`() =
        runTest {
            val rig = rig(jitter = 1.0)
            rig.coordinator.onStart(owner)
            // Land exactly on the startup tick: the retry timer is armed at
            // that instant, so the ladder below is measured from it.
            advanceTimeBy(STARTUP_DELAY_MS)
            runCurrent()
            assertEquals(1, rig.gateway.fetches)
            assertEquals(UpdateNoticePhase.Failed, rig.coordinator.status.value.phase)

            val firstRetry = jitteredDelayMs(BACKOFF_BASE_MS, 1.0)
            advanceTimeBy(firstRetry - 1)
            runCurrent()
            assertEquals(1, rig.gateway.fetches)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, rig.gateway.fetches)

            val secondRetry = jitteredDelayMs(2 * BACKOFF_BASE_MS, 1.0)
            advanceTimeBy(secondRetry - 1)
            runCurrent()
            assertEquals(2, rig.gateway.fetches)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(3, rig.gateway.fetches)
        }

    @Test
    fun `off screen means no timer, and coming back re-arms it`() =
        runTest {
            val rig = rig()
            rig.coordinator.onStart(owner)
            advanceTimeBy(STARTUP_DELAY_MS / 2)
            rig.coordinator.onStop(owner)
            advanceTimeBy(60L * 60 * 1000)
            runCurrent()
            assertEquals(0, rig.gateway.fetches)

            rig.coordinator.onStart(owner)
            advanceTimeBy(STARTUP_DELAY_MS + 1)
            runCurrent()
            assertEquals(1, rig.gateway.fetches)
        }

    @Test
    fun `offline gates the check without a request and the network's return re-arms it`() =
        runTest {
            val rig = rig(online = false)
            rig.coordinator.onStart(owner)
            advanceTimeBy(STARTUP_DELAY_MS + 1)
            runCurrent()
            assertEquals(0, rig.gateway.fetches)
            assertEquals(UpdatePhase.Failed, rig.coordinator.machineStatus.value.phase)
            assertEquals(UpdateError.Offline, rig.coordinator.machineStatus.value.error)

            rig.online.value = true
            runCurrent()
            advanceTimeBy(RECONNECT_CHECK_DELAY_MS + 1)
            runCurrent()
            assertEquals(1, rig.gateway.fetches)
            assertFalse(rig.coordinator.machineStatus.value.phase == UpdatePhase.Checking)
        }

    private companion object {
        const val EPOCH_MS = 1_700_000_000_000L
    }
}
