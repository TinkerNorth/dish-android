// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The copy of dbVersion 1 the app ships, in app/src/main/assets/controllers/v1.
class BundledControllerDbTest {
    private val dir = File("src/main/assets/controllers/v1")
    private val manifest = (parseManifest(File(dir, MANIFEST_FILE).readBytes(), testJson) as ParsedManifest.Ok).manifest
    private val db = (parseControllerDb(File(dir, CONTROLLERS_FILE).readBytes(), manifest.dbVersion, testJson) as ParsedDb.Ok).db
    private val english = parseStrings(File(dir, ENGLISH_STRINGS_FILE).readBytes(), testJson).orEmpty()

    @Test
    fun `the bundled database is schema 1 of the manifest's dbVersion`() {
        assertEquals(CONTROLLER_DB_SCHEMA, db.schema)
        assertEquals(manifest.dbVersion, db.dbVersion)
    }

    @Test
    fun `every file the manifest names is bundled with its size and sha256`() {
        manifest.files.forEach { (path, expected) ->
            assertEquals(path, FileCheck.Ok, checkFile(File(dir, path).readBytes(), expected))
        }
    }

    @Test
    fun `dbVersion 1 holds 272 records, one per id`() {
        val ids = db.records.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(DB_VERSION_1_RECORDS, db.records.size)
    }

    @Test
    fun `the records split into the verified usb, other usb and bluetooth rows the native tables compile`() {
        val (bluetooth, usb) = db.records.partition { it.support.verification.transport == TRANSPORT_BLUETOOTH }
        val verified = usb.count { it.support.verification.state == VERIFICATION_VERIFIED }
        assertEquals(DB_VERSION_1_RECORDS, verified + (usb.size - verified) + bluetooth.size)
        assertEquals(2, bluetooth.size)
    }

    @Test
    fun `every record has an english name`() {
        db.records.forEach { assertNotNull(it.id, english["controller.${it.id}.name"]) }
    }

    @Test
    fun `every record is applicable on this build`() {
        db.records.forEach { assertTrue(it.id, !isInert(it)) }
    }

    @Test
    fun `every bundled locale is a flat strings file`() {
        listOf("en", "bs", "de", "es", "fr", "pt-BR").forEach { locale ->
            assertNotNull(locale, parseStrings(File(dir, "strings/$locale.json").readBytes(), testJson))
        }
    }

    private companion object {
        const val DB_VERSION_1_RECORDS = 272
    }
}
