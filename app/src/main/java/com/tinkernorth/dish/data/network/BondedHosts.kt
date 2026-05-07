// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import android.bluetooth.BluetoothClass

/**
 * Coarse host kind derived from a Bluetooth Class-of-Device record. CoD is
 * advertised by the remote, so values can be wrong or missing — treat the
 * mapping as a hint, not a gate. The "Other" bucket is everything we can't
 * confidently call a HID host (audio gear, wearables, peripherals, unknown).
 */
enum class BondedHostKind(
    val label: String,
) {
    CONSOLE("Console"),
    COMPUTER("Computer"),
    PHONE("Phone"),
    OTHER("Other"),
}

/**
 * Snapshot of a bonded device as observed from the platform Bluetooth stack.
 * Pulled out of [android.bluetooth.BluetoothDevice] so the filter and the
 * [BondedHostsRepo] can be unit tested without the framework.
 */
data class BondedDeviceSnapshot(
    val mac: String,
    val name: String?,
    val majorClass: Int,
    val minorClass: Int,
)

/**
 * Bonded host as the UI sees it after filtering. [name] always has a value:
 * if the device didn't report one we fall back to the MAC so rows render
 * something the user can recognize.
 */
data class BondedHost(
    val mac: String,
    val name: String,
    val kind: BondedHostKind,
)

object BondedHostFilter {
    /**
     * Collapse the BluetoothClass.Device.Major value into the four buckets the
     * UI cares about. AUDIO_VIDEO covers consoles in practice (PS5/Xbox/Switch
     * report there) — the user can fall back to "Show all" if their host
     * lands somewhere unexpected.
     */
    fun classify(majorClass: Int): BondedHostKind =
        when (majorClass) {
            BluetoothClass.Device.Major.COMPUTER -> BondedHostKind.COMPUTER
            BluetoothClass.Device.Major.PHONE -> BondedHostKind.PHONE
            BluetoothClass.Device.Major.AUDIO_VIDEO -> BondedHostKind.CONSOLE
            else -> BondedHostKind.OTHER
        }

    /**
     * Treat the kind as a "likely host" — i.e. show in the default filtered
     * tier. OTHER is hidden behind the Show all toggle. Pure mirror of
     * [classify] but kept explicit for readability at call sites.
     */
    fun isLikelyHost(kind: BondedHostKind): Boolean = kind != BondedHostKind.OTHER

    /**
     * Drop devices that obviously aren't gamepad hosts even if the user toggles
     * Show all: paired controllers, headsets, wearables, etc. The filter is
     * intentionally narrow — when in doubt, show the device. CoD is unreliable
     * enough that hiding things aggressively just makes the list feel broken.
     */
    fun isExcludedAccessory(majorClass: Int): Boolean =
        when (majorClass) {
            BluetoothClass.Device.Major.PERIPHERAL,
            BluetoothClass.Device.Major.WEARABLE,
            BluetoothClass.Device.Major.TOY,
            BluetoothClass.Device.Major.HEALTH,
            BluetoothClass.Device.Major.IMAGING,
            -> true
            else -> false
        }
}
