// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private const val WELL_INSIDE_THE_WRITE_TIMEOUT_MS = 500L
private const val LONG_PAST_A_WRITE_STARTING_MS = 1_000L

// Whether the condition came true before the deadline, so one that never does fails the test
// rather than hanging it.
private fun waitFor(condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + LONG_PAST_A_WRITE_STARTING_MS
    while (!condition() && System.currentTimeMillis() < deadline) Thread.yield()
    return condition()
}

class SteamBleLinkTest {
    private val pad = -1000
    private val notification = byteArrayOf(0x40, 0x10, 0x34)
    private val claims = FakeSteamBleClaims(nextPadId = pad)

    private fun linkTo(
        model: SteamBleModel,
        gatt: FakeSteamGatt = FakeSteamGatt(characteristics = listOf(STEAM_BLE_REPORT, model.input)),
    ): Pair<SteamBleLink, FakeSteamGatt> {
        val link = SteamBleLink(gatt, claims, InlineExecutor())
        assertTrue(link.start())
        return link to gatt
    }

    // Up to the subscription SDL asks for before it registers the pad.
    private fun subscribed(model: SteamBleModel): Pair<SteamBleLink, FakeSteamGatt> {
        val (link, gatt) = linkTo(model)
        link.onConnected()
        link.onServicesDiscovered(ok = true)
        if (model.needsLargeMtu) link.onMtuChanged()
        return link to gatt
    }

    private fun claimed(model: SteamBleModel): Pair<SteamBleLink, FakeSteamGatt> {
        val (link, gatt) = subscribed(model)
        link.onNotificationsEnabled(ok = true)
        return link to gatt
    }

    @Test
    fun `the 2015 controller subscribes to its input characteristic without asking for an MTU`() {
        val (_, gatt) = subscribed(SteamBleModel.CONTROLLER_2015)
        val expected =
            listOf(
                FakeSteamGatt.Call.Connect,
                FakeSteamGatt.Call.DiscoverServices,
                FakeSteamGatt.Call.EnableNotifications(SteamBleModel.CONTROLLER_2015.input),
            )
        assertEquals(expected, gatt.calls)
    }

    @Test
    fun `the 2026 controller asks for the large MTU before it subscribes`() {
        val (link, gatt) = linkTo(SteamBleModel.CONTROLLER_2026)
        link.onConnected()
        link.onServicesDiscovered(ok = true)
        assertEquals(FakeSteamGatt.Call.RequestMtu(STEAM_BLE_LARGE_MTU), gatt.calls.last())
        link.onMtuChanged()
        assertEquals(FakeSteamGatt.Call.EnableNotifications(SteamBleModel.CONTROLLER_2026.input), gatt.calls.last())
    }

    @Test
    fun `the pad is claimed once its notifications are on`() {
        val (link, _) = claimed(SteamBleModel.CONTROLLER_2026_TIMESTAMPED)
        val expected =
            listOf(
                FakeSteamBleClaims.Call.Attach(SteamBleModel.CONTROLLER_2026_TIMESTAMPED),
                FakeSteamBleClaims.Call.Start(pad),
            )
        assertEquals(expected, claims.calls)
        assertEquals(SteamBleStage.CLAIMED, link.stage)
    }

    @Test
    fun `a notification of the input characteristic reaches the decoder once the pad is claimed`() {
        val (link, _) = claimed(SteamBleModel.CONTROLLER_2015)
        link.onNotification(SteamBleModel.CONTROLLER_2015.input, notification)
        assertEquals(listOf(FakeSteamBleClaims.Call.Report(pad, notification.toList())), claims.reports())
    }

    @Test
    fun `a notification before the claim is not forwarded`() {
        val (link, _) = subscribed(SteamBleModel.CONTROLLER_2015)
        link.onNotification(SteamBleModel.CONTROLLER_2015.input, notification)
        assertTrue(claims.reports().isEmpty())
    }

    @Test
    fun `a notification of another characteristic is not forwarded`() {
        val (link, _) = claimed(SteamBleModel.CONTROLLER_2015)
        link.onNotification(STEAM_BLE_REPORT, notification)
        assertTrue(claims.reports().isEmpty())
    }

    @Test
    fun `a lost link ends the claim without a word to the pad and closes the client`() {
        val (link, gatt) = claimed(SteamBleModel.CONTROLLER_2015)
        link.onDisconnected()
        assertEquals(listOf(FakeSteamBleClaims.Call.Detach(pad, restore = false)), claims.detaches())
        assertEquals(FakeSteamGatt.Call.Close, gatt.calls.last())
        assertFalse(link.isLive)
        link.onNotification(SteamBleModel.CONTROLLER_2015.input, notification)
        assertTrue(claims.reports().isEmpty())
    }

