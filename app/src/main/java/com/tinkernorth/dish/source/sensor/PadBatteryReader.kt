// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.InputDevice
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PadBatteryReader
    internal constructor(
        private val native: PhysicalInputNative,
        private val bluetoothBattery: BluetoothBatteryReader,
        private val sdkInt: Int,
        private val lookup: (deviceId: Int) -> InputDevice?,
    ) {
        @Inject
        constructor(
            @ApplicationContext context: Context,
            native: PhysicalInputNative,
        ) : this(native, BluetoothBatteryReader(context), Build.VERSION.SDK_INT, InputDevice::getDevice)

        private val frameworkDevices = HashMap<Int, InputDevice>()

        fun retain(present: Set<Int>) {
            frameworkDevices.keys.retainAll(present)
        }

        // A Direct-claimed pad has no InputDevice to ask; its own reader decoded the charge out
        // of the last report, and the card shows that. (The wire is the USB rule's, see
        // BatteryRouting: the phone battery, whatever the pad says.)
        fun sample(device: PhysicalGamepadRegistry.Device): BatterySample? =
            if (device.isUsbSynthetic) readDirectPadSample(device.id) else frameworkPadSample(device.id)

        private fun readDirectPadSample(deviceId: Int): BatterySample? {
            val sample = directPadSample(native.getDirectPadBattery(deviceId))
            if (sample != null) Log.d(TAG, "pad $deviceId own battery (Direct) $sample")
            return sample
        }

        private fun frameworkPadSample(deviceId: Int): BatterySample? {
            val device = frameworkDevice(deviceId) ?: return null
            if (atLeast(Build.VERSION_CODES.S)) return batteryStateSample(deviceId, device)
            // API 24-30: no getBatteryState(), use BT reflection fallback.
            val level = bluetoothBattery.readLevel(device.name) ?: return null
            Log.d(TAG, "pad $deviceId BT battery level=$level (API<31 reflection)")
            return BatterySample(level, BatteryValidator.STATUS_UNKNOWN)
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun batteryStateSample(
            deviceId: Int,
            device: InputDevice,
        ): BatterySample? {
            val state = device.batteryState
            val sample =
                controllerSample(
                    isPresent = state.isPresent,
                    capacity = state.capacity,
                    status = state.status,
                )
            if (sample != null) Log.d(TAG, "pad $deviceId own battery $sample")
            return sample
        }

        private fun frameworkDevice(deviceId: Int): InputDevice? {
            frameworkDevices[deviceId]?.let { return it }
            val device = lookup(deviceId) ?: return null
            frameworkDevices[deviceId] = device
            return device
        }

        @ChecksSdkIntAtLeast(parameter = 0)
        private fun atLeast(api: Int): Boolean = sdkInt >= api

        private companion object {
            const val TAG = "PadBatteryReader"
        }
    }
