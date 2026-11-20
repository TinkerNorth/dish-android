// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControllerStringsTest {
    private val bundled =
        mapOf(
            "en" to mapOf("controller.pad.name" to "Pad", "family.hid.name" to "Generic HID gamepad"),
            "de" to mapOf("family.hid.name" to "Generisches HID-Gamepad"),
        )

    private val served =
        mapOf(
            "en" to
                mapOf(
                    "controller.pad.name" to "Pad (renamed)",
                    "controller.new.name" to "New pad",
                    "family.hid.name" to "HID",
                ),
            "de" to mapOf("controller.new.name" to "Neues Pad"),
        )

    private val withCache = ControllerStrings(bundled, served, setOf("pad"))
    private val bundledOnly = ControllerStrings(bundled, null, setOf("pad"))

    @Test
    fun `a key in the locale is the locale's`() {
        assertEquals("Generisches HID-Gamepad", bundledOnly.lookup("family.hid.name", "de"))
    }

    @Test
    fun `a key missing from the locale falls back to english`() {
        assertEquals("Pad", bundledOnly.lookup("controller.pad.name", "de"))
    }

    @Test
    fun `a key in no file is null`() {
        assertNull(bundledOnly.lookup("controller.missing.name", "de"))
    }

    @Test
    fun `the bundled string for an id the app ships wins over the served one`() {
        assertEquals("Pad", withCache.lookup("controller.pad.name", "en"))
        assertEquals("Generic HID gamepad", withCache.lookup("family.hid.name", "en"))
    }

    @Test
    fun `a served string fills in for an id the app does not know`() {
        assertEquals("New pad", withCache.lookup("controller.new.name", "en"))
        assertEquals("Neues Pad", withCache.lookup("controller.new.name", "de"))
    }

    @Test
    fun `a served translation fills a locale the bundled copy lacks`() {
        val translated = served + ("fr" to mapOf("controller.pad.name" to "Manette"))
        val strings = ControllerStrings(bundled, translated, setOf("pad"))
        assertEquals("Manette", strings.lookup("controller.pad.name", "fr"))
    }

    @Test
    fun `brazilian portuguese is its own file and other portuguese falls back to it`() {
        assertEquals("pt-BR", stringsLocaleFor("pt", "BR"))
        assertEquals("pt-BR", stringsLocaleFor("pt", "PT"))
        assertEquals("de", stringsLocaleFor("de", "AT"))
        assertEquals("bs", stringsLocaleFor("bs", ""))
        assertEquals("en", stringsLocaleFor("ja", "JP"))
    }

    @Test
    fun `every device locale the app is read in lands on its file`() {
        assertEquals("bs", stringsLocaleFor("bs", "BA"))
        assertEquals("pt-BR", stringsLocaleFor("pt", "BR"))
        assertEquals("fr", stringsLocaleFor("fr", "CA"))
        assertEquals("es", stringsLocaleFor("es", "419"))
        assertEquals("en", stringsLocaleFor("en", "GB"))
    }

    @Test
    fun `the first language in the device list that the app ships is the one the ui shows`() {
        assertEquals("de", stringsLocaleForList(listOf("ja" to "JP", "de" to "AT")))
        assertEquals("en", stringsLocaleForList(listOf("en" to "GB", "de" to "DE")))
        assertEquals("pt-BR", stringsLocaleForList(listOf("pt" to "PT")))
        assertEquals("en", stringsLocaleForList(listOf("ja" to "JP", "ko" to "KR")))
        assertEquals("en", stringsLocaleForList(emptyList()))
    }
}
