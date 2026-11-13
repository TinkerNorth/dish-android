// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.bluetooth

import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.system.BluetoothAdapterState
import java.util.concurrent.Executor

/** Whether the app can reach the Steam Controllers paired to the phone over Bluetooth LE. */
enum class SteamBleStatus {
    UNSUPPORTED,
    BLUETOOTH_OFF,
    PERMISSION_REFUSED,
    WATCHING,
}

internal fun steamBleStatusOf(
    hasBluetoothLe: Boolean,
    adapter: BluetoothAdapterState,
    connectMissing: Boolean,
): SteamBleStatus {
    if (!hasBluetoothLe) return SteamBleStatus.UNSUPPORTED
    return when (adapter) {
        BluetoothAdapterState.UNSUPPORTED -> SteamBleStatus.UNSUPPORTED
        BluetoothAdapterState.OFF -> SteamBleStatus.BLUETOOTH_OFF
        BluetoothAdapterState.ON -> if (connectMissing) SteamBleStatus.PERMISSION_REFUSED else SteamBleStatus.WATCHING
    }
}

// Without the permission no paired device can be listed, but Android still binds a Steam
// Controller's keyboard and mouse, which carry its Bluetooth identity: that is the pad the refusal
// keeps from being a controller.
internal fun steamBlePermissionNeeded(
    status: SteamBleStatus,
    ignored: Collection<PhysicalGamepadRegistry.IgnoredInputDevice>,
): Boolean {
    val refused = status == SteamBleStatus.PERMISSION_REFUSED
    return refused && ignored.any { isBluetoothSteamController(it.vendorId, it.productId) }
}

/** The paired Steam Controllers, and a GATT client for one. */
internal interface SteamBleRadio {
    fun pairedSteamControllers(): List<String>

    fun gattFor(address: String): SteamGatt?
}

/**
 * The link of every paired Steam Controller, by address: one for each that is there while the app
 * may reach them, none otherwise. Confined to the main thread, where the status and the ACL
 * broadcasts arrive.
 */
internal class SteamBleLinks(
    private val radio: SteamBleRadio,
    private val claims: SteamBleClaims,
    private val work: Executor,
) {
    private val links = HashMap<String, SteamBleLink>()

    var status: SteamBleStatus = SteamBleStatus.UNSUPPORTED
        private set

    fun onStatus(next: SteamBleStatus) {
        status = next
        when (next) {
            SteamBleStatus.WATCHING -> radio.pairedSteamControllers().forEach(::connect)
            SteamBleStatus.UNSUPPORTED, SteamBleStatus.BLUETOOTH_OFF, SteamBleStatus.PERMISSION_REFUSED -> releaseAll()
        }
    }

    // A paired Steam Controller woke or came in range: SDL reconnects on ACL_CONNECTED.
    fun onSteamControllerConnected(address: String) {
        if (status == SteamBleStatus.WATCHING) connect(address)
    }

    fun onDisconnected(address: String) {
        links.remove(address)?.release()
    }

    fun linkOf(address: String): SteamBleLink? = links[address]

    private fun connect(address: String) {
        val held = links[address]
        if (held?.isLive == true) return
        val gatt = radio.gattFor(address) ?: return
        val link = SteamBleLink(gatt, claims, work)
        links[address] = link
        if (!link.start()) links.remove(address)
    }

    private fun releaseAll() {
        links.values.forEach(SteamBleLink::release)
        links.clear()
    }
}
