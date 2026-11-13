// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.bluetooth

import android.bluetooth.BluetoothDevice
import java.util.Locale
import java.util.UUID

// Valve's GATT service, which a Steam Controller on Bluetooth LE serves beside the HID-over-GATT
// keyboard and mouse Android binds, and its characteristics, as SDL's HIDDeviceBLESteamController
// finds them: 100F6Cxx-1735-4313-B402-38567131E5F3, where xx is 0x35 past a report's id.
private const val VALVE_UUID_FORMAT = "100F6C%02X-1735-4313-B402-38567131E5F3"
private const val VALVE_SERVICE_SHORT = 0x32
private const val REPORT_CHARACTERISTIC_SHORT = 0x34
private const val REPORT_ID_TO_CHARACTERISTIC = 0x35

private fun valveUuid(short: Int): UUID = UUID.fromString(String.format(Locale.ROOT, VALVE_UUID_FORMAT, short))

internal val STEAM_BLE_SERVICE: UUID = valveUuid(VALVE_SERVICE_SHORT)

// The characteristic that takes feature reports, both models' settings among them.
internal val STEAM_BLE_REPORT: UUID = valveUuid(REPORT_CHARACTERISTIC_SHORT)

internal const val STEAM_BLE_VENDOR_ID = 0x28DE

// A write native names by this target goes to the report characteristic; any other target is an
// output report's id (usb_parsers.h BLE_FEATURE_TARGET).
internal const val STEAM_BLE_FEATURE_TARGET = 0

// SDL asks for this MTU before a 2026 controller streams its 45-byte reports; Android reads it as
// a request for data length extensions.
internal const val STEAM_BLE_LARGE_MTU = 517

private const val PRODUCT_2015 = 0x1106
private const val PRODUCT_2026 = 0x1303

// Each model by the input characteristic it serves its reports on, the report id SDL's Android
// bridge puts back in front of each one, and the Bluetooth identity SDL gives it.
internal enum class SteamBleModel(
    val input: UUID,
    val inputReportId: Int,
    val productId: Int,
    val needsLargeMtu: Boolean,
) {
    CONTROLLER_2015(valveUuid(0x33), 0x03, PRODUCT_2015, false),
    CONTROLLER_2026(valveUuid(0x7A), 0x45, PRODUCT_2026, true),
    CONTROLLER_2026_TIMESTAMPED(valveUuid(0x7C), 0x47, PRODUCT_2026, true),
}

private fun modelServing(characteristic: UUID): SteamBleModel? = SteamBleModel.entries.firstOrNull { it.input == characteristic }

// SDL keeps the last input characteristic the Valve service lists.
internal fun steamBleModelOf(characteristics: List<UUID>): SteamBleModel? = characteristics.mapNotNull(::modelServing).lastOrNull()

internal fun steamBleWriteCharacteristic(target: Int): UUID =
    if (target == STEAM_BLE_FEATURE_TARGET) STEAM_BLE_REPORT else valveUuid(target + REPORT_ID_TO_CHARACTERISTIC)

// The names the 2015 and the 2026 controller advertise, as SDL's HIDDeviceManager matches them.
internal fun isSteamControllerName(name: String?): Boolean {
    val isThe2015Name = name == "SteamController"
    val isThe2026Name = name?.startsWith("Steam Ctrl") == true
    return isThe2015Name || isThe2026Name
}

// A Steam Controller always speaks Bluetooth LE, alone or beside classic.
internal fun isSteamControllerDevice(
    name: String?,
    type: Int,
): Boolean {
    val speaksLe = (type and BluetoothDevice.DEVICE_TYPE_LE) != 0
    return speaksLe && isSteamControllerName(name)
}

// The keyboard and mouse Android binds for a Steam Controller on Bluetooth carry its Bluetooth
// identity.
internal fun isBluetoothSteamController(
    vendorId: Int,
    productId: Int,
): Boolean {
    val isValve = vendorId == STEAM_BLE_VENDOR_ID
    val isABluetoothIdentity = productId == PRODUCT_2015 || productId == PRODUCT_2026
    return isValve && isABluetoothIdentity
}
