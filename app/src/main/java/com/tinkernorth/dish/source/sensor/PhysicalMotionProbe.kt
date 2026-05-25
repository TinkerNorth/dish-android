// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.annotation.SuppressLint
import android.hardware.Sensor
import android.os.Build
import android.view.InputDevice

object PhysicalMotionProbe {
    fun hasGyro(deviceId: Int): Boolean = evaluate(Build.VERSION.SDK_INT, InputDevice.getDevice(deviceId))

    // sdkInt is a runtime parameter so lint can't statically prove the API-31 gate.
    @SuppressLint("NewApi")
    fun evaluate(
        sdkInt: Int,
        device: InputDevice?,
    ): Boolean {
        if (sdkInt < Build.VERSION_CODES.S) return false
        if (device == null) return false
        return device.sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
    }
}
