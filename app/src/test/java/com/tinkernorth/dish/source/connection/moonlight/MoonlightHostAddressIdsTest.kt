// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.content.Context
import com.tinkernorth.dish.core.net.moonlight.MoonlightIdentity
import com.tinkernorth.dish.core.net.moonlight.RememberedMoonlight
import com.tinkernorth.dish.repository.RememberedMoonlightRepository
import com.tinkernorth.dish.repository.SatellitePinRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MOON-D6. A host is filed under its address, the one name a scanned host and a typed-in one share
 * from first contact. Records and pins an earlier version filed under the host's uniqueid are moved
 * there when the app starts, before any binding exists to point at either name: bindings are not
 * kept across a restart.
 */
class MoonlightHostAddressIdsTest {
    private val rows = linkedMapOf<String, RememberedMoonlight>()
    private val pinned = linkedMapOf<String, String>()

    private val store = mockk<RememberedMoonlightRepository>(relaxed = true)
    private val pins = mockk<SatellitePinRepository>()

    init {
        every { store.get(any()) } answers { rows[firstArg<String>()] }
        every { store.all() } answers { rows.values.toList() }
        every { store.entries } returns MutableStateFlow(emptyList())
        every { store.put(any<RememberedMoonlight>()) } answers {
            val row = firstArg<RememberedMoonlight>()
            rows[row.id] = row
        }
        every { store.remove(any<String>()) } answers { forgetRow(firstArg()) }
        every { pins.pinnedFingerprint(any()) } answers { pinned[firstArg()] }
        every { pins.pin(any(), any()) } answers { pinned[firstArg()] = secondArg() }
        every { pins.forget(any()) } answers { forgetPin(firstArg()) }
    }

    private fun startTheApp() {
        val dispatcher = StandardTestDispatcher()
        MoonlightConnectionManager(
            context = mockk<Context>(relaxed = true),
            scope = TestScope(dispatcher),
            ioDispatcher = dispatcher,
            discovery = mockk(relaxed = true),
            gateway = MoonlightHttpGateway(mockk<MoonlightIdentity>(relaxed = true), pins),
            identity = mockk<MoonlightIdentity>(relaxed = true),
            store = store,
            bindings = mockk(relaxed = true),
        )
    }

    private fun remember(record: RememberedMoonlight) {
        rows[record.id] = record
    }

    private fun forgetRow(id: String) {
        rows.remove(id)
    }

    private fun forgetPin(id: String) {
        pinned.remove(id)
    }

    @Test
    fun `a host remembered under its uniqueid is filed under its address`() {
        remember(byUniqueId.copy(paired = true, lastAppId = "7", lastAppName = "Steam"))

        startTheApp()

        assertEquals(listOf(ADDRESS_ID), rows.keys.toList())
        val record = rows.getValue(ADDRESS_ID)
        assertEquals(UNIQUE_ID, record.uniqueId)
        assertTrue(record.paired)
        assertEquals("7", record.lastAppId)
        assertEquals("Steam", record.lastAppName)
    }

    // The two rows MOON-D6 made of one host: found by a scan, and typed in by its address.
    @Test
    fun `a host remembered both ways becomes one record that keeps its trust and its app`() {
        remember(byAddress.copy(paired = true))
        remember(byUniqueId.copy(paired = false, lastAppId = "7", lastAppName = "Steam"))

        startTheApp()

        assertEquals(listOf(ADDRESS_ID), rows.keys.toList())
        val record = rows.getValue(ADDRESS_ID)
        assertTrue("trust from either record", record.paired)
        assertEquals("7", record.lastAppId)
        assertEquals("Steam", record.lastAppName)
    }

    // The uniqueid, the pin and the pairing describe one machine, the one this device paired with, so
    // they come from the record that paired. Taking them from both could claim a pairing with one
    // machine under the certificate or the uniqueid of another.
    @Test
    fun `the uniqueid comes from the record that paired`() {
        remember(byAddress.copy(uniqueId = OTHER_UNIQUE_ID, paired = true))
        remember(byUniqueId.copy(paired = false))

        startTheApp()

        assertEquals(OTHER_UNIQUE_ID, rows.getValue(ADDRESS_ID).uniqueId)
    }

