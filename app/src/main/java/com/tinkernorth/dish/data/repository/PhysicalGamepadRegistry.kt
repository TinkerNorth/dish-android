// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.repository

import android.content.Context
import android.hardware.input.InputManager
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import com.tinkernorth.dish.data.network.SatelliteNative
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped registry of currently-attached physical gamepad InputDevices.
 *
 * Owned by [com.tinkernorth.dish.DishApplication]; lives outside any activity
 * so the per-device → connection bindings managed by
 * [com.tinkernorth.dish.data.network.PhysicalSlotBindingObserver] don't get
 * torn down when [com.tinkernorth.dish.ui.main.MainActivity] stops in favour
 * of the gamepad overlay. Also the natural home for the deadzone-push that
 * the native input pipeline depends on, since it only needs to run once per
 * device-add.
 */
@Singleton
class PhysicalGamepadRegistry
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : InputManager.InputDeviceListener {
        data class Device(
            val id: Int,
            val name: String,
        )

        private val inputManager =
            context.getSystemService(Context.INPUT_SERVICE) as InputManager

        private val _devices = MutableStateFlow<Map<Int, Device>>(emptyMap())
        val devices: StateFlow<Map<Int, Device>> = _devices.asStateFlow()

        @Volatile private var installed = false

        /**
         * Register the [InputManager] listener and seed the device map from
         * everything already attached. Idempotent — safe if [install] is
         * called twice (e.g. in tests that re-construct the application).
         */
        fun install() {
            if (installed) return
            installed = true
            inputManager.registerInputDeviceListener(this, null)
            syncAll()
        }

        private fun syncAll() {
            val next = mutableMapOf<Int, Device>()
            for (id in InputDevice.getDeviceIds()) {
                val dev = InputDevice.getDevice(id) ?: continue
                if (!isGamepad(dev)) continue
                pushDeadzones(dev)
                next[id] = Device(id, dev.name)
            }
            _devices.value = next
        }

        override fun onInputDeviceAdded(deviceId: Int) {
            val dev = InputDevice.getDevice(deviceId) ?: return
            if (!isGamepad(dev)) return
            pushDeadzones(dev)
            _devices.value = _devices.value + (deviceId to Device(deviceId, dev.name))
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            if (deviceId !in _devices.value) return
            _devices.value = _devices.value - deviceId
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            val dev = InputDevice.getDevice(deviceId)
            if (dev == null || !isGamepad(dev)) {
                if (deviceId in _devices.value) _devices.value = _devices.value - deviceId
                return
            }
            // Name or sources may have changed — refresh in place so the slot
            // row picks up the new label without a phantom add/remove cycle.
            val current = _devices.value[deviceId]
            if (current == null || current.name != dev.name) {
                _devices.value = _devices.value + (deviceId to Device(deviceId, dev.name))
            }
        }

        /**
         * Query the device's per-axis flat (deadzone) values once and push them
         * into the native processor, which uses them in its inline deadzone
         * gate. Pushed at device-add time so the native input thread never has
         * to cross back into Java per event to look them up.
         */
        private fun pushDeadzones(dev: InputDevice) {
            val src = InputDevice.SOURCE_JOYSTICK
            SatelliteNative.setDeviceDeadzones(
                dev.id,
                dev.getMotionRange(MotionEvent.AXIS_X, src)?.flat ?: 0f,
                dev.getMotionRange(MotionEvent.AXIS_Y, src)?.flat ?: 0f,
                dev.getMotionRange(MotionEvent.AXIS_Z, src)?.flat ?: 0f,
                dev.getMotionRange(MotionEvent.AXIS_RZ, src)?.flat ?: 0f,
            )
            logDeviceCapabilities(dev)
        }

        // One-line per-device dump of every motion range the controller declares,
        // so the native filter's axis choices can be cross-checked against what
        // the device actually advertises (Z/RZ vs RX/RY for right stick, hat
        // axes vs DPAD keycodes, etc.).
        private fun logDeviceCapabilities(dev: InputDevice) {
            val axes =
                intArrayOf(
                    MotionEvent.AXIS_X,
                    MotionEvent.AXIS_Y,
                    MotionEvent.AXIS_Z,
                    MotionEvent.AXIS_RZ,
                    MotionEvent.AXIS_RX,
                    MotionEvent.AXIS_RY,
                    MotionEvent.AXIS_HAT_X,
                    MotionEvent.AXIS_HAT_Y,
                    MotionEvent.AXIS_LTRIGGER,
                    MotionEvent.AXIS_RTRIGGER,
                    MotionEvent.AXIS_BRAKE,
                    MotionEvent.AXIS_GAS,
                )
            val names =
                arrayOf("X", "Y", "Z", "RZ", "RX", "RY", "HX", "HY", "LT", "RT", "BR", "GS")
            val sb = StringBuilder()
            sb
                .append("DEVCAPS id=")
                .append(dev.id)
                .append(" name=\"")
                .append(dev.name)
                .append('"')
                .append(" sources=0x")
                .append(Integer.toHexString(dev.sources))
                .append(" ranges=[")
            var first = true
            for (i in axes.indices) {
                val r = dev.getMotionRange(axes[i], InputDevice.SOURCE_JOYSTICK) ?: continue
                if (!first) sb.append(',')
                first = false
                sb
                    .append(names[i])
                    .append('(')
                    .append(r.min)
                    .append("..")
                    .append(r.max)
                    .append(",flat=")
                    .append(r.flat)
                    .append(')')
            }
            sb.append(']')
            Log.i("SatelliteJNI", sb.toString())
        }

        /**
         * "Looks like a controller" — non-alphabetic-keyboard + carries a
         * GAMEPAD or JOYSTICK source bit. We deliberately don't gate on
         * [InputDevice.hasKeys] for the standard A/B/X/Y/Start/Select
         * keycodes: generic HID joysticks (e.g. cheap USB adapters) expose
         * their buttons as KEYCODE_BUTTON_1..16 because no OEM key-layout
         * file relabels them, and rejecting those left the device invisible
         * to the slot list entirely. Buttons whose keycodes don't translate
         * still fall out cleanly at `keycodeToXusb` in the native pipeline.
         */
        private fun isGamepad(d: InputDevice): Boolean = isGamepadDeviceFromCapabilities(d.sources, d.keyboardType)
    }

/**
 * Pure-function variant of [PhysicalGamepadRegistry.isGamepad] that takes the
 * raw capability bits instead of an [InputDevice]. Lifted out so the
 * classifier can be exercised in JVM unit tests without mocking
 * [InputDevice] — that's a final class with many `native` methods, which
 * trips the test-worker JVM through mockk's bytecode rewriter.
 */
internal fun isGamepadDeviceFromCapabilities(
    sources: Int,
    keyboardType: Int,
): Boolean {
    if (keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false
    return (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
}