    @Test
    fun `a link that is lost twice ends its claim once`() {
        val (link, _) = claimed(SteamBleModel.CONTROLLER_2015)
        link.onDisconnected()
        link.release()
        assertEquals(1, claims.detaches().size)
    }

    @Test
    fun `a link lost before the claim has no claim to end`() {
        val (link, gatt) = subscribed(SteamBleModel.CONTROLLER_2015)
        link.onDisconnected()
        assertTrue(claims.calls.isEmpty())
        assertEquals(FakeSteamGatt.Call.Close, gatt.calls.last())
    }

    @Test
    fun `a controller without the Valve service is closed without a claim`() {
        val (link, gatt) = linkTo(SteamBleModel.CONTROLLER_2015, FakeSteamGatt(characteristics = listOf(STEAM_BLE_REPORT)))
        link.onConnected()
        link.onServicesDiscovered(ok = true)
        assertFalse(link.isLive)
        assertEquals(FakeSteamGatt.Call.Close, gatt.calls.last())
        assertTrue(claims.calls.isEmpty())
    }

    @Test
    fun `a refused subscription ends the link without a claim`() {
        val (link, _) = subscribed(SteamBleModel.CONTROLLER_2015)
        link.onNotificationsEnabled(ok = false)
        assertFalse(link.isLive)
        assertTrue(claims.calls.isEmpty())
    }

    @Test
    fun `a pad native refuses ends the link`() {
        claims.nextPadId = NO_STEAM_BLE_PAD
        val (link, _) = claimed(SteamBleModel.CONTROLLER_2015)
        assertFalse(link.isLive)
        assertTrue(claims.detaches().isEmpty())
    }

    @Test
    fun `a pad whose start fails is given back restored over the live link`() {
        claims.starts = false
        val (link, gatt) = claimed(SteamBleModel.CONTROLLER_2015)
        assertEquals(listOf(FakeSteamBleClaims.Call.Detach(pad, restore = true)), claims.detaches())
        assertFalse(link.isLive)
        assertEquals(FakeSteamGatt.Call.Close, gatt.calls.last())
    }

    @Test
    fun `the pad's writes go to the report characteristic and wait for the controller's answer`() {
        claims.startWrites = listOf(byteArrayOf(0x01, 0x02))
        val (_, gatt) = claimed(SteamBleModel.CONTROLLER_2015)
        assertEquals(listOf(true), claims.startAnswers)
        assertEquals(FakeSteamGatt.Call.Write(STEAM_BLE_REPORT, listOf<Byte>(0x01, 0x02)), gatt.calls.last())
    }

    @Test
    fun `an output report's write goes to its own characteristic`() {
        val (link, gatt) = claimed(SteamBleModel.CONTROLLER_2026)
        assertTrue(link.write(0x80, byteArrayOf(0x00)))
        assertEquals(steamBleWriteCharacteristic(0x80), (gatt.calls.last() as FakeSteamGatt.Call.Write).characteristic)
    }

    @Test
    fun `a write the controller refuses fails`() {
        val (link, gatt) = claimed(SteamBleModel.CONTROLLER_2026)
        gatt.writeAnswer = false
        assertFalse(link.write(0x80, byteArrayOf(0x00)))
    }

    @Test
    fun `a write still waiting when the link drops fails at once`() {
        val (link, gatt) = claimed(SteamBleModel.CONTROLLER_2026)
        gatt.writeAnswer = null
        val result = AtomicReference<Boolean>()
        val writer = thread { result.set(link.write(0x80, byteArrayOf(0x00))) }
        val writing = waitFor { gatt.calls.last() is FakeSteamGatt.Call.Write }
        link.onDisconnected()
        writer.join(WELL_INSIDE_THE_WRITE_TIMEOUT_MS)
        assertTrue(writing)
        assertEquals(false, result.get())
    }

    @Test
    fun `a write on an ended link fails at once`() {
        val (link, gatt) = claimed(SteamBleModel.CONTROLLER_2026)
        link.onDisconnected()
        val writesBefore = gatt.calls.size
        assertFalse(link.write(0x80, byteArrayOf(0x00)))
        assertEquals(writesBefore, gatt.calls.size)
    }
}
