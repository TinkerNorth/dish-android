// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.bluetooth

import android.bluetooth.BluetoothDevice
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.system.BluetoothAdapterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

// SDL's HIDDeviceBLESteamController: the Valve service and its characteristics.
private val VALVE_SERVICE = UUID.fromString("100F6C32-1735-4313-B402-38567131E5F3")
private val INPUT_2015 = UUID.fromString("100F6C33-1735-4313-B402-38567131E5F3")
private val REPORT = UUID.fromString("100F6C34-1735-4313-B402-38567131E5F3")
private val INPUT_2026 = UUID.fromString("100F6C7A-1735-4313-B402-38567131E5F3")
private val INPUT_2026_TIMESTAMPED = UUID.fromString("100F6C7C-1735-4313-B402-38567131E5F3")
private val RUMBLE_2026 = UUID.fromString("100F6CB5-1735-4313-B402-38567131E5F3")

private fun keyboardOf(
    vendorId: Int,
    productId: Int,
): PhysicalGamepadRegistry.IgnoredInputDevice =
    PhysicalGamepadRegistry.IgnoredInputDevice(
        id = 7,
        name = "SteamController",
        vendorId = vendorId,
        productId = productId,
        sources = 0,
        keyboardType = 0,
    )

class SteamBleModelTest {
    @Test
    fun `the service and the report characteristic are SDL's`() {
        assertEquals(VALVE_SERVICE, STEAM_BLE_SERVICE)
        assertEquals(REPORT, STEAM_BLE_REPORT)
    }

    @Test
    fun `each input characteristic names its model, its report id and its Bluetooth identity`() {
        assertEquals(SteamBleModel.CONTROLLER_2015, steamBleModelOf(listOf(REPORT, INPUT_2015)))
        assertEquals(0x03, SteamBleModel.CONTROLLER_2015.inputReportId)
        assertEquals(0x1106, SteamBleModel.CONTROLLER_2015.productId)
        assertEquals(SteamBleModel.CONTROLLER_2026, steamBleModelOf(listOf(INPUT_2026)))
        assertEquals(0x45, SteamBleModel.CONTROLLER_2026.inputReportId)
        assertEquals(SteamBleModel.CONTROLLER_2026_TIMESTAMPED, steamBleModelOf(listOf(INPUT_2026_TIMESTAMPED)))
        assertEquals(0x47, SteamBleModel.CONTROLLER_2026_TIMESTAMPED.inputReportId)
        assertEquals(0x1303, SteamBleModel.CONTROLLER_2026_TIMESTAMPED.productId)
    }

    @Test
    fun `only the 2026 controller needs the large MTU`() {
        assertFalse(SteamBleModel.CONTROLLER_2015.needsLargeMtu)
        assertTrue(SteamBleModel.CONTROLLER_2026.needsLargeMtu)
        assertTrue(SteamBleModel.CONTROLLER_2026_TIMESTAMPED.needsLargeMtu)
    }

    @Test
    fun `the last input characteristic the service lists wins, as in SDL`() {
        assertEquals(SteamBleModel.CONTROLLER_2026_TIMESTAMPED, steamBleModelOf(listOf(INPUT_2026, INPUT_2026_TIMESTAMPED)))
        assertEquals(SteamBleModel.CONTROLLER_2026, steamBleModelOf(listOf(INPUT_2026_TIMESTAMPED, INPUT_2026)))
    }

    @Test
    fun `a service without an input characteristic is no Steam Controller`() {
        assertNull(steamBleModelOf(listOf(REPORT, RUMBLE_2026)))
        assertNull(steamBleModelOf(emptyList()))
    }

    @Test
    fun `a feature report goes to the report characteristic and an output report 0x35 past its id`() {
        assertEquals(REPORT, steamBleWriteCharacteristic(STEAM_BLE_FEATURE_TARGET))
        assertEquals(RUMBLE_2026, steamBleWriteCharacteristic(0x80))
    }

    @Test
    fun `both controllers' advertised names are Steam Controllers on LE`() {
        assertTrue(isSteamControllerDevice("SteamController", BluetoothDevice.DEVICE_TYPE_LE))
        assertTrue(isSteamControllerDevice("Steam Ctrl 1A2B", BluetoothDevice.DEVICE_TYPE_DUAL))
        assertFalse(isSteamControllerDevice("SteamController", BluetoothDevice.DEVICE_TYPE_CLASSIC))
        assertFalse(isSteamControllerDevice("Xbox Wireless Controller", BluetoothDevice.DEVICE_TYPE_LE))
        assertFalse(isSteamControllerDevice(null, BluetoothDevice.DEVICE_TYPE_LE))
    }

    @Test
    fun `the Bluetooth identities are Valve's 0x1106 and 0x1303`() {
        assertTrue(isBluetoothSteamController(STEAM_BLE_VENDOR_ID, 0x1106))
        assertTrue(isBluetoothSteamController(STEAM_BLE_VENDOR_ID, 0x1303))
        assertFalse(isBluetoothSteamController(STEAM_BLE_VENDOR_ID, 0x1102))
        assertFalse(isBluetoothSteamController(0x045E, 0x1106))
    }

    @Test
    fun `a phone without Bluetooth LE has no Steam Controllers to reach`() {
        assertEquals(SteamBleStatus.UNSUPPORTED, steamBleStatusOf(false, BluetoothAdapterState.ON, connectMissing = false))
        assertEquals(SteamBleStatus.UNSUPPORTED, steamBleStatusOf(true, BluetoothAdapterState.UNSUPPORTED, connectMissing = false))
    }

    @Test
    fun `Bluetooth off is its own state whatever the permission`() {
        assertEquals(SteamBleStatus.BLUETOOTH_OFF, steamBleStatusOf(true, BluetoothAdapterState.OFF, connectMissing = true))
    }

    @Test
    fun `a refused permission shows its state`() {
        assertEquals(SteamBleStatus.PERMISSION_REFUSED, steamBleStatusOf(true, BluetoothAdapterState.ON, connectMissing = true))
        assertEquals(SteamBleStatus.WATCHING, steamBleStatusOf(true, BluetoothAdapterState.ON, connectMissing = false))
    }

    @Test
    fun `the refusal is told when a Steam Controller on Bluetooth is there to be refused`() {
        val steamKeyboard = listOf(keyboardOf(STEAM_BLE_VENDOR_ID, 0x1106))
        assertTrue(steamBlePermissionNeeded(SteamBleStatus.PERMISSION_REFUSED, steamKeyboard))
        assertFalse(steamBlePermissionNeeded(SteamBleStatus.WATCHING, steamKeyboard))
        assertFalse(steamBlePermissionNeeded(SteamBleStatus.BLUETOOTH_OFF, steamKeyboard))
        val otherKeyboard = listOf(keyboardOf(0x046D, 0xC52B))
        assertFalse(steamBlePermissionNeeded(SteamBleStatus.PERMISSION_REFUSED, otherKeyboard))
    }
}
