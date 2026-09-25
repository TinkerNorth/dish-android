// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.hardware.Sensor
import android.os.Build
import android.view.InputDevice
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi

// The per-device sensor API exists from 31; the annotation is the contract lint checks
// callers of probeGyro against.
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
private fun perDeviceSensorsAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

internal fun hasGyro(deviceId: Int): Boolean = perDeviceSensorsAvailable() && probeGyro(InputDevice.getDevice(deviceId))

// Split from hasGyro so the read itself is unit-testable against a mocked InputDevice.
@RequiresApi(Build.VERSION_CODES.S)
internal fun probeGyro(device: InputDevice?): Boolean {
    if (device == null) return false
    return device.sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
}
