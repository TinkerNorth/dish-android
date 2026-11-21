// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveControllerDbTest {
    private val bundledPad = record("pad", usb = listOf("045e:028e"), family = "xinput360")
    private val bundled = LoadedControllerDb(db(1, bundledPad), mapOf("en" to mapOf("controller.pad.name" to "Pad")))

    @Test
    fun `with no download the bundled copy is active`() {
        val active = activeControllerDb(bundled, null)
        assertEquals(ControllerDbSource.BUNDLED, active.source)
        assertEquals(1, active.activeVersion)
    }

    @Test
    fun `a download of a greater dbVersion replaces the whole set`() {
        val cached = LoadedControllerDb(db(2, record("other", usb = listOf("0001:0001"))), emptyMap())
        val active = activeControllerDb(bundled, cached)
        assertEquals(ControllerDbSource.CACHE, active.source)
        assertEquals(2, active.activeVersion)
        assertEquals(1, active.bundledVersion)
        assertNull(active.index.recordForUsb(0x045E, 0x028E))
    }

    @Test
    fun `a download no newer than the bundled copy is ignored`() {
        val cached = LoadedControllerDb(db(1, record("other")), emptyMap())
        assertEquals(ControllerDbSource.BUNDLED, activeControllerDb(bundled, cached).source)
    }

    @Test
    fun `an inert served record keeps the bundled input and output for its id with the served issues`() {
        val issue = RecordIssue(status = "open", id = "new-issue", transport = TRANSPORT_USB)
        val inert = record("pad", usb = listOf("045e:028e"), family = "gameCubeUsb", issues = listOf(issue))
        val active = activeControllerDb(bundled, LoadedControllerDb(db(2, inert), emptyMap()))
        val kept = requireNotNull(active.index.recordForUsb(0x045E, 0x028E))
        assertEquals("xinput360", kept.input.protocol.ref)
        assertEquals(listOf("new-issue"), kept.support.issues.map { it.id })
    }

    @Test
    fun `an inert served record with no bundled twin stays inert`() {
        val inert = record("future", usb = listOf("0001:0001"), family = "gameCubeUsb")
        val active = activeControllerDb(bundled, LoadedControllerDb(db(2, inert), emptyMap()))
        assertEquals(0, servedRowsOf(active.index).size)
        assertEquals("future", active.index.recordForUsb(1, 1)?.id)
    }
}
