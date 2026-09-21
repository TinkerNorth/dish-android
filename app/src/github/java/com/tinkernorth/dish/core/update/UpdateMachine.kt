// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.update

// The update notice's lifecycle as a pure reducer, the notify-only subset of
// dish-windows' UpdateMachine: this client never downloads or applies
// anything, it tells the user a newer release exists and opens it in the
// browser. Every (phase x event) pair is total, `reduce` does no IO and reads
// no clock, and effects come back as data for UpdateCoordinator to execute
// against the network, the preferences and the timers.
//
// Two rules earn their own comment because getting either wrong ships a client
// that nags forever or never:
//   - Ordering is by the parsed version triple ONLY. `publishedAt` is display
//     text and no wall clock enters this file, so a phone with a skewed clock
//     behaves exactly like one without.
//   - A skip mutes exactly one version, and never a required one: a build
//     below the release's supported minimum keeps being told until it moves.

enum class UpdatePhase {
    Disabled, // checks turned off: no timers, no network IO at all
    Idle, // enabled, nothing known yet
    Checking, // a manifest fetch is in flight
    UpToDate, // the newest published release is this one (or a skipped one)
    Available, // a newer release exists; the notice is showing
    Failed, // the last check failed; `error` says how
}

enum class UpdateError {
    None,
    Offline, // reachability gate; no request was made
    Http, // transport, TLS, status code, 404 in a publish window
    ManifestInvalid, // any parseUpdateManifest rejection, incl. portal HTML
}

enum class UpdateTrigger {
    Startup,
    Periodic,
    Manual,
    Retry,
}

data class UpdateStatus(
    val currentVersion: String, // the build's own version, seeded once
    val phase: UpdatePhase = UpdatePhase.Idle,
    val availableVersion: String = "",
    val downloadUrl: String = "",
    val minimumSupportedVersion: String = "",
    val consecutiveFailures: Int = 0,
    val error: UpdateError = UpdateError.None,
    val checksEnabled: Boolean = true,
    val required: Boolean = false, // currentVersion < minimumSupportedVersion
    // `skippedVersion` because a skip must suppress the notice while the
    // manifest still offers that exact version; `online` because the gate is
    // evaluated at CheckRequested time, not when the connectivity event came.
    val skippedVersion: String = "",
    val online: Boolean = true,
)

sealed interface UpdateEvent {
    // The reactive preference slice; the coordinator republishes it on every
    // store change, so this event is also how "checks off" reaches every phase.
    data class PrefsChanged(
        val checksEnabled: Boolean,
        val skippedVersion: String,
    ) : UpdateEvent

    data class CheckRequested(
        val trigger: UpdateTrigger,
    ) : UpdateEvent

    data class ManifestArrived(
        val manifest: UpdateManifest,
    ) : UpdateEvent

    data class CheckFailed(
        val error: UpdateError,
    ) : UpdateEvent

    data class SkipRequested(
        val version: String,
    ) : UpdateEvent

    data class ReachabilityChanged(
        val online: Boolean,
    ) : UpdateEvent
}

sealed interface UpdateEffect {
    // GET the release permalink. `manual` only relaxes the coordinator's rate
    // limiting; the request itself is identical.
    data class FetchManifest(
        val manual: Boolean,
    ) : UpdateEffect

    // Re-arm the single check timer. Failure delays are the UNJITTERED ladder
    // value; the coordinator jitters those and only those.
    data class ScheduleNextCheck(
        val delayMs: Long,
    ) : UpdateEffect

    data object PersistLastCheck : UpdateEffect
}

data class UpdateReduction(
    val next: UpdateStatus,
    val effects: List<UpdateEffect> = emptyList(),
)

object UpdateMachine {
    // Startup is delayed so the check never competes with the first frame; the
    // min gap keeps a relaunch loop from hammering the permalink, and the
    // future-jump escape means a clock that reads 2099 costs one extra check,
    // not silence. The ladder is deliberately not the 1 s..60 s satellite
    // reconnect scale: polling GitHub on that would be abuse.
    const val STARTUP_DELAY_MS = 15_000L
    const val MIN_CHECK_GAP_MS = 60L * 60 * 1000 // 1 h
    const val FUTURE_SKEW_ESCAPE_MS = 24L * 60 * 60 * 1000 // 24 h
    const val PERIODIC_INTERVAL_MS = 4L * 60 * 60 * 1000 // 4 h
    const val BACKOFF_BASE_MS = 10L * 60 * 1000 // 10 min
    const val BACKOFF_CAP_MS = 6L * 60 * 60 * 1000 // 6 h
    const val BACKOFF_JITTER = 0.2 // +-20 percent
    const val MANUAL_MIN_GAP_MS = 10_000L // manual rate limit
    const val RECONNECT_CHECK_DELAY_MS = 30_000L // after the network returns

    // 10, 20, 40 ... 360 min. `failures` is the count INCLUDING the one just
    // recorded, so the first failure waits BACKOFF_BASE_MS.
    fun backoffDelayMs(failures: Int): Long {
        var delay = BACKOFF_BASE_MS
        for (i in 1 until failures) {
            delay = (delay * 2).coerceAtMost(BACKOFF_CAP_MS)
        }
        return delay
    }

