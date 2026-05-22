// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.sensor

import android.annotation.SuppressLint
import android.hardware.Sensor
import android.os.Build
import android.view.InputDevice

/**
 * One-shot capability probe: does this physical gamepad expose a gyroscope
 * through Android's per-device sensor API?
 *
 * Pulled out of [PhysicalMotionSource] so the same probe can populate
 * [com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry.Device.hasGyro]
 * once at device-add time. That fact is then read by
 * [com.tinkernorth.dish.composer.MotionCapabilityComposer] to decide whether
 * to advertise `CAP_MOTION` for a slot, and by [PhysicalMotionSource] to
 * decide whether to register sensor listeners — without each call site re-doing
 * the API-31 / null-sensor dance.
 *
 * The probe is API-gated: [InputDevice.getSensorManager] is API 31+. Below
 * that, per-device sensors are not exposed by the platform and a pad's IMU is
 * unreachable from the app regardless of hardware.
 *
 * **Testability:** the pure decision (given an SDK level and an
 * `InputDevice`, does it have a gyro?) is exposed as [evaluate], so unit
 * tests don't have to stub `Build.VERSION.SDK_INT` via reflection.
 * [hasGyro] is the wrapper that resolves both inputs from the live OS.
 */
object PhysicalMotionProbe {
    /**
     * Returns true iff the device with [deviceId] is currently attached and
     * its per-device [android.hardware.SensorManager] reports a
     * [Sensor.TYPE_GYROSCOPE] sensor. Returns false on API < 31, on an
     * unknown device id, or on a pad with no IMU surface — all of which are
     * legitimate "not motion-capable" outcomes, not errors.
     */
    fun hasGyro(deviceId: Int): Boolean = evaluate(Build.VERSION.SDK_INT, InputDevice.getDevice(deviceId))

    /**
     * Pure variant: returns true iff [sdkInt] is API 31+ and [device] has a
     * gyroscope on its per-device [android.hardware.SensorManager]. The
     * [InputDevice] is still queried (not a pure data class), but the SDK
     * gate is parameterised so unit tests can drive every branch from a JVM
     * without reflection on the system constants.
     *
     * `@SuppressLint("NewApi")` — the `InputDevice.getSensorManager()` call
     * is API-31-gated by the first guard, but lint can't statically prove
     * that because `sdkInt` is a runtime parameter, not `Build.VERSION
     * .SDK_INT` directly. The runtime gate is explicit; the alternative
     * (`@RequiresApi(S)`) would force every caller to gate too, defeating
     * the parameterised-pure-function design.
     */
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
