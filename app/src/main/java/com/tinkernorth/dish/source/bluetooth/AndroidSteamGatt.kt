// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.UUID

private const val TAG = "SteamBleGatt"

// The Client Characteristic Configuration descriptor a notification is switched on through.
private val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

// SDL's: notify where the characteristic notifies, indicate where it only indicates.
private fun subscriptionValueFor(properties: Int): ByteArray? {
    val notifies = (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
    val indicates = (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    if (notifies) return BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    if (indicates) return BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
    return null
}

/**
 * The Android GATT client behind [SteamGatt]: one BluetoothGatt to one Steam Controller, a client
 * of the app's own beside the system's HID-over-GATT one, connected over LE as SDL connects it.
 * Its callbacks reach the link on a binder thread, one at a time.
 */
internal class AndroidSteamGatt(
    private val context: Context,
    private val device: BluetoothDevice,
) : BluetoothGattCallback(),
    SteamGatt {
    @Volatile private var gatt: BluetoothGatt? = null

    @Volatile private var events: SteamGattEvents? = null

    override fun connect(events: SteamGattEvents): Boolean {
        this.events = events
        gatt = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) legacyConnect() else connectOverLe()
        return gatt != null
    }

    // Over LE, as SDL connects, since a dual-mode controller would otherwise be reached over
    // classic; a direct connection, not a background one.
    @RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
    private fun connectOverLe(): BluetoothGatt? {
        val settings =
            BluetoothGattConnectionSettings
                .Builder()
                .setTransport(BluetoothDevice.TRANSPORT_LE)
                .setAutoConnectEnabled(false)
                .build()
        return try {
            device.connectGatt(settings, ::runInPlace, this)
        } catch (e: SecurityException) {
            Log.w(TAG, "connect refused without BLUETOOTH_CONNECT: ${e.message}")
            null
        }
    }

    // Marker: before 37 a GATT client connects only through the Context overloads, which 37
    // deprecates for connectGatt(settings, executor, callback) above. The right fix is a minSdk
    // of 37; until then the deprecated call lives here and nowhere else.
    @Suppress("DEPRECATION")
    private fun legacyConnect(): BluetoothGatt? =
        try {
            device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.w(TAG, "connect refused without BLUETOOTH_CONNECT: ${e.message}")
            null
        }

    // The callbacks stay on the binder thread that brings them, one at a time, as before 37.
    private fun runInPlace(command: Runnable) = command.run()

    override fun discoverServices(): Boolean {
        val client = gatt ?: return false
        return try {
            client.discoverServices()
        } catch (e: SecurityException) {
            Log.w(TAG, "discovery refused without BLUETOOTH_CONNECT: ${e.message}")
            false
        }
    }

    override fun valveCharacteristics(): List<UUID> =
        gatt
            ?.getService(STEAM_BLE_SERVICE)
            ?.characteristics
            ?.map { it.uuid }
            .orEmpty()

    override fun requestMtu(mtu: Int): Boolean {
        val client = gatt ?: return false
        return try {
            client.requestMtu(mtu)
        } catch (e: SecurityException) {
            Log.w(TAG, "MTU request refused without BLUETOOTH_CONNECT: ${e.message}")
            false
        }
    }

    override fun enableNotifications(characteristic: UUID): Boolean {
        val client = gatt ?: return false
        val input = client.getService(STEAM_BLE_SERVICE)?.getCharacteristic(characteristic) ?: return false
        val config = input.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG) ?: return false
        val value = subscriptionValueFor(input.properties) ?: return false
        return notifyLocally(client, input) && writeDescriptor(client, config, value)
    }

    override fun write(
        characteristic: UUID,
        value: ByteArray,
    ): Boolean {
        val client = gatt ?: return false
        val target = client.getService(STEAM_BLE_SERVICE)?.getCharacteristic(characteristic) ?: return false
        return writeCharacteristic(client, target, value)
    }

    override fun close() {
        val client = gatt ?: return
        gatt = null
        try {
            client.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "close refused without BLUETOOTH_CONNECT: ${e.message}")
        }
    }

    override fun onConnectionStateChange(
        client: BluetoothGatt,
        status: Int,
        newState: Int,
    ) {
        if (newState == BluetoothProfile.STATE_CONNECTED) connected(client)
        if (newState == BluetoothProfile.STATE_DISCONNECTED) events?.onDisconnected()
    }

    // The client the callback hands over, as SDL discovers through it: it can arrive before the
    // one connectGatt returned has been stored.
    private fun connected(client: BluetoothGatt) {
        gatt = client
        events?.onConnected()
    }

    override fun onServicesDiscovered(
        client: BluetoothGatt,
        status: Int,
    ) {
        events?.onServicesDiscovered(status == BluetoothGatt.GATT_SUCCESS)
    }

    override fun onMtuChanged(
        client: BluetoothGatt,
        mtu: Int,
        status: Int,
    ) {
        events?.onMtuChanged()
    }

    override fun onDescriptorWrite(
        client: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        if (descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG) return
        events?.onNotificationsEnabled(status == BluetoothGatt.GATT_SUCCESS)
    }

    override fun onCharacteristicWrite(
        client: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        events?.onWritten(status == BluetoothGatt.GATT_SUCCESS)
    }

    override fun onCharacteristicChanged(
        client: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        events?.onNotification(characteristic.uuid, value)
    }

    // Marker: before 33 a notification arrives only through this overload, and its value only
    // through BluetoothGattCharacteristic.getValue; from 33 the overload above replaces both. The
    // right fix is a minSdk of 33; until then the deprecated pair lives here and nowhere else.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onCharacteristicChanged(
        client: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        events?.onNotification(characteristic.uuid, characteristic.value)
    }

    private fun notifyLocally(
        client: BluetoothGatt,
        input: BluetoothGattCharacteristic,
    ): Boolean =
        try {
            client.setCharacteristicNotification(input, true)
        } catch (e: SecurityException) {
            Log.w(TAG, "subscription refused without BLUETOOTH_CONNECT: ${e.message}")
            false
        }

    private fun writeDescriptor(
        client: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return legacyWriteDescriptor(client, descriptor, value)
        return try {
            client.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } catch (e: SecurityException) {
            Log.w(TAG, "subscription refused without BLUETOOTH_CONNECT: ${e.message}")
            false
        }
    }

    // The characteristic's own write type, as SDL leaves it.
    private fun writeCharacteristic(
        client: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return legacyWriteCharacteristic(client, characteristic, value)
        return try {
            client.writeCharacteristic(characteristic, value, characteristic.writeType) == BluetoothStatusCodes.SUCCESS
        } catch (e: SecurityException) {
            Log.w(TAG, "write refused without BLUETOOTH_CONNECT: ${e.message}")
            false
        }
    }

    // Marker: before 33 a descriptor is written only by setting its value and handing it over;
    // from 33 writeDescriptor(descriptor, value) replaces both. The right fix is a minSdk of 33;
    // until then the deprecated pair lives here and nowhere else.
    @Suppress("DEPRECATION")
    private fun legacyWriteDescriptor(
        client: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean {
        val staged = descriptor.setValue(value)
        return try {
            staged && client.writeDescriptor(descriptor)
        } catch (e: SecurityException) {
            Log.w(TAG, "subscription refused without BLUETOOTH_CONNECT: ${e.message}")
            false
        }
    }

    // Marker: the characteristic's form of the same pair, and the same right fix.
    @Suppress("DEPRECATION")
    private fun legacyWriteCharacteristic(
        client: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        val staged = characteristic.setValue(value)
        return try {
            staged && client.writeCharacteristic(characteristic)
        } catch (e: SecurityException) {
            Log.w(TAG, "write refused without BLUETOOTH_CONNECT: ${e.message}")
            false
        }
    }
}
