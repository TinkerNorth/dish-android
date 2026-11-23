// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.controllerdb

import com.tinkernorth.dish.core.controllerdb.ControllerDbCheckResult
import com.tinkernorth.dish.core.controllerdb.ControllerDbGateway
import com.tinkernorth.dish.core.controllerdb.ControllerDbSource
import com.tinkernorth.dish.core.controllerdb.FakeControllerDbGateway
import com.tinkernorth.dish.core.controllerdb.FileReply
import com.tinkernorth.dish.core.controllerdb.ManifestReply
import com.tinkernorth.dish.core.controllerdb.ServedRows
import com.tinkernorth.dish.core.controllerdb.servedFiles
import com.tinkernorth.dish.core.controllerdb.testJson
import com.tinkernorth.dish.repository.ControllerDbRepository
import com.tinkernorth.dish.repository.mapBackedPrefs
import com.tinkernorth.dish.source.store.ControllerDbPreferenceStore
import com.tinkernorth.dish.source.store.ControllerDbPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

// A served side that fails every manifest request with [failure], counting them.
private class ThrowingGateway(
    private val failure: Throwable,
) : ControllerDbGateway {
    var manifestRequests = 0

    override suspend fun manifest(etag: String?): ManifestReply {
        manifestRequests++
        throw failure
    }

    override suspend fun file(path: String): FileReply = throw failure
}

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerDbUpdaterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val assets = File("src/main/assets/controllers/v1")
    private val gateway = FakeControllerDbGateway(files = servedFiles(2, "newpad"))
    private val pushed = mutableListOf<ServedRows>()
    private val prefsStore = ControllerDbPreferenceStore(mapBackedPrefs().first)

    private fun TestScope.updater(
        served: ControllerDbGateway = gateway,
        readBundled: (String) -> ByteArray = { File(assets, it).readBytes() },
    ): ControllerDbUpdater {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ControllerDbUpdater(
            repository = ControllerDbRepository(tmp.root, readBundled, testJson),
            prefs = prefsStore,
            gateway = served,
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
    fun `a check that fails with something other than an IOException is invalid, and the next tick still checks`() =
        runTest {
            val failing = ThrowingGateway(IllegalStateException("not a network failure"))
            updater(served = failing).start()
            advanceTimeBy(10_001)
            val first = prefsStore.state.value.lastCheckResult
            advanceTimeBy(SIX_HOURS_MS)

            assertEquals(ControllerDbCheckResult.INVALID, first)
            assertEquals(2, failing.manifestRequests)
        }

    @Test
    fun `a failed check keeps the etag it had`() =
        runTest {
            prefsStore.recordCheck(NOW_MS, ControllerDbCheckResult.UP_TO_DATE, "\"etag-1\"")
            updater(served = ThrowingGateway(IllegalStateException("broken"))).start()
            advanceTimeBy(10_001)

            assertEquals("\"etag-1\"", prefsStore.state.value.etag)
        }

    @Test
    fun `a cancelled check is not recorded as a result`() =
        runTest {
            updater(served = ThrowingGateway(CancellationException("the process scope ended"))).start()
            advanceTimeBy(10_001)

            assertNull(prefsStore.state.value.lastCheckResult)
        }

    @Test
    fun `a bundled copy that does not load leaves an empty database in force, and the checks still run`() =
        runTest {
            val updater = updater(readBundled = ::aBrokenApk)
            updater.start()
            advanceTimeBy(10_001)

            assertEquals(0, updater.status.value.bundledVersion)
            assertEquals(2, updater.status.value.activeVersion)
            assertEquals(1, gateway.manifestRequests)
        }

    @Test
    fun `a stored download that no longer reads forgets its etag, so the next check fetches it again`() =
        runTest {
            val first = updater()
            first.start()
            runCurrent()
            first.checkNow()
            runCurrent()
            File(tmp.root, "current/strings/en.json").writeText("{}")

            val second = updater()
            second.start()
            runCurrent()
            second.checkNow()
            runCurrent()

            assertEquals(listOf(null, null), gateway.etagsSent)
            assertEquals(ControllerDbSource.CACHE, second.status.value.source)
        }

    @Test
    fun `with nothing stored the etag of an equal manifest is kept`() =
        runTest {
            prefsStore.recordCheck(NOW_MS, ControllerDbCheckResult.UP_TO_DATE, "\"etag-1\"")
            updater().start()
            runCurrent()

            assertEquals("\"etag-1\"", prefsStore.state.value.etag)
        }

    @Test
    fun `the switch off is the result whatever the last check said`() {
        val prefs = ControllerDbPreferences(checksEnabled = false, lastCheckResult = ControllerDbCheckResult.UPDATED)
        assertEquals(ControllerDbCheckResult.OFF, effectiveCheckResult(prefs))
        assertTrue(effectiveCheckResult(prefs.copy(checksEnabled = true)) == ControllerDbCheckResult.UPDATED)
    }

    private fun aBrokenApk(path: String): ByteArray = throw IOException("$path is missing from the APK")

    private companion object {
        const val NOW_MS = 1_763_586_000_000L
        const val SIX_HOURS_MS = 6L * 60 * 60 * 1000
    }
}
