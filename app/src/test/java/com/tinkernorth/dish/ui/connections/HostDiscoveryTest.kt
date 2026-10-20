// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.connections

import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.system.BluetoothPermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The minSdk; Fire OS 7 (Android 9); Fire OS 8 (Android 11), the last release whose discovery
// results need a location permission; Android 12, where BLUETOOTH_SCAN can disavow location;
// and the targetSdk.
private const val SDK_OLDEST = 24
private const val SDK_FIRE_OS_7 = 28
private const val SDK_LAST_LOCATION_SCAN = 30
private const val SDK_NEARBY_DEVICES = 31
private const val SDK_NEWEST = 37

class HostDiscoveryTest {
    private val nearbyGranted = BluetoothPermissionState(required = true, connectGranted = true, scanGranted = true)
    private val nearbyDenied = BluetoothPermissionState(required = true, connectGranted = true, scanGranted = false)

    @Test
    fun `Android 11 pairs a new host in the system settings, because its discovery results need location`() {
        assertEquals(HostDiscovery.SystemSettings, hostDiscoveryFor(SDK_LAST_LOCATION_SCAN, BluetoothPermissionState.SATISFIED))
    }

    @Test
    fun `a Fire OS 7 tablet pairs a new host in the system settings`() {
        assertEquals(HostDiscovery.SystemSettings, hostDiscoveryFor(SDK_FIRE_OS_7, BluetoothPermissionState.SATISFIED))
    }

    @Test
    fun `the oldest Android Dish runs on pairs a new host in the system settings`() {
        assertEquals(HostDiscovery.SystemSettings, hostDiscoveryFor(SDK_OLDEST, BluetoothPermissionState.SATISFIED))
    }

    @Test
    fun `below Android 12 even a scan grant leaves pairing to the system settings, since location is what is missing`() {
        assertEquals(HostDiscovery.SystemSettings, hostDiscoveryFor(SDK_LAST_LOCATION_SCAN, nearbyGranted))
    }

    @Test
    fun `Android 12 with Nearby devices granted scans in the app`() {
        assertEquals(HostDiscovery.InApp, hostDiscoveryFor(SDK_NEARBY_DEVICES, nearbyGranted))
    }

    @Test
    fun `Android 12 without the Nearby devices grant lists paired hosts and asks for the grant`() {
        assertEquals(HostDiscovery.NeedsNearbyPermission, hostDiscoveryFor(SDK_NEARBY_DEVICES, nearbyDenied))
    }

    @Test
    fun `the newest Android scans in the app like Android 12`() {
        assertEquals(HostDiscovery.InApp, hostDiscoveryFor(SDK_NEWEST, nearbyGranted))
    }

    @Test
    fun `a picker that scans by itself shows no note`() {
        assertNull(hostDiscoveryNote(HostDiscovery.InApp))
    }

    @Test
    fun `a picker missing the grant keeps the Nearby devices note`() {
        assertEquals(R.string.bt_scan_permission_note, hostDiscoveryNote(HostDiscovery.NeedsNearbyPermission))
    }

    @Test
    fun `a picker that cannot scan says where a new host pairs`() {
        assertEquals(R.string.bt_scan_system_settings_note, hostDiscoveryNote(HostDiscovery.SystemSettings))
    }
}
