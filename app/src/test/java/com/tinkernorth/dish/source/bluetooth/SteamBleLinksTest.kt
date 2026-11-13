// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PAD_A = "AA:BB:CC:DD:EE:01"
private const val PAD_B = "AA:BB:CC:DD:EE:02"

private class FakeRadio(
    var paired: List<String> = emptyList(),
) : SteamBleRadio {
    val opened = mutableMapOf<String, MutableList<FakeSteamGatt>>()

    override fun pairedSteamControllers(): List<String> = paired

    override fun gattFor(address: String): SteamGatt {
        val gatt = FakeSteamGatt(characteristics = listOf(SteamBleModel.CONTROLLER_2015.input))
        opened.getOrPut(address, ::mutableListOf) += gatt
        return gatt
    }

    fun clientsOf(address: String): List<FakeSteamGatt> = opened[address].orEmpty()
}

private class RefusingRadio : SteamBleRadio {
    override fun pairedSteamControllers(): List<String> = listOf(PAD_A)

    override fun gattFor(address: String): SteamGatt = FakeSteamGatt(connects = false)
}

class SteamBleLinksTest {
    private val radio = FakeRadio(paired = listOf(PAD_A, PAD_B))
    private val claims = FakeSteamBleClaims()
    private val links = SteamBleLinks(radio, claims, InlineExecutor())

    private fun claim(address: String) {
        val link = links.linkOf(address) ?: error("no link to $address")
        link.onConnected()
        link.onServicesDiscovered(ok = true)
        link.onNotificationsEnabled(ok = true)
    }

    @Test
    fun `watching connects every paired Steam Controller`() {
        links.onStatus(SteamBleStatus.WATCHING)
        assertEquals(1, radio.clientsOf(PAD_A).size)
        assertEquals(1, radio.clientsOf(PAD_B).size)
    }

    @Test
    fun `a refused permission connects nothing`() {
        links.onStatus(SteamBleStatus.PERMISSION_REFUSED)
        links.onSteamControllerConnected(PAD_A)
        assertTrue(radio.opened.isEmpty())
        assertEquals(SteamBleStatus.PERMISSION_REFUSED, links.status)
    }

    @Test
    fun `a permission taken away ends every claim`() {
        links.onStatus(SteamBleStatus.WATCHING)
        claim(PAD_A)
        links.onStatus(SteamBleStatus.PERMISSION_REFUSED)
        assertEquals(listOf(FakeSteamBleClaims.Call.Detach(claims.nextPadId, restore = false)), claims.detaches())
        assertNull(links.linkOf(PAD_A))
    }

    @Test
    fun `Bluetooth turned off ends every claim and every link`() {
        links.onStatus(SteamBleStatus.WATCHING)
        claim(PAD_A)
        links.onStatus(SteamBleStatus.BLUETOOTH_OFF)
        assertEquals(1, claims.detaches().size)
        assertEquals(
            FakeSteamGatt.Call.Close,
            radio
                .clientsOf(PAD_A)
                .single()
                .calls
                .last(),
        )
        assertEquals(
            FakeSteamGatt.Call.Close,
            radio
                .clientsOf(PAD_B)
                .single()
                .calls
                .last(),
        )
    }

    @Test
    fun `a controller that goes to sleep ends its claim`() {
        links.onStatus(SteamBleStatus.WATCHING)
        claim(PAD_A)
        links.onDisconnected(PAD_A)
        assertEquals(1, claims.detaches().size)
        assertNull(links.linkOf(PAD_A))
    }

    @Test
    fun `a controller that wakes again gets a new link and a new claim`() {
        links.onStatus(SteamBleStatus.WATCHING)
        claim(PAD_A)
        val asleep = links.linkOf(PAD_A)
        links.onDisconnected(PAD_A)
        links.onSteamControllerConnected(PAD_A)
        val awake = links.linkOf(PAD_A)
        assertNotSame(asleep, awake)
        claim(PAD_A)
        assertEquals(2, radio.clientsOf(PAD_A).size)
        assertEquals(2, claims.calls.filterIsInstance<FakeSteamBleClaims.Call.Start>().size)
    }

    @Test
    fun `a controller that connects again while its link lives keeps that link`() {
        links.onStatus(SteamBleStatus.WATCHING)
        val held = links.linkOf(PAD_A)
        links.onSteamControllerConnected(PAD_A)
        assertSame(held, links.linkOf(PAD_A))
        assertEquals(1, radio.clientsOf(PAD_A).size)
    }

    @Test
    fun `a link whose own client dropped is replaced on the next connect`() {
        links.onStatus(SteamBleStatus.WATCHING)
        links.linkOf(PAD_A)?.onDisconnected()
        links.onSteamControllerConnected(PAD_A)
        assertEquals(2, radio.clientsOf(PAD_A).size)
        assertTrue(links.linkOf(PAD_A)?.isLive == true)
    }

    @Test
    fun `a client that cannot connect leaves no link`() {
        val held = SteamBleLinks(RefusingRadio(), claims, InlineExecutor())
        held.onStatus(SteamBleStatus.WATCHING)
        assertNull(held.linkOf(PAD_A))
        assertTrue(claims.calls.isEmpty())
    }
}
