// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerDbDecisionsTest {
    private val body = "{\"schema\":1}".toByteArray()

    private fun manifestJson(
        schema: Int = 1,
        version: Int = 2,
        files: String = "\"controllers.json\":{\"sha256\":\"ab\",\"bytes\":2},\"strings/en.json\":{\"sha256\":\"cd\",\"bytes\":3}",
    ): ByteArray = "{\"schema\":$schema,\"dbVersion\":$version,\"files\":{$files}}".toByteArray()

    @Test
    fun `a manifest of a higher dbVersion is newer`() {
        assertTrue(manifestIsNewer(activeVersion = 1, remoteVersion = 2))
    }

    @Test
    fun `a manifest of the same dbVersion is not newer`() {
        assertFalse(manifestIsNewer(activeVersion = 2, remoteVersion = 2))
    }

    @Test
    fun `a manifest of a lower dbVersion is not newer`() {
        assertFalse(manifestIsNewer(activeVersion = 3, remoteVersion = 2))
    }

    @Test
    fun `a file whose size and sha256 match the manifest passes`() {
        val expected = ManifestFile(sha256Hex(body), body.size.toLong())
        assertEquals(FileCheck.Ok, checkFile(body, expected))
    }

    @Test
    fun `a file of another size fails before its hash is read`() {
        val expected = ManifestFile(sha256Hex(body), body.size.toLong() + 1)
        assertEquals(FileCheck.WrongSize, checkFile(body, expected))
    }

    @Test
    fun `a file of the right size and another sha256 fails`() {
        val expected = ManifestFile("00".repeat(32), body.size.toLong())
        assertEquals(FileCheck.WrongHash, checkFile(body, expected))
    }

    @Test
    fun `the hash compare ignores the case of the manifest's hex`() {
        val expected = ManifestFile(sha256Hex(body).uppercase(), body.size.toLong())
        assertEquals(FileCheck.Ok, checkFile(body, expected))
    }

    @Test
    fun `sha256Hex is the lowercase digest`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun `a schema 1 manifest naming the database and the english strings parses`() {
        val parsed = parseManifest(manifestJson(), testJson)
        assertTrue(parsed is ParsedManifest.Ok)
        assertEquals(2, (parsed as ParsedManifest.Ok).manifest.dbVersion)
    }

    @Test
    fun `a manifest of another schema is invalid`() {
        assertEquals(ParsedManifest.Invalid, parseManifest(manifestJson(schema = 2), testJson))
    }

    @Test
    fun `a manifest that is not json is invalid`() {
        assertEquals(ParsedManifest.Invalid, parseManifest("<html>".toByteArray(), testJson))
    }

    @Test
    fun `a manifest without controllers json is invalid`() {
        val files = "\"strings/en.json\":{\"sha256\":\"cd\",\"bytes\":3}"
        assertEquals(ParsedManifest.Invalid, parseManifest(manifestJson(files = files), testJson))
    }

    @Test
    fun `a manifest without the english strings is invalid`() {
        val files = "\"controllers.json\":{\"sha256\":\"ab\",\"bytes\":2}"
        assertEquals(ParsedManifest.Invalid, parseManifest(manifestJson(files = files), testJson))
    }

    @Test
    fun `a manifest naming a path outside the database is invalid`() {
        val files =
            "\"controllers.json\":{\"sha256\":\"ab\",\"bytes\":2},\"strings/en.json\":{\"sha256\":\"cd\",\"bytes\":3}," +
                "\"../shared_prefs/x.json\":{\"sha256\":\"ef\",\"bytes\":1}"
        assertEquals(ParsedManifest.Invalid, parseManifest(manifestJson(files = files), testJson))
    }

    @Test
    fun `a manifest of a dbVersion below one is invalid`() {
        assertEquals(ParsedManifest.Invalid, parseManifest(manifestJson(version = 0), testJson))
    }

    @Test
    fun `a manifest naming a file bigger than the cap is invalid`() {
        val files =
            "\"controllers.json\":{\"sha256\":\"ab\",\"bytes\":${CONTROLLER_DB_FILE_MAX_BYTES + 1}}," +
                "\"strings/en.json\":{\"sha256\":\"cd\",\"bytes\":3}"
        assertEquals(ParsedManifest.Invalid, parseManifest(manifestJson(files = files), testJson))
    }

    @Test
    fun `the database file and a locale's strings are files a manifest may name`() {
        assertTrue(isDatabaseFilePath("controllers.json"))
        assertTrue(isDatabaseFilePath("strings/pt-BR.json"))
        assertTrue(isDatabaseFilePath("strings/bs.json"))
        assertFalse(isDatabaseFilePath("strings/../en.json"))
        assertFalse(isDatabaseFilePath("schema/controller-db.schema.json"))
        assertFalse(isDatabaseFilePath("/etc/passwd"))
    }

    @Test
    fun `a schema 1 database of the manifest's dbVersion parses`() {
        val bytes = "{\"schema\":1,\"dbVersion\":2,\"records\":[{\"id\":\"pad\"}]}".toByteArray()
        val parsed = parseControllerDb(bytes, expectedVersion = 2, json = testJson)
        assertTrue(parsed is ParsedDb.Ok)
        assertEquals(
            "pad",
            (parsed as ParsedDb.Ok)
                .db.records
                .single()
                .id,
        )
    }

    @Test
    fun `a database of another schema is invalid`() {
        val bytes = "{\"schema\":2,\"dbVersion\":2,\"records\":[]}".toByteArray()
        assertEquals(ParsedDb.Invalid, parseControllerDb(bytes, expectedVersion = 2, json = testJson))
    }

    @Test
    fun `a database whose dbVersion is not the manifest's is invalid`() {
        val bytes = "{\"schema\":1,\"dbVersion\":3,\"records\":[]}".toByteArray()
        assertEquals(ParsedDb.Invalid, parseControllerDb(bytes, expectedVersion = 2, json = testJson))
    }

    @Test
    fun `a strings file is a flat object of strings`() {
        val parsed = parseStrings("{\"controller.pad.name\":\"Pad\"}".toByteArray(), testJson)
        assertEquals(mapOf("controller.pad.name" to "Pad"), parsed)
    }

    @Test
    fun `a strings file holding anything but strings is refused`() {
        assertEquals(null, parseStrings("{\"controller.pad.name\":3}".toByteArray(), testJson))
        assertEquals(null, parseStrings("[]".toByteArray(), testJson))
    }

    @Test
    fun `the locale of a strings path is its file name`() {
        assertEquals("pt-BR", localeOfStringsPath("strings/pt-BR.json"))
        assertEquals(null, localeOfStringsPath("controllers.json"))
    }

    @Test
    fun `each check result has its contract name`() {
        assertEquals(
            listOf("upToDate", "updated", "offline", "invalid", "off"),
            ControllerDbCheckResult.entries.map { it.wire },
        )
    }
}
