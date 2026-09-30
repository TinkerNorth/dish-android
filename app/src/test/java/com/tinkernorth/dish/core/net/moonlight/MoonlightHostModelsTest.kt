// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonlightHostModelsTest {
    // MOON-D6. A scan meets a host before it has been asked its uniqueid and a typed address meets
    // it after, so an id built from the uniqueid named one host twice. The address is the one name
    // both routes have from the start; the uniqueid stays the witness of which machine answers there.
    @Test
    fun `a host's id is its address, whether or not its uniqueid is known`() {
        val scanned = MoonlightHost(name = "PC", address = "192.168.1.5")
        val typed = scanned.copy(uniqueId = "abc123", manual = true)

        assertEquals("moonlight:192.168.1.5", scanned.id)
        assertEquals(scanned.id, typed.id)
    }

    @Test
    fun `remembered host round-trips to a host`() {
        val remembered =
            RememberedMoonlight(
                id = "moonlight:10.0.0.9",
                name = "PC",
                address = "10.0.0.9",
                httpsPort = 47984,
                uniqueId = "x",
                lastAppId = "42",
                emulatedType = PLAYSTATION,
            )
        val host = remembered.toHost()
        assertEquals("PC", host.name)
        assertEquals("10.0.0.9", host.address)
        assertEquals("x", host.uniqueId)
        assertEquals(remembered.id, host.id)
    }

    @Test
    fun `a record refiled onto an id nothing holds is kept as it is`() {
        assertEquals(REFILED, foldedRecords(filed = null, refiled = REFILED, pinsAgree = true))
    }

    @Test
    fun `a fold keeps the trust either record holds`() {
        assertTrue(foldedRecords(FILED.copy(paired = true), REFILED.copy(paired = false), pinsAgree = true).paired)
        assertTrue(foldedRecords(FILED.copy(paired = false), REFILED.copy(paired = true), pinsAgree = true).paired)
        assertFalse(foldedRecords(FILED.copy(paired = false), REFILED.copy(paired = false), pinsAgree = true).paired)
    }

    // Pins that disagree are two machines, and nothing here can tell which one the pairing was with.
    @Test
    fun `a fold whose pins disagree keeps no trust`() {
        assertFalse(foldedRecords(FILED.copy(paired = true), REFILED.copy(paired = true), pinsAgree = false).paired)
    }

    // The uniqueid is the witness of the machine a pairing was with, so it comes from the record that paired.
    @Test
    fun `a fold takes the uniqueid of the record that paired`() {
        val onlyFiledPaired = foldedRecords(FILED.copy(uniqueId = "old", paired = true), REFILED.copy(paired = false), pinsAgree = true)
        val onlyRefiledPaired = foldedRecords(FILED.copy(uniqueId = "old", paired = false), REFILED.copy(paired = true), pinsAgree = true)

        assertEquals("old", onlyFiledPaired.uniqueId)
        assertEquals("abc", onlyRefiledPaired.uniqueId)
    }

    @Test
    fun `a fold of two records that both paired, or neither, takes the refiled uniqueid, even an empty one`() {
        val filed = FILED.copy(uniqueId = "old")
        val neitherPaired = foldedRecords(filed.copy(paired = false), REFILED.copy(paired = false), pinsAgree = true)

        assertEquals("abc", foldedRecords(filed, REFILED, pinsAgree = true).uniqueId)
        assertEquals("", foldedRecords(filed, REFILED.copy(uniqueId = ""), pinsAgree = true).uniqueId)
        assertEquals("abc", neitherPaired.uniqueId)
    }

    // An app id and its title are one pick, so they come from the same record.
    @Test
    fun `a fold keeps the refiled app pick, or the filed one when the refiled record has none`() {
        val filedPick = FILED.copy(lastAppId = "3", lastAppName = "Desktop")

        val refiledPicked = foldedRecords(filedPick, REFILED.copy(lastAppId = "7", lastAppName = "Steam"), pinsAgree = true)
        val refiledDidNot = foldedRecords(filedPick, REFILED, pinsAgree = true)

        assertEquals("7" to "Steam", refiledPicked.lastAppId to refiledPicked.lastAppName)
        assertEquals("3" to "Desktop", refiledDidNot.lastAppId to refiledDidNot.lastAppName)
    }

    @Test
    fun `emulated Auto resolves to a concrete arrival type, explicit passes through`() {
        assertEquals(
            CONTROLLER_TYPE_XBOX,
            resolveMoonlightEmulatedType(AUTO, sourceHasMotion = false),
        )
        assertEquals(
            CONTROLLER_TYPE_PS,
            resolveMoonlightEmulatedType(PLAYSTATION, sourceHasMotion = false),
        )
        assertTrue(AUTO == 0xFF)
    }

    private companion object {
        val FILED = RememberedMoonlight(id = "moonlight:10.0.0.9", name = "PC", address = "10.0.0.9")
        val REFILED = FILED.copy(name = "Gaming PC", uniqueId = "abc")
    }
}