    // Deterministic given `unit` in [0, 1]: the coordinator supplies the random
    // draw, so the ladder itself stays pinnable in a test. Applied ONLY to
    // failure backoff (spreading a fleet's retries), never to the startup
    // delay or the periodic interval, both of which are asserted verbatim.
    fun jitteredDelayMs(
        baseMs: Long,
        unit: Double,
    ): Long {
        val clamped = unit.coerceIn(0.0, 1.0)
        val factor = 1.0 - BACKOFF_JITTER + (2.0 * BACKOFF_JITTER * clamped)
        val scaled = baseMs.toDouble() * factor
        return if (scaled < 0.0) 0L else scaled.toLong()
    }

    // One arm per event, with the phase guards inside it: the interesting
    // rules are about WHAT arrived, not where it arrived. The tests pin the
    // effect lists in order, so a reordering is a behaviour change.
    fun reduce(
        s: UpdateStatus,
        event: UpdateEvent,
    ): UpdateReduction =
        when (event) {
            is UpdateEvent.PrefsChanged -> prefsChanged(s, event)
            is UpdateEvent.CheckRequested -> checkRequested(s, event.trigger)
            is UpdateEvent.ManifestArrived -> manifestArrived(s, event.manifest)
            is UpdateEvent.CheckFailed -> if (s.phase == UpdatePhase.Checking) settleFailure(s, event.error) else stay(s)
            is UpdateEvent.SkipRequested -> skipRequested(s, event.version)
            is UpdateEvent.ReachabilityChanged -> reachabilityChanged(s, event.online)
        }

    private fun stay(s: UpdateStatus) = UpdateReduction(s)

    private fun prefsChanged(
        s: UpdateStatus,
        prefs: UpdateEvent.PrefsChanged,
    ): UpdateReduction {
        val n = s.copy(checksEnabled = prefs.checksEnabled, skippedVersion = prefs.skippedVersion)
        return when {
            // Off means off from EVERY phase: no timer is re-armed and no
            // request is made while Disabled.
            !prefs.checksEnabled -> stay(n.copy(phase = UpdatePhase.Disabled, error = UpdateError.None))
            // Re-enabling behaves exactly like a cold start.
            s.phase == UpdatePhase.Disabled ->
                UpdateReduction(n.copy(phase = UpdatePhase.Idle), listOf(UpdateEffect.ScheduleNextCheck(STARTUP_DELAY_MS)))
            else -> stay(n)
        }
    }

    private fun checkRequested(
        s: UpdateStatus,
        trigger: UpdateTrigger,
    ): UpdateReduction =
        when {
            s.phase == UpdatePhase.Disabled || s.phase == UpdatePhase.Checking -> stay(s)
            // The reachability gate answers WITHOUT touching the network, which
            // is the whole point: a captive portal never gets a request.
            !s.online -> settleFailure(s, UpdateError.Offline)
            else ->
                UpdateReduction(
                    s.copy(phase = UpdatePhase.Checking),
                    listOf(UpdateEffect.FetchManifest(manual = trigger == UpdateTrigger.Manual)),
                )
        }

    private fun manifestArrived(
        s: UpdateStatus,
        m: UpdateManifest,
    ): UpdateReduction {
        if (s.phase != UpdatePhase.Checking) return stay(s)
        val required = isStrictlyNewer(m.minimumSupportedVersion, s.currentVersion)
        val facts =
            s.copy(
                minimumSupportedVersion = m.minimumSupportedVersion,
                required = required,
                consecutiveFailures = 0,
                error = UpdateError.None,
            )
        val newer = isStrictlyNewer(m.version, s.currentVersion)
        val skipped = s.skippedVersion.isNotEmpty() && s.skippedVersion == m.version
        // Nothing to offer, or the user muted this exact version. A skip is
        // ignored while the running build is below the supported minimum.
        val next =
            if (!newer || (skipped && !required)) {
                facts.copy(phase = UpdatePhase.UpToDate, availableVersion = "", downloadUrl = "")
            } else {
                facts.copy(phase = UpdatePhase.Available, availableVersion = m.version, downloadUrl = m.downloadUrl)
            }
        return UpdateReduction(next, listOf(UpdateEffect.PersistLastCheck, UpdateEffect.ScheduleNextCheck(PERIODIC_INTERVAL_MS)))
    }

    // The failure counts toward the backoff ladder and `error` describes the
    // phase the status settles in.
    private fun settleFailure(
        s: UpdateStatus,
        error: UpdateError,
    ): UpdateReduction {
        val failures = s.consecutiveFailures + 1
        return UpdateReduction(
            s.copy(phase = UpdatePhase.Failed, error = error, consecutiveFailures = failures),
            listOf(UpdateEffect.ScheduleNextCheck(backoffDelayMs(failures))),
        )
    }

    private fun skipRequested(
        s: UpdateStatus,
        version: String,
    ): UpdateReduction {
        // A required update cannot be skipped: the build is already unsupported.
        if (s.required || version.isEmpty()) return stay(s)
        val n = s.copy(skippedVersion = version)
        return stay(
            if (s.availableVersion == version) {
                n.copy(phase = UpdatePhase.UpToDate, availableVersion = "", downloadUrl = "")
            } else {
                n
            },
        )
    }

    private fun reachabilityChanged(
        s: UpdateStatus,
        online: Boolean,
    ): UpdateReduction {
        val n = s.copy(online = online)
        val wasOfflineGated = s.phase == UpdatePhase.Failed && s.error == UpdateError.Offline && s.checksEnabled
        return if (online && wasOfflineGated) {
            UpdateReduction(n, listOf(UpdateEffect.ScheduleNextCheck(RECONNECT_CHECK_DELAY_MS)))
        } else {
            stay(n)
        }
    }
}
