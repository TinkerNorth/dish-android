// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.controllerdb

import com.tinkernorth.dish.core.controllerdb.ControllerDbCheckResult
import com.tinkernorth.dish.core.controllerdb.ControllerDbSource
import com.tinkernorth.dish.core.controllerdb.FakeControllerDbGateway
import com.tinkernorth.dish.core.controllerdb.ManifestReply
import com.tinkernorth.dish.core.controllerdb.ServedRows
import com.tinkernorth.dish.core.controllerdb.servedFiles
import com.tinkernorth.dish.core.controllerdb.testJson
import com.tinkernorth.dish.repository.ControllerDbRepository
import com.tinkernorth.dish.repository.mapBackedPrefs
import com.tinkernorth.dish.source.store.ControllerDbPreferenceStore
import com.tinkernorth.dish.source.store.ControllerDbPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerDbUpdaterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val assets = File("src/main/assets/controllers/v1")
    private val gateway = FakeControllerDbGateway(files = servedFiles(2, "newpad"))
    private val pushed = mutableListOf<ServedRows>()
    private val prefsStore = ControllerDbPreferenceStore(mapBackedPrefs().first)

    private fun TestScope.updater(): ControllerDbUpdater {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ControllerDbUpdater(
            repository = ControllerDbRepository(tmp.root, { File(assets, it).readBytes() }, testJson),
            prefs = prefsStore,
            gateway = gateway,
            pushRows = ::recordPush,
            scope = backgroundScope,
            io = dispatcher,
            json = testJson,
            nowMs = { NOW_MS },
        )
    }

    private fun recordPush(rows: ServedRows) {
        pushed += rows
    }

    @Test
    fun `startup hands native the bundled rows before any check`() =
        runTest {
            updater().start()
            runCurrent()
            assertEquals(1, pushed.size)
            assertEquals(270, pushed.single().size)
            assertEquals(0, gateway.manifestRequests)
        }

    @Test
    fun `the first check runs ten seconds after launch`() =
        runTest {
            updater().start()
            advanceTimeBy(9_999)
            assertEquals(0, gateway.manifestRequests)
            advanceTimeBy(2)
            assertEquals(1, gateway.manifestRequests)
        }

    @Test
    fun `checks repeat every six hours while the process runs`() =
        runTest {
            gateway.manifestReply = ManifestReply.NotModified
            updater().start()
            advanceTimeBy(10_001)
            advanceTimeBy(SIX_HOURS_MS)
            assertEquals(2, gateway.manifestRequests)
        }

    @Test
    fun `with the switch off no request leaves the device, on the schedule or on check now`() =
        runTest {
            prefsStore.setChecksEnabled(false)
            val updater = updater()
            updater.start()
            advanceTimeBy(10_001 + 2 * SIX_HOURS_MS)
            updater.checkNow()
            runCurrent()
            assertEquals(0, gateway.manifestRequests)
            assertEquals(0, gateway.fileRequests)
            assertEquals(ControllerDbCheckResult.OFF, updater.status.value.lastCheckResult)
        }

    @Test
    fun `check now runs one check off the schedule`() =
        runTest {
            val updater = updater()
            updater.start()
            runCurrent()
            updater.checkNow()
            runCurrent()
            assertEquals(1, gateway.manifestRequests)
            backgroundScope.coroutineContext.cancelChildren()
        }

    @Test
    fun `an update is stored, becomes active and reaches native`() =
        runTest {
            val updater = updater()
            updater.start()
            runCurrent()
            updater.checkNow()
            runCurrent()
            val status = updater.status.value
            assertEquals(ControllerDbSource.CACHE, status.source)
            assertEquals(2, status.activeVersion)
            assertEquals(1, status.bundledVersion)
            assertEquals(ControllerDbCheckResult.UPDATED, status.lastCheckResult)
            assertEquals(NOW_MS, status.lastCheckAtMs)
            assertEquals(1, pushed.last().size)
        }

    @Test
    fun `a failed check records its result and keeps the copy in force`() =
        runTest {
            gateway.tamperedPaths = setOf("controllers.json")
            val updater = updater()
            updater.start()
            runCurrent()
            updater.checkNow()
            runCurrent()
            assertEquals(ControllerDbCheckResult.INVALID, updater.status.value.lastCheckResult)
            assertEquals(ControllerDbSource.BUNDLED, updater.status.value.source)
            assertEquals(1, pushed.size)
        }

    @Test
    fun `the next launch reads the stored download as active`() =
        runTest {
            val first = updater()
            first.start()
            runCurrent()
            first.checkNow()
            runCurrent()
            val second = updater()
            second.start()
            runCurrent()
            assertEquals(ControllerDbSource.CACHE, second.active.value?.source)
        }

    @Test
    fun `the etag of the last manifest is sent with the next`() =
        runTest {
            val updater = updater()
            updater.start()
            runCurrent()
            updater.checkNow()
            runCurrent()
            updater.checkNow()
            runCurrent()
            assertEquals(listOf(null, "\"etag-2\""), gateway.etagsSent)
        }

    @Test
    fun `the switch off is the result whatever the last check said`() {
        val prefs = ControllerDbPreferences(checksEnabled = false, lastCheckResult = ControllerDbCheckResult.UPDATED)
        assertEquals(ControllerDbCheckResult.OFF, effectiveCheckResult(prefs))
        assertTrue(effectiveCheckResult(prefs.copy(checksEnabled = true)) == ControllerDbCheckResult.UPDATED)
    }

    private companion object {
        const val NOW_MS = 1_763_586_000_000L
        const val SIX_HOURS_MS = 6L * 60 * 60 * 1000
    }
}
