// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.update

import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.BuildConfig
import com.tinkernorth.dish.core.update.FUTURE_SKEW_ESCAPE_MS
import com.tinkernorth.dish.core.update.MANUAL_MIN_GAP_MS
import com.tinkernorth.dish.core.update.MIN_CHECK_GAP_MS
import com.tinkernorth.dish.core.update.PERIODIC_INTERVAL_MS
import com.tinkernorth.dish.core.update.STARTUP_DELAY_MS
import com.tinkernorth.dish.core.update.UpdateEffect
import com.tinkernorth.dish.core.update.UpdateEvent
import com.tinkernorth.dish.core.update.UpdatePhase
import com.tinkernorth.dish.core.update.UpdateStatus
import com.tinkernorth.dish.core.update.UpdateTrigger
import com.tinkernorth.dish.core.update.UpdateVersion
import com.tinkernorth.dish.core.update.jitteredDelayMs
import com.tinkernorth.dish.core.update.reduce
import com.tinkernorth.dish.source.system.NetworkState
import com.tinkernorth.dish.source.system.NetworkStateObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

// Runs UpdateMachine against the real world: the preference store, the
// network-state observer, the manifest gateway and one check timer. Every
// decision is the reducer's; this class only executes the effects it returns
// and feeds events back. Checks happen only while the app is on screen
// (ProcessLifecycleOwner start/stop): the timer is dropped on stop and
// re-armed on start by the min-gap rule, so a relaunch loop never hammers the
// permalink and nothing runs while the app is closed.
@Singleton
class UpdateCoordinator internal constructor(
    private val store: UpdatePreferenceStore,
    private val gateway: UpdateManifestGateway,
    private val online: Flow<Boolean>,
    private val scope: CoroutineScope,
    currentVersion: String,
    private val nowMs: () -> Long,
    private val jitterUnit: () -> Double,
) : UpdateNotices {
    @Inject
    constructor(
        store: UpdatePreferenceStore,
        gateway: UpdateManifestGateway,
        network: NetworkStateObserver,
        scope: CoroutineScope,
    ) : this(
        store = store,
        gateway = gateway,
        online = network.state.map { it != NetworkState.NONE },
        scope = scope,
        // A build past its tag reports the tag's version; a name that is not a
        // version at all can never be "older" than a release, so such a build
        // is never offered one.
        currentVersion = UpdateVersion.ofBuild(BuildConfig.VERSION_NAME)?.toString() ?: BuildConfig.VERSION_NAME,
        nowMs = System::currentTimeMillis,
        jitterUnit = { Random.nextDouble() },
    )

    private val lock = Any()
    private val machine = MutableStateFlow(initialStatus(currentVersion, store.state.value))

    // The reducer's full status, for the tests.
    internal val machineStatus: StateFlow<UpdateStatus> = machine.asStateFlow()

    override val supported: Boolean = true

    override val status: StateFlow<UpdateNoticeStatus> =
        machine.map(::noticeOf).stateIn(scope, SharingStarted.Eagerly, noticeOf(machine.value))

    private var checkTimer: Job? = null
    private var fetch: Job? = null
    private var prefsWatch: Job? = null
    private var onlineWatch: Job? = null
    private var lastManualCheckMs = 0L

    @Volatile private var onScreen = false

    override fun onStart(owner: LifecycleOwner) {
        onScreen = true
        prefsWatch =
            scope.launch {
                store.state.collect { dispatch(UpdateEvent.PrefsChanged(it.checksEnabled, it.skippedVersion)) }
            }
        onlineWatch =
            scope.launch {
                online.collect { dispatch(UpdateEvent.ReachabilityChanged(it)) }
            }
        armStartupCheck()
    }

    override fun onStop(owner: LifecycleOwner) {
        onScreen = false
        prefsWatch?.cancel()
        onlineWatch?.cancel()
        synchronized(lock) {
            checkTimer?.cancel()
            checkTimer = null
        }
    }

    override fun setChecksEnabled(enabled: Boolean) = store.setChecksEnabled(enabled)

    override fun checkNow() {
        val now = nowMs()
        if (lastManualCheckMs != 0L && now - lastManualCheckMs < MANUAL_MIN_GAP_MS) return
        lastManualCheckMs = now
        dispatch(UpdateEvent.CheckRequested(UpdateTrigger.Manual))
    }

    override fun skipAvailableVersion() {
        val version = machine.value.availableVersion
        if (version.isEmpty()) return
        dispatch(UpdateEvent.SkipRequested(version))
        // Persist only what the reducer accepted: a required update stays.
        if (machine.value.skippedVersion == version) store.setSkippedVersion(version)
    }

    private fun armStartupCheck() =
        synchronized(lock) {
            if (machine.value.phase != UpdatePhase.Disabled) {
                val now = nowMs()
                val last = store.lastCheckMs()
                // A recorded time far in the FUTURE means the clock moved, not
                // that a check just happened; check anyway rather than going
                // quiet for a day.
                val clockJumped = last > now + FUTURE_SKEW_ESCAPE_MS
                val withinGap = last > 0 && !clockJumped && now - last < MIN_CHECK_GAP_MS
                val delayMs = if (withinGap) PERIODIC_INTERVAL_MS else STARTUP_DELAY_MS
                armTimer(delayMs, if (withinGap) UpdateTrigger.Periodic else UpdateTrigger.Startup)
            }
        }

    private fun dispatch(event: UpdateEvent) =
        synchronized(lock) {
            val reduction = reduce(machine.value, event)
            machine.value = reduction.next
            reduction.effects.forEach { apply(it, reduction.next) }
        }

    private fun apply(
        effect: UpdateEffect,
        next: UpdateStatus,
    ) {
        when (effect) {
            is UpdateEffect.FetchManifest -> startFetch()
            is UpdateEffect.ScheduleNextCheck -> {
                // Only failures are jittered (spreading a fleet's retries); the
                // startup delay and the periodic interval are exact.
                val failed = next.phase == UpdatePhase.Failed
                val delayMs = if (failed) jitteredDelayMs(effect.delayMs, jitterUnit()) else effect.delayMs
                armTimer(delayMs, if (failed) UpdateTrigger.Retry else UpdateTrigger.Periodic)
            }
            UpdateEffect.PersistLastCheck -> store.recordLastCheck(nowMs())
        }
    }

    // One timer: a new arm replaces the old. Nothing is armed while the app is
    // off screen; onStart re-arms by the gap rule. Assumes `lock` held.
    private fun armTimer(
        delayMs: Long,
        trigger: UpdateTrigger,
    ) {
        checkTimer?.cancel()
        checkTimer =
            if (!onScreen) {
                null
            } else {
                scope.launch {
                    delay(delayMs)
                    dispatch(UpdateEvent.CheckRequested(trigger))
                }
            }
    }

    private fun startFetch() {
        fetch?.cancel()
        fetch =
            scope.launch {
                val event =
                    when (val result = gateway.fetchLatest()) {
                        is ManifestFetch.Ok -> UpdateEvent.ManifestArrived(result.manifest)
                        is ManifestFetch.Failed -> UpdateEvent.CheckFailed(result.error)
                    }
                dispatch(event)
            }
    }

    private companion object {
        fun initialStatus(
            currentVersion: String,
            prefs: UpdatePreferences,
        ) = UpdateStatus(
            currentVersion = currentVersion,
            phase = if (prefs.checksEnabled) UpdatePhase.Idle else UpdatePhase.Disabled,
            checksEnabled = prefs.checksEnabled,
            skippedVersion = prefs.skippedVersion,
        )

        fun noticeOf(s: UpdateStatus) =
            UpdateNoticeStatus(
                phase =
                    when (s.phase) {
                        UpdatePhase.Disabled -> UpdateNoticePhase.Disabled
                        UpdatePhase.Idle -> UpdateNoticePhase.Idle
                        UpdatePhase.Checking -> UpdateNoticePhase.Checking
                        UpdatePhase.UpToDate -> UpdateNoticePhase.UpToDate
                        UpdatePhase.Available -> UpdateNoticePhase.Available
                        UpdatePhase.Failed -> UpdateNoticePhase.Failed
                    },
                checksEnabled = s.checksEnabled,
                availableVersion = s.availableVersion,
                downloadUrl = s.downloadUrl,
                required = s.required,
            )
    }
}
