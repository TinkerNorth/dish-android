// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.connections

import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.model.DishNotification
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.bluetooth.BtStaleReason
import com.tinkernorth.dish.source.system.BluetoothAdapterState
import com.tinkernorth.dish.source.system.BluetoothPermissionBannerVariant
import com.tinkernorth.dish.source.system.NetworkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class ConnectionsCopyTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `a radio that is on needs no adapter banner`() {
        assertNull(btAdapterBannerFor(BluetoothAdapterState.ON))
    }

    @Test
    fun `a phone without a radio gets the unsupported banner and a radio that is off the off banner`() {
        assertEquals(BtAdapterBanner.UNSUPPORTED, btAdapterBannerFor(BluetoothAdapterState.UNSUPPORTED))
        assertEquals(BtAdapterBanner.OFF, btAdapterBannerFor(BluetoothAdapterState.OFF))
    }

    @Test
    fun `wifi needs no network banner`() {
        assertNull(networkBannerFor(NetworkState.WIFI))
    }

    @Test
    fun `no network and cellular only each get their own banner`() {
        assertEquals(NetworkBanner.NO_NETWORK, networkBannerFor(NetworkState.NONE))
        assertEquals(NetworkBanner.CELLULAR_ONLY, networkBannerFor(NetworkState.CELLULAR))
    }

    @Test
    fun `a missing key asks to re-pair while a removed bond says the host was unpaired`() {
        assertEquals(
            StaleBtCopy(R.string.notif_bt_key_missing_title, R.string.notif_bt_key_missing_body),
            staleBtCopy(BtStaleReason.KEY_MISSING),
        )
        assertEquals(
            StaleBtCopy(R.string.notif_bt_bond_removed_title, R.string.notif_bt_bond_removed_body),
            staleBtCopy(BtStaleReason.BOND_REMOVED),
        )
    }

    @Test
    fun `the connect permission banner warns while the scan permission banner only informs`() {
        val connect = btPermissionBannerCopy(BluetoothPermissionBannerVariant.CONNECT)
        assertEquals(DishNotification.Severity.WARN, connect.severity)
        assertEquals(R.string.notif_bt_permission_title, connect.titleRes)
        assertEquals(R.string.notif_bt_permission_body, connect.bodyRes)
        val scan = btPermissionBannerCopy(BluetoothPermissionBannerVariant.SCAN)
        assertEquals(DishNotification.Severity.INFO, scan.severity)
        assertEquals(R.string.notif_bt_scan_permission_title, scan.titleRes)
        assertEquals(R.string.notif_bt_scan_permission_body, scan.bodyRes)
    }

    @Test
    fun `a registered host session asks the user to pair from the host`() {
        val state = BluetoothGamepadRegistry.SlotState(registered = true, acquiring = true)
        assertEquals(R.string.bt_row_pair_from_host, btConnectingLabelRes(state))
    }

    @Test
    fun `an acquiring session reads acquiring and anything else reads waiting`() {
        assertEquals(R.string.bt_row_acquiring, btConnectingLabelRes(BluetoothGamepadRegistry.SlotState(acquiring = true)))
        assertEquals(R.string.bt_row_waiting, btConnectingLabelRes(BluetoothGamepadRegistry.SlotState()))
    }

    @Test
    fun `no deadline means no seconds left`() {
        assertEquals(0, secondsLeft(untilMs = null, nowMs = NOW_MS))
    }

    @Test
    fun `a deadline already passed clamps to zero`() {
        assertEquals(0, secondsLeft(untilMs = NOW_MS - 1L, nowMs = NOW_MS))
    }

    @Test
    fun `a deadline ahead counts whole seconds down`() {
        assertEquals(2, secondsLeft(untilMs = NOW_MS + 2999L, nowMs = NOW_MS))
    }

    @Test
    fun `a named server gets the named pairing subtitle and an unnamed one the plain subtitle`() {
        assertEquals(R.string.pair_dialog_subtitle_named, pairSubtitleRes("Living Room"))
        assertEquals(R.string.pair_dialog_subtitle, pairSubtitleRes(""))
    }

    @Test
    fun `a screen that never scanned says so`() {
        assertEquals(DiscoveryEmptyCopy.NeverScanned, discoveryEmptyCopy(lastScanAtMs = null, zone = utc))
    }

    @Test
    fun `a scan with no results names the clock time it ran`() {
        assertEquals(DiscoveryEmptyCopy.NoResultsAt("13:07"), discoveryEmptyCopy(THIRTEEN_OH_SEVEN_UTC_MS, utc))
    }

    @Test
    fun `the clock is two digits each for hour and minute`() {
        assertEquals("00:00", formatClock(0L, utc))
        assertEquals("13:07", formatClock(THIRTEEN_OH_SEVEN_UTC_MS, utc))
    }

    private companion object {
        const val NOW_MS = 1_000_000L
        const val THIRTEEN_OH_SEVEN_UTC_MS = (13 * 3600 + 7 * 60) * 1000L
    }
}
