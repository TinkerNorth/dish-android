// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.connections

import android.os.Build
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.system.BluetoothPermissionState

/** How the Bluetooth host picker finds a device the phone has not paired with yet. */
enum class HostDiscovery {
    /** Android 12 and newer with Nearby devices granted: the picker scans by itself. */
    InApp,

    /** Android 12 and newer without the grant: paired devices only, and the note asks for it. */
    NeedsNearbyPermission,

    /**
     * Android 11 and older deliver discovery results only to an app holding a location permission,
     * which Dish never asks for: the picker lists paired devices and a new one pairs in the system
     * Bluetooth settings.
     */
    SystemSettings,
}

internal fun hostDiscoveryFor(
    sdkInt: Int,
    permission: BluetoothPermissionState,
): HostDiscovery =
    when {
        sdkInt < Build.VERSION_CODES.S -> HostDiscovery.SystemSettings
        permission.scanMissing -> HostDiscovery.NeedsNearbyPermission
        else -> HostDiscovery.InApp
    }

/** The picker's note for a route, or null when the picker scans by itself. */
@StringRes
internal fun hostDiscoveryNote(discovery: HostDiscovery): Int? =
    when (discovery) {
        HostDiscovery.InApp -> null
        HostDiscovery.NeedsNearbyPermission -> R.string.bt_scan_permission_note
        HostDiscovery.SystemSettings -> R.string.bt_scan_system_settings_note
    }
