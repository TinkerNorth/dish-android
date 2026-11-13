// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.SteamBleBridge
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.system.BluetoothAdapterState
import com.tinkernorth.dish.source.system.BluetoothAdapterStateObserver
import com.tinkernorth.dish.source.system.BluetoothPermissionState
import com.tinkernorth.dish.source.system.BluetoothPermissionStateObserver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SteamBle"

/** The paired devices and GATT clients of the phone's own Bluetooth adapter. */
internal class AndroidSteamBleRadio(
    private val context: Context,
) : SteamBleRadio {
    private fun adapter(): BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    override fun pairedSteamControllers(): List<String> {
        val adapter = adapter() ?: return emptyList()
        return try {
            adapter.bondedDevices
                .orEmpty()
                .filter(::isASteamController)
                .map { it.address }
        } catch (e: SecurityException) {
            Log.w(TAG, "paired devices unavailable without BLUETOOTH_CONNECT: ${e.message}")
            emptyList()
        }
    }

    override fun gattFor(address: String): SteamGatt? {
        val adapter = adapter() ?: return null
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return null
        return AndroidSteamGatt(context, device)
    }

    fun isASteamController(device: BluetoothDevice): Boolean =
        try {
            isSteamControllerDevice(device.name, device.type)
        } catch (e: SecurityException) {
            Log.w(TAG, "device name unavailable without BLUETOOTH_CONNECT: ${e.message}")
            false
        }
}

/**
 * Steam Controllers paired to the phone over Bluetooth LE, as Direct pads out of lizard mode: the
 * USB Direct path's decoders, slot list, trackpad pick, extended buttons and battery rule, reached
 * through a GATT client of the app's own beside the HID-over-GATT one that binds the controller's
 * keyboard and mouse. Started with the application.
 */
@Singleton
class SteamBleManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val permission: BluetoothPermissionStateObserver,
        private val adapterState: BluetoothAdapterStateObserver,
        private val registry: PhysicalGamepadRegistry,
        private val native: PhysicalInputNative,
        private val scope: CoroutineScope,
    ) {
        private val radio = AndroidSteamBleRadio(context)

        // A claimed pad's writes, by pad id, for native's upcalls from its output worker.
        private val writers = ConcurrentHashMap<Int, SteamBleWriter>()

        // One claim or release at a time: each waits on the controller's answers.
        private val work = Dispatchers.IO.limitedParallelism(1).asExecutor()

        private val links = SteamBleLinks(radio, Claims(), work)

        private val _status = MutableStateFlow(SteamBleStatus.UNSUPPORTED)
        val status: StateFlow<SteamBleStatus> = _status.asStateFlow()

        // A Steam Controller on Bluetooth the permission keeps from being a controller.
        val permissionNeeded: StateFlow<Boolean> =
            combine(status, registry.ignoredInputDevices) { now, ignored -> steamBlePermissionNeeded(now, ignored.values) }
                .stateIn(scope, SharingStarted.Eagerly, false)

        private val hasBluetoothLe = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

        @Volatile private var installed = false

        private inner class AclReceiver : BroadcastReceiver() {
            override fun onReceive(
                ctx: Context,
                intent: Intent,
            ) {
                val device =
                    IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                val action = intent.action
                if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED) links.onDisconnected(device.address)
                val aSteamControllerCame = action == BluetoothDevice.ACTION_ACL_CONNECTED && radio.isASteamController(device)
                if (aSteamControllerCame) links.onSteamControllerConnected(device.address)
            }
        }

        private inner class Claims : SteamBleClaims {
            override fun attach(
                model: SteamBleModel,
                writer: SteamBleWriter,
            ): Int {
                val padId = native.attachBlePad(STEAM_BLE_VENDOR_ID, model.productId, model.inputReportId)
                if (padId != NO_STEAM_BLE_PAD) writers[padId] = writer
                return padId
            }

            override fun start(
                padId: Int,
                model: SteamBleModel,
            ): Boolean {
                if (!native.startBlePad(padId)) return false
                registry.addUsbSynthetic(
                    deviceId = padId,
                    name = native.lookupKnownModelName(STEAM_BLE_VENDOR_ID, model.productId),
                    hasGyro = native.modelHasImu(STEAM_BLE_VENDOR_ID, model.productId),
                    pollRateHz = 0,
                    vendorId = STEAM_BLE_VENDOR_ID,
                    productId = model.productId,
                    transport = Transport.Bluetooth,
                )
                Log.i(TAG, "pad $padId claimed: ${model.name}")
                return true
            }

            override fun report(
                padId: Int,
                value: ByteArray,
            ) {
                native.processBleReport(padId, value)
            }

            override fun detach(
                padId: Int,
                restore: Boolean,
            ) {
                registry.removeUsbSynthetic(padId)
                native.detachBlePad(padId, restore)
                writers -= padId
                Log.i(TAG, "pad $padId released (restored=$restore)")
            }
        }

        fun install() {
            if (installed) return
            installed = true
            SteamBleBridge.install(::writeFor)
            ContextCompat.registerReceiver(
                context,
                AclReceiver(),
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            combine(adapterState.state, permission.state, ::statusOf)
                .distinctUntilChanged()
                .onEach { next -> withContext(Dispatchers.Main) { applyStatus(next) } }
                .launchIn(scope)
        }

        private fun statusOf(
            adapter: BluetoothAdapterState,
            granted: BluetoothPermissionState,
        ): SteamBleStatus = steamBleStatusOf(hasBluetoothLe, adapter, granted.connectMissing)

        private fun applyStatus(next: SteamBleStatus) {
            _status.value = next
            links.onStatus(next)
        }

        private fun writeFor(
            padId: Int,
            target: Int,
            value: ByteArray,
        ): Boolean = writers[padId]?.write(target, value) ?: false
    }
