// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.update

import com.tinkernorth.dish.core.update.UpdateMachine.BACKOFF_BASE_MS
import com.tinkernorth.dish.core.update.UpdateMachine.BACKOFF_CAP_MS
import com.tinkernorth.dish.core.update.UpdateMachine.PERIODIC_INTERVAL_MS
import com.tinkernorth.dish.core.update.UpdateMachine.RECONNECT_CHECK_DELAY_MS
import com.tinkernorth.dish.core.update.UpdateMachine.STARTUP_DELAY_MS
import com.tinkernorth.dish.core.update.UpdateMachine.reduce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateMachineTest {
    private val notes = "https://github.com/TinkerNorth/dish-android/releases/tag/2.1.0"

    private fun status(
        phase: UpdatePhase = UpdatePhase.Idle,
        current: String = "2.0.0",
        available: String = "",
        skipped: String = "",
        required: Boolean = false,
        online: Boolean = true,
        failures: Int = 0,
        error: UpdateError = UpdateError.None,
        checksEnabled: Boolean = true,
    ) = UpdateStatus(
        currentVersion = current,
        phase = phase,
        availableVersion = available,
        downloadUrl = if (available.isEmpty()) "" else notes,
        skippedVersion = skipped,
        required = required,
        online = online,
        consecutiveFailures = failures,
        error = error,
        checksEnabled = checksEnabled,
    )

    private fun manifest(
        version: String = "2.1.0",
        minimum: String = "0.1.0",
    ) = UpdateManifest(
        version = version,
        minimumSupportedVersion = minimum,
        publishedAt = "2026-09-21T00:00:00Z",
        releaseNotesUrl = notes,
        apkAsset = UpdateAsset(ASSET_URL_PREFIX + "$version/dish.apk", "a".repeat(64), 1L),
    )

    private val check = UpdateEvent.CheckRequested(UpdateTrigger.Periodic)
    private val everyPhase = UpdatePhase.entries

    // ── Constants and ladders ───────────────────────────────────────────────

    @Test
    fun `the schedule constants are the documented ones`() {
        assertEquals(15_000L, STARTUP_DELAY_MS)
        assertEquals(60L * 60 * 1000, UpdateMachine.MIN_CHECK_GAP_MS)
        assertEquals(24L * 60 * 60 * 1000, UpdateMachine.FUTURE_SKEW_ESCAPE_MS)
        assertEquals(4L * 60 * 60 * 1000, PERIODIC_INTERVAL_MS)
        assertEquals(10L * 60 * 1000, BACKOFF_BASE_MS)
        assertEquals(6L * 60 * 60 * 1000, BACKOFF_CAP_MS)
        assertEquals(10_000L, UpdateMachine.MANUAL_MIN_GAP_MS)
        assertEquals(30_000L, RECONNECT_CHECK_DELAY_MS)
    }

    @Test
    fun `the backoff ladder doubles to a six-hour cap`() {
        assertEquals(BACKOFF_BASE_MS, UpdateMachine.backoffDelayMs(0))
        assertEquals(BACKOFF_BASE_MS, UpdateMachine.backoffDelayMs(1))
        assertEquals(20L * 60 * 1000, UpdateMachine.backoffDelayMs(2))
        assertEquals(40L * 60 * 1000, UpdateMachine.backoffDelayMs(3))
        assertEquals(320L * 60 * 1000, UpdateMachine.backoffDelayMs(6))
        assertEquals(BACKOFF_CAP_MS, UpdateMachine.backoffDelayMs(7))
        assertEquals(BACKOFF_CAP_MS, UpdateMachine.backoffDelayMs(40))
    }

    @Test
    fun `jitter stays inside plus or minus 20 percent`() {
        assertEquals(800L, UpdateMachine.jitteredDelayMs(1000L, 0.0))
        assertEquals(1000L, UpdateMachine.jitteredDelayMs(1000L, 0.5))
        assertEquals(1200L, UpdateMachine.jitteredDelayMs(1000L, 1.0))
        assertEquals(800L, UpdateMachine.jitteredDelayMs(1000L, -3.0))
        assertEquals(1200L, UpdateMachine.jitteredDelayMs(1000L, 7.0))
    }

    // ── Preferences ─────────────────────────────────────────────────────────

    @Test
    fun `checks off means off from every phase`() {
        for (phase in everyPhase) {
            val r = reduce(status(phase = phase, available = "2.1.0", error = UpdateError.Http), UpdateEvent.PrefsChanged(false, ""))
            assertEquals(phase.name, UpdatePhase.Disabled, r.next.phase)
            assertEquals(phase.name, UpdateError.None, r.next.error)
            assertFalse(phase.name, r.next.checksEnabled)
            assertTrue(phase.name, r.effects.isEmpty())
        }
    }

    @Test
    fun `re-enabling checks behaves like a cold start`() {
        val r = reduce(status(phase = UpdatePhase.Disabled, checksEnabled = false), UpdateEvent.PrefsChanged(true, ""))
        assertEquals(UpdatePhase.Idle, r.next.phase)
        assertTrue(r.next.checksEnabled)
        assertEquals(listOf(UpdateEffect.ScheduleNextCheck(STARTUP_DELAY_MS)), r.effects)
    }

    @Test
    fun `prefs while enabled only record the skip`() {
        val r = reduce(status(phase = UpdatePhase.Available, available = "2.1.0"), UpdateEvent.PrefsChanged(true, "1.0.0"))
        assertEquals(UpdatePhase.Available, r.next.phase)
        assertEquals("1.0.0", r.next.skippedVersion)
        assertTrue(r.effects.isEmpty())
    }

    // ── Check lifecycle ─────────────────────────────────────────────────────

    @Test
    fun `a check fetches, and a manual one says so`() {
        for (phase in listOf(UpdatePhase.Idle, UpdatePhase.UpToDate, UpdatePhase.Available, UpdatePhase.Failed)) {
            val r = reduce(status(phase = phase), check)
            assertEquals(phase.name, UpdatePhase.Checking, r.next.phase)
            assertEquals(phase.name, listOf(UpdateEffect.FetchManifest(manual = false)), r.effects)
        }
        val manual = reduce(status(), UpdateEvent.CheckRequested(UpdateTrigger.Manual))
        assertEquals(listOf(UpdateEffect.FetchManifest(manual = true)), manual.effects)
    }

    @Test
    fun `a check while disabled or already checking does nothing`() {
        for (phase in listOf(UpdatePhase.Disabled, UpdatePhase.Checking)) {
            val s = status(phase = phase)
            val r = reduce(s, check)
            assertEquals(s, r.next)
            assertTrue(r.effects.isEmpty())
        }
    }

    @Test
    fun `the offline gate answers without touching the network`() {
        val r = reduce(status(online = false), check)
        assertEquals(UpdatePhase.Failed, r.next.phase)
        assertEquals(UpdateError.Offline, r.next.error)
        assertEquals(1, r.next.consecutiveFailures)
        assertEquals(listOf(UpdateEffect.ScheduleNextCheck(BACKOFF_BASE_MS)), r.effects)
    }

    @Test
    fun `a newer manifest announces the release`() {
        val r =
            reduce(status(phase = UpdatePhase.Checking, failures = 3, error = UpdateError.Http), UpdateEvent.ManifestArrived(manifest()))
        assertEquals(UpdatePhase.Available, r.next.phase)
        assertEquals("2.1.0", r.next.availableVersion)
        assertEquals(notes, r.next.downloadUrl)
        assertEquals("0.1.0", r.next.minimumSupportedVersion)
        assertFalse(r.next.required)
        assertEquals(0, r.next.consecutiveFailures)
        assertEquals(UpdateError.None, r.next.error)
        assertEquals(listOf(UpdateEffect.PersistLastCheck, UpdateEffect.ScheduleNextCheck(PERIODIC_INTERVAL_MS)), r.effects)
    }

    @Test
    fun `a manifest at or below the running version is UpToDate`() {
        for (version in listOf("2.0.0", "1.9.9")) {
            val r = reduce(status(phase = UpdatePhase.Checking), UpdateEvent.ManifestArrived(manifest(version = version)))
            assertEquals(version, UpdatePhase.UpToDate, r.next.phase)
            assertEquals(version, "", r.next.availableVersion)
            assertEquals(version, "", r.next.downloadUrl)
            assertEquals(version, listOf(UpdateEffect.PersistLastCheck, UpdateEffect.ScheduleNextCheck(PERIODIC_INTERVAL_MS)), r.effects)
        }
    }

    @Test
    fun `a build below the supported minimum is required`() {
        val r = reduce(status(phase = UpdatePhase.Checking, current = "1.0.0"), UpdateEvent.ManifestArrived(manifest(minimum = "2.0.0")))
        assertEquals(UpdatePhase.Available, r.next.phase)
        assertTrue(r.next.required)
        val fine = reduce(status(phase = UpdatePhase.Checking, current = "2.0.0"), UpdateEvent.ManifestArrived(manifest(minimum = "2.0.0")))
        assertFalse(fine.next.required)
    }

    @Test
    fun `a skipped version is muted unless it is required`() {
        val muted = reduce(status(phase = UpdatePhase.Checking, skipped = "2.1.0"), UpdateEvent.ManifestArrived(manifest()))
        assertEquals(UpdatePhase.UpToDate, muted.next.phase)
        val newer =
            reduce(status(phase = UpdatePhase.Checking, skipped = "2.1.0"), UpdateEvent.ManifestArrived(manifest(version = "2.2.0")))
        assertEquals(UpdatePhase.Available, newer.next.phase)
        assertEquals("2.2.0", newer.next.availableVersion)
        val required =
            reduce(
                status(phase = UpdatePhase.Checking, current = "1.0.0", skipped = "2.1.0"),
                UpdateEvent.ManifestArrived(manifest(minimum = "2.0.0")),
            )
        assertEquals(UpdatePhase.Available, required.next.phase)
        assertTrue(required.next.required)
    }

    @Test
    fun `a manifest that arrives outside a check is ignored`() {
        for (phase in everyPhase.filter { it != UpdatePhase.Checking }) {
            val s = status(phase = phase)
            val r = reduce(s, UpdateEvent.ManifestArrived(manifest()))
            assertEquals(phase.name, s, r.next)
            assertTrue(phase.name, r.effects.isEmpty())
        }
    }

    @Test
    fun `a failed check backs off along the ladder`() {
        val first = reduce(status(phase = UpdatePhase.Checking), UpdateEvent.CheckFailed(UpdateError.Http))
        assertEquals(UpdatePhase.Failed, first.next.phase)
        assertEquals(UpdateError.Http, first.next.error)
        assertEquals(1, first.next.consecutiveFailures)
        assertEquals(listOf(UpdateEffect.ScheduleNextCheck(BACKOFF_BASE_MS)), first.effects)
        val second = reduce(first.next.copy(phase = UpdatePhase.Checking), UpdateEvent.CheckFailed(UpdateError.ManifestInvalid))
        assertEquals(2, second.next.consecutiveFailures)
        assertEquals(listOf(UpdateEffect.ScheduleNextCheck(2 * BACKOFF_BASE_MS)), second.effects)
        val outside = reduce(status(phase = UpdatePhase.Available, available = "2.1.0"), UpdateEvent.CheckFailed(UpdateError.Http))
        assertEquals(UpdatePhase.Available, outside.next.phase)
        assertTrue(outside.effects.isEmpty())
    }

    // ── User decisions ──────────────────────────────────────────────────────

    @Test
    fun `skipping mutes the offered version`() {
        val r = reduce(status(phase = UpdatePhase.Available, available = "2.1.0"), UpdateEvent.SkipRequested("2.1.0"))
        assertEquals(UpdatePhase.UpToDate, r.next.phase)
        assertEquals("2.1.0", r.next.skippedVersion)
        assertEquals("", r.next.availableVersion)
        assertEquals("", r.next.downloadUrl)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun `a skip of something else, an empty skip and a required update are ignored`() {
        val other = reduce(status(phase = UpdatePhase.Available, available = "2.1.0"), UpdateEvent.SkipRequested("2.0.5"))
        assertEquals(UpdatePhase.Available, other.next.phase)
        assertEquals("2.0.5", other.next.skippedVersion)
        val empty = status(phase = UpdatePhase.Available, available = "2.1.0")
        assertEquals(empty, reduce(empty, UpdateEvent.SkipRequested("")).next)
        val required = status(phase = UpdatePhase.Available, available = "2.1.0", required = true)
        assertEquals(required, reduce(required, UpdateEvent.SkipRequested("2.1.0")).next)
    }

    // ── Connectivity ────────────────────────────────────────────────────────

    @Test
    fun `coming back online re-arms the check the gate refused`() {
        val gated = status(phase = UpdatePhase.Failed, error = UpdateError.Offline, online = false, failures = 1)
        val back = reduce(gated, UpdateEvent.ReachabilityChanged(true))
        assertTrue(back.next.online)
        assertEquals(listOf(UpdateEffect.ScheduleNextCheck(RECONNECT_CHECK_DELAY_MS)), back.effects)
        val disabled = reduce(gated.copy(checksEnabled = false), UpdateEvent.ReachabilityChanged(true))
        assertTrue(disabled.effects.isEmpty())
        val httpFailure = reduce(gated.copy(error = UpdateError.Http), UpdateEvent.ReachabilityChanged(true))
        assertTrue(httpFailure.effects.isEmpty())
        val lost = reduce(status(phase = UpdatePhase.Available, available = "2.1.0"), UpdateEvent.ReachabilityChanged(false))
        assertFalse(lost.next.online)
        assertEquals(UpdatePhase.Available, lost.next.phase)
        assertTrue(lost.effects.isEmpty())
    }

    @Test
    fun `every phase-event pair is total`() {
        val events =
            listOf(
                UpdateEvent.PrefsChanged(true, ""),
                UpdateEvent.PrefsChanged(false, "2.1.0"),
                check,
                UpdateEvent.CheckRequested(UpdateTrigger.Manual),
                UpdateEvent.ManifestArrived(manifest()),
                UpdateEvent.CheckFailed(UpdateError.Http),
                UpdateEvent.SkipRequested("2.1.0"),
                UpdateEvent.ReachabilityChanged(false),
                UpdateEvent.ReachabilityChanged(true),
            )
        for (phase in everyPhase) {
            for (event in events) {
                val r = reduce(status(phase = phase, available = "2.1.0"), event)
                assertTrue("$phase x $event", r.next.currentVersion == "2.0.0")
            }
        }
    }
}
