// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControllerDbCheckTest {
    private val gateway = FakeControllerDbGateway(files = servedFiles(2, "newpad"))

    private suspend fun check(
        active: Int = 1,
        etag: String? = "\"old\"",
    ) = runControllerDbCheck(gateway, active, etag, testJson)

    @Test
    fun `the manifest is asked for with the etag of the last one`() =
        runTest {
            check(etag = "\"old\"")
            assertEquals(listOf<String?>("\"old\""), gateway.etagsSent)
        }

    @Test
    fun `a 304 is up to date and fetches nothing else`() =
        runTest {
            gateway.manifestReply = ManifestReply.NotModified
            val outcome = check()
            assertEquals(ControllerDbCheckResult.UP_TO_DATE, outcome.result)
            assertEquals("\"old\"", outcome.etag)
            assertEquals(0, gateway.fileRequests)
        }

    @Test
    fun `an equal dbVersion is up to date, fetches nothing else, and keeps the new etag`() =
        runTest {
            val outcome = check(active = 2)
            assertEquals(ControllerDbCheckResult.UP_TO_DATE, outcome.result)
            assertEquals("\"etag-2\"", outcome.etag)
            assertEquals(0, gateway.fileRequests)
        }

    @Test
    fun `an unreachable manifest is offline`() =
        runTest {
            gateway.manifestReply = ManifestReply.Unreachable
            assertEquals(ControllerDbCheckResult.OFFLINE, check().result)
        }

    @Test
    fun `an invalid manifest is invalid and keeps the old etag`() =
        runTest {
            gateway.manifestReply = ManifestReply.Fresh("{}".toByteArray(), "\"new\"")
            val outcome = check()
            assertEquals(ControllerDbCheckResult.INVALID, outcome.result)
            assertEquals("\"old\"", outcome.etag)
        }

    @Test
    fun `a newer manifest downloads every file it names and is updated`() =
        runTest {
            val outcome = check()
            assertEquals(ControllerDbCheckResult.UPDATED, outcome.result)
            assertEquals(3, gateway.fileRequests)
            val download = requireNotNull(outcome.download)
            assertEquals(2, download.db.dbVersion)
            assertEquals("newpad", download.strings.getValue("en")["controller.newpad.name"])
            assertEquals("\"etag-2\"", outcome.etag)
        }

    @Test
    fun `a file whose bytes do not match the manifest is invalid and nothing is kept`() =
        runTest {
            gateway.tamperedPaths = setOf(ENGLISH_STRINGS_FILE)
            val outcome = check()
            assertEquals(ControllerDbCheckResult.INVALID, outcome.result)
            assertNull(outcome.download)
            assertEquals("\"old\"", outcome.etag)
        }

    @Test
    fun `a file that does not arrive is offline`() =
        runTest {
            gateway.unreachablePaths = setOf(CONTROLLERS_FILE)
            assertEquals(ControllerDbCheckResult.OFFLINE, check().result)
        }

    @Test
    fun `a database whose schema is not 1 is invalid even when its hashes match`() =
        runTest {
            gateway.files = gateway.files + (CONTROLLERS_FILE to "{\"schema\":2,\"dbVersion\":2,\"records\":[]}".toByteArray())
            assertEquals(ControllerDbCheckResult.INVALID, check().result)
        }

    @Test
    fun `a strings file that is not flat strings is invalid`() =
        runTest {
            gateway.files = gateway.files + ("strings/de.json" to "{\"a\":1}".toByteArray())
            assertEquals(ControllerDbCheckResult.INVALID, check().result)
        }
}
