// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.hardware.Sensor
import android.os.Build
import android.view.InputDevice
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi

// The per-device sensor API exists from 31.
internal fun hasGyro(
    deviceId: Int,
    sdkInt: Int,
): Boolean {
    if (!atLeast(Build.VERSION_CODES.S, sdkInt)) return false
    return probeGyro(InputDevice.getDevice(deviceId))
}

// The annotation is what lets lint read a caller-supplied API level as the gate probeGyro needs;
// a bare `sdkInt >= api` comparison it cannot see through.
@ChecksSdkIntAtLeast(parameter = 0)
private fun atLeast(
    api: Int,
    sdkInt: Int,
): Boolean = sdkInt >= api

// Split from hasGyro so the read itself is unit-testable against a mocked InputDevice.
@RequiresApi(Build.VERSION_CODES.S)
internal fun probeGyro(device: InputDevice?): Boolean {
    if (device == null) return false
    return device.sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
}
