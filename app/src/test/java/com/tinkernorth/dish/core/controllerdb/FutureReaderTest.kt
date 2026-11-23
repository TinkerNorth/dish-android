// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

import com.tinkernorth.dish.di.AppModule
import com.tinkernorth.dish.repository.ControllerDbRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// The future-reader set (dish-website, docs/controller-db.md): a dbVersion 2 the website may publish
// under /controllers/v1/ later, which this reader, released today, must take as its EXPECT.md says.
// Served to the production check with the bundled dbVersion 1 in force, stored, read back and
// indexed as at the next launch, then handed down as the rows native classifies with.
//
// This build's Direct path runs both xinput360 and dualshock4, so future-pad-served (1209:0f02) and
// future-pad-served-ds4 (1209:0f05) both resolve to their served records, and neither is inert.
class FutureReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fixture = File("src/test/resources/controllerdb/future-reader")
    private val assets = File("src/main/assets/controllers/v1")
    private val json = AppModule.provideJson()
    private val repository by lazy { ControllerDbRepository(tmp.root, { File(assets, it).readBytes() }, json) }

    private lateinit var outcome: CheckOutcome
    private lateinit var active: ActiveControllerDb
    private lateinit var rows: ServedRows

    @Before
    fun serveTheFutureAndRestart() =
        runTest {
            val manifest = File(fixture, MANIFEST_FILE).readBytes()
            val gateway = FakeControllerDbGateway(files = fixtureFiles(), manifestReply = ManifestReply.Fresh(manifest, ETAG))
            outcome = runControllerDbCheck(gateway, activeVersion = repository.bundled().db.dbVersion, etag = null, json = json)
            outcome.download?.let(repository::store)
            active = activeControllerDb(repository.bundled(), repository.cached())
            rows = servedRowsOf(active.index)
        }

    private fun fixtureFiles(): Map<String, ByteArray> =
        fixture
            .walkTopDown()
            .filter { it.isFile && it.name != MANIFEST_FILE }
            .associate { it.relativeTo(fixture).path.replace(File.separatorChar, '/') to it.readBytes() }

    private fun record(id: String): ControllerRecord =
        active.index.db.records
            .single { it.id == id }

    private fun rowFor(identity: String): Int? {
        val (vid, pid) = requireNotNull(parseIdentity(identity))
        val at = (0 until rows.size).firstOrNull { rows.vendorIds[it] == vid && rows.productIds[it] == pid }
        return at
    }

    // ---- the whole set ----

    @Test
    fun `the check ends updated, and version 2 replaces version 1 whole`() {
        assertEquals(ControllerDbCheckResult.UPDATED, outcome.result)
        assertEquals(ControllerDbSource.CACHE, active.source)
        assertEquals(2, active.activeVersion)
        assertEquals(
            ALL_IDS,
            active.index.db.records
                .map { it.id }
                .toSet(),
        )
    }

    @Test
    fun `the manifest's unknown signature and the database's unknown generatedBy are ignored`() {
        val manifest = parseManifest(File(fixture, MANIFEST_FILE).readBytes(), json)
        val db = parseControllerDb(File(fixture, CONTROLLERS_FILE).readBytes(), expectedVersion = 2, json = json)

        assertTrue(manifest is ParsedManifest.Ok)
        assertTrue(db is ParsedDb.Ok)
    }

    @Test
    fun `a locale the build does not ship does not spoil the set, and nothing is written outside the manifest's paths`() {
        val expected = (fixtureFiles().keys + MANIFEST_FILE).toSet()
        val current = File(tmp.root, "current")
        val written =
            current
                .walkTopDown()
                .filter { it.isFile }
                .map {
                    it
                        .relativeTo(
                            current,
                        ).path
                        .replace(File.separatorChar, '/')
                }.toSet()

        assertTrue(written.isNotEmpty())
        assertTrue("$written", expected.containsAll(written))
    }

    // ---- record by record ----

    @Test
    fun `the DualSense keeps its family and its row, both issues, and matches a text os by its major`() {
        val dualsense = record("sony-dualsense")
        val textIssue = issuesFor(dualsense, TRANSPORT_USB, "Future Handheld", androidMajor = 15).map { it.id }
        val otherRelease = issuesFor(dualsense, TRANSPORT_USB, "Future Handheld", androidMajor = 14)

        assertFalse(isInert(dualsense))
        assertEquals("dualsense", dualsense.input.protocol.ref)
        assertEquals(listOf("thor-ds5-rumble-usb", "future-os-as-text"), dualsense.support.issues.map { it.id })
        assertEquals(listOf("future-os-as-text"), textIssue)
        assertTrue(otherRelease.isEmpty())
        assertEquals(PARSER_DUALSENSE, rows.parsers[requireNotNull(rowFor("054c:0ce6"))])
        assertTrue(rows.verified[requireNotNull(rowFor("054c:0ce6"))])
    }

    @Test
    fun `the Xbox 360 record needing a future family is read by the build's own row`() {
        val xbox = record("xbox-360-controller")

        assertEquals("xinput360", xbox.input.protocol.ref)
        assertEquals(PARSER_XINPUT360, rows.parsers[requireNotNull(rowFor("045e:028e"))])
    }

    @Test
    fun `the served xinput360 pad resolves to its record, and Android's optIn means it is not claimed without asking`() {
        val at = requireNotNull(rowFor("1209:0f02"))

        assertEquals(PARSER_XINPUT360, rows.parsers[at])
        assertFalse(rows.verified[at])
    }

    @Test
    fun `the served dualshock4 pad resolves to its record, and Android's optIn means it is not claimed without asking`() {
        val at = requireNotNull(rowFor("1209:0f05"))

        assertEquals(PARSER_DUALSHOCK4, rows.parsers[at])
        assertFalse(rows.verified[at])
    }

    @Test
    fun `a pad on a future family or needing a future op is named and never applied`() {
        assertTrue(isInert(record("future-pad-new-family")))
        assertTrue(isInert(record("future-pad-new-op")))
        assertNull(rowFor("1209:0f01"))
        assertNull(rowFor("1209:0f03"))
        assertEquals("Future Pad (new family)", active.index.strings.controllerName("future-pad-new-family", ENGLISH))
    }

    @Test
    fun `the 8BitDo's object mapping leaves the build's own record for 2dc8_5006 in force`() {
        val m30 = record("8bitdo-m30-d-input")

        assertFalse(isInert(m30))
        assertEquals("eightbitdo", mappingName(m30))
        assertNotNull(rowFor("2dc8:5006"))
    }

    @Test
    fun `the misauthored record is left out of the rows and its issue is not shown`() {
        val misauthored = record("future-pad-misauthored")

        assertNull(rowFor("1209:0f04"))
        assertTrue(issuesFor(misauthored, "futureTransport", "", androidMajor = 15).isEmpty())
    }

    @Test
    fun `the Bluetooth Steam Controller is as in version 1`() {
        val steam = active.index.recordForBluetooth(0x28DE, 0x1106)

        assertEquals("valve-steam-controller-bluetooth", steam?.id)
        assertFalse(isInert(requireNotNull(steam)))
    }

    private companion object {
        const val ETAG = "\"future\""
        const val PARSER_XINPUT360 = 1
        const val PARSER_DUALSHOCK4 = 3
        const val PARSER_DUALSENSE = 4
        val ALL_IDS =
            setOf(
                "sony-dualsense",
                "xbox-360-controller",
                "future-pad-served",
                "future-pad-served-ds4",
                "future-pad-new-family",
                "future-pad-new-op",
                "8bitdo-m30-d-input",
                "future-pad-misauthored",
                "valve-steam-controller-bluetooth",
            )
    }
}
