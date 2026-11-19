// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.controllerdb

import android.util.Log
import com.tinkernorth.dish.core.controllerdb.ActiveControllerDb
import com.tinkernorth.dish.core.controllerdb.ControllerDbCheckResult
import com.tinkernorth.dish.core.controllerdb.ControllerDbGateway
import com.tinkernorth.dish.core.controllerdb.ControllerDbSource
import com.tinkernorth.dish.core.controllerdb.DownloadedControllerDb
import com.tinkernorth.dish.core.controllerdb.LoadedControllerDb
import com.tinkernorth.dish.core.controllerdb.ServedRows
import com.tinkernorth.dish.core.controllerdb.activeControllerDb
import com.tinkernorth.dish.core.controllerdb.runControllerDbCheck
import com.tinkernorth.dish.core.controllerdb.servedRowsOf
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.di.IoDispatcher
import com.tinkernorth.dish.repository.ControllerDbRepository
import com.tinkernorth.dish.source.store.ControllerDbPreferenceStore
import com.tinkernorth.dish.source.store.ControllerDbPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// What Diagnostics, Settings and the bundle show of the controller database (section 6.7).
data class ControllerDbStatus(
    val bundledVersion: Int,
    val activeVersion: Int,
    val source: ControllerDbSource,
    val checksEnabled: Boolean,
    val lastCheckAtMs: Long,
    val lastCheckResult: ControllerDbCheckResult?,
    val checking: Boolean,
) {
    companion object {
        val UNLOADED =
            ControllerDbStatus(
                bundledVersion = 0,
                activeVersion = 0,
                source = ControllerDbSource.BUNDLED,
                checksEnabled = true,
                lastCheckAtMs = 0L,
                lastCheckResult = null,
                checking = false,
            )
    }
}

// The switch off is the result: no check runs, whatever the last one said.
fun effectiveCheckResult(prefs: ControllerDbPreferences): ControllerDbCheckResult? =
    if (prefs.checksEnabled) prefs.lastCheckResult else ControllerDbCheckResult.OFF

// Section 6: loads the bundled copy and the cached download at startup, hands native the active
// rows, and checks dish.tinkernorth.com ten seconds after launch and every six hours while the
// process runs, only while the switch is on. "Check now" runs one check off the schedule, also only
// while the switch is on: with it off no request leaves the device.
@Singleton
class ControllerDbUpdater internal constructor(
    private val repository: ControllerDbRepository,
    private val prefs: ControllerDbPreferenceStore,
    private val gateway: ControllerDbGateway,
    private val pushRows: (ServedRows) -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val json: Json,
    private val nowMs: () -> Long,
) {
    @Inject
    constructor(
        repository: ControllerDbRepository,
        prefs: ControllerDbPreferenceStore,
        gateway: ControllerDbGateway,
        physicalInputNative: PhysicalInputNative,
        scope: CoroutineScope,
        @IoDispatcher io: CoroutineDispatcher,
        json: Json,
    ) : this(
        repository = repository,
        prefs = prefs,
        gateway = gateway,
        pushRows = physicalInputNative::setServedModels,
        scope = scope,
        io = io,
        json = json,
        nowMs = System::currentTimeMillis,
    )

    private val checkTurn = Mutex()
    private val checking = MutableStateFlow(false)
    private val _active = MutableStateFlow<ActiveControllerDb?>(null)
    private var bundled: LoadedControllerDb? = null

    // Null until the startup load finishes.
    val active: StateFlow<ActiveControllerDb?> = _active.asStateFlow()

    val status: StateFlow<ControllerDbStatus> =
        combine(_active, prefs.state, checking, ::statusOf)
            .stateIn(scope, SharingStarted.Eagerly, ControllerDbStatus.UNLOADED)

    fun start() {
        scope.launch(io) {
            load()
            runSchedule()
        }
    }

    fun setChecksEnabled(enabled: Boolean) = prefs.setChecksEnabled(enabled)

    fun checkNow() {
        scope.launch(io) { checkIfEnabled() }
    }

    private fun load() {
        val loaded = repository.bundled()
        bundled = loaded
        publish(activeControllerDb(loaded, repository.cached()))
    }

    private suspend fun runSchedule() {
        delay(STARTUP_DELAY_MS)
        while (true) {
            checkIfEnabled()
            delay(PERIODIC_INTERVAL_MS)
        }
    }

    private suspend fun checkIfEnabled() =
        checkTurn.withLock {
            if (!prefs.state.value.checksEnabled) return@withLock
            val active = _active.value ?: return@withLock
            checking.value = true
            try {
                check(active)
            } finally {
                checking.value = false
            }
        }

    private suspend fun check(active: ActiveControllerDb) {
        val outcome = runControllerDbCheck(gateway, active.activeVersion, prefs.state.value.etag, json)
        val download = outcome.download
        val result = if (download == null) outcome.result else install(download)
        val installFailed = download != null && result != ControllerDbCheckResult.UPDATED
        val etag = if (installFailed) prefs.state.value.etag else outcome.etag
        prefs.recordCheck(nowMs(), result, etag)
    }

    // A download that cannot be written leaves the previous copy in force and counts as a failed
    // check, so the next one fetches it again.
    private suspend fun install(download: DownloadedControllerDb): ControllerDbCheckResult =
        withContext(io) {
            try {
                repository.store(download)
                publish(activeControllerDb(bundled ?: repository.bundled(), repository.cached()))
                ControllerDbCheckResult.UPDATED
            } catch (e: IOException) {
                Log.w(TAG, "controller database: the download could not be written", e)
                ControllerDbCheckResult.INVALID
            }
        }

    private fun publish(active: ActiveControllerDb) {
        _active.value = active
        pushRows(servedRowsOf(active.index))
    }

    private companion object {
        const val TAG = "ControllerDb"
        const val STARTUP_DELAY_MS = 10_000L
        const val PERIODIC_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}

private fun statusOf(
    active: ActiveControllerDb?,
    prefs: ControllerDbPreferences,
    checking: Boolean,
): ControllerDbStatus =
    ControllerDbStatus(
        bundledVersion = active?.bundledVersion ?: 0,
        activeVersion = active?.activeVersion ?: 0,
        source = active?.source ?: ControllerDbSource.BUNDLED,
        checksEnabled = prefs.checksEnabled,
        lastCheckAtMs = prefs.lastCheckAtMs,
        lastCheckResult = effectiveCheckResult(prefs),
        checking = checking,
    )