    @Test
    fun `the pin comes from the record that paired, even when it holds none`() {
        remember(byAddress.copy(paired = true))
        remember(byUniqueId.copy(paired = false))
        pinned[UNIQUE_ID_KEY] = "bb22"

        startTheApp()

        assertEquals(emptyMap<String, String>(), pinned)
    }

    // With neither record paired there is no pairing to follow, and the record filed by uniqueid is
    // the one an earlier version kept up to date.
    @Test
    fun `with neither record paired the uniqueid and the pin come from the record filed by uniqueid`() {
        remember(byAddress.copy(uniqueId = OTHER_UNIQUE_ID, paired = false))
        remember(byUniqueId.copy(paired = false))
        pinned[ADDRESS_ID] = "aa11"

        startTheApp()

        assertEquals(UNIQUE_ID, rows.getValue(ADDRESS_ID).uniqueId)
        assertEquals(emptyMap<String, String>(), pinned)
    }

    @Test
    fun `an app picked under the address outlives the fold`() {
        remember(byAddress.copy(lastAppId = "3", lastAppName = "Desktop"))
        remember(byUniqueId)

        startTheApp()

        assertEquals("3", rows.getValue(ADDRESS_ID).lastAppId)
        assertEquals("Desktop", rows.getValue(ADDRESS_ID).lastAppName)
    }

    @Test
    fun `the pinned certificate moves with the host`() {
        remember(byUniqueId)
        pinned[UNIQUE_ID_KEY] = "ab12"

        startTheApp()

        assertEquals(mapOf(ADDRESS_ID to "ab12"), pinned)
    }

    // Two certificates are two machines, or one that changed its certificate, and nothing here can
    // tell which of them answers at the address now. The next pairing settles it.
    @Test
    fun `two pins that disagree leave the host with neither, and not paired`() {
        remember(byAddress.copy(paired = true))
        remember(byUniqueId.copy(paired = true))
        pinned[ADDRESS_ID] = "aa11"
        pinned[UNIQUE_ID_KEY] = "bb22"

        startTheApp()

        assertEquals(emptyMap<String, String>(), pinned)
        assertFalse(rows.getValue(ADDRESS_ID).paired)
    }

    @Test
    fun `two pins that agree are kept, and so is the pairing`() {
        remember(byAddress.copy(paired = true))
        remember(byUniqueId.copy(paired = true))
        pinned[ADDRESS_ID] = "aa11"
        pinned[UNIQUE_ID_KEY] = "aa11"

        startTheApp()

        assertEquals(mapOf(ADDRESS_ID to "aa11"), pinned)
        assertTrue(rows.getValue(ADDRESS_ID).paired)
    }

    // A pin at the address with no record behind it was written on first use by a probe of whoever
    // answered there. It used to count as a second opinion, and disagreeing with the record's own
    // pin, the one a pairing proved, cost the host both.
    @Test
    fun `a record filed under its uniqueid alone keeps its own pin over a stray one at its address`() {
        remember(byUniqueId.copy(paired = true))
        pinned[UNIQUE_ID_KEY] = "bb22"
        pinned[ADDRESS_ID] = "aa11"

        startTheApp()

        assertEquals(mapOf(ADDRESS_ID to "bb22"), pinned)
        assertTrue(rows.getValue(ADDRESS_ID).paired)
    }

    @Test
    fun `a host already filed under its address is left as it is`() {
        remember(byAddress.copy(uniqueId = UNIQUE_ID, paired = true))
        pinned[ADDRESS_ID] = "aa11"

        startTheApp()

        verify(exactly = 0) { store.put(any<RememberedMoonlight>()) }
        verify(exactly = 0) { store.remove(any<String>()) }
        assertEquals(mapOf(ADDRESS_ID to "aa11"), pinned)
    }

    private companion object {
        const val ADDRESS = "192.168.68.98"
        const val UNIQUE_ID = "host-1"
        const val OTHER_UNIQUE_ID = "host-2"
        const val ADDRESS_ID = "moonlight:$ADDRESS"

        // The id an earlier version filed a host under once it knew the host's uniqueid.
        const val UNIQUE_ID_KEY = "moonlight:uid:$UNIQUE_ID"

        val byAddress = RememberedMoonlight(id = ADDRESS_ID, name = "PC", address = ADDRESS)
        val byUniqueId = RememberedMoonlight(id = UNIQUE_ID_KEY, name = "PC", address = ADDRESS, uniqueId = UNIQUE_ID)
    }
}
