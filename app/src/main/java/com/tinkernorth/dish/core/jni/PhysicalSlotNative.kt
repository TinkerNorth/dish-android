// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.jni

/**
 * Routing of physical pads: which slot a device drives, the framework input events that
 * feed it, and the per-device state the inspector mirrors.
 */
object PhysicalSlotNative {
    init {
        System.loadLibrary("satellite")
    }

    external fun bindPhysicalSlotSatellite(
        deviceId: Int,
        sessionHandle: Int,
        controllerIndex: Int,
    )

    external fun bindPhysicalSlotBluetooth(
        deviceId: Int,
        connectionId: String,
    )

    // One Moonlight session carries up to four pads, so unlike the Bluetooth bind the
    // binding has to name which of them this device drives.
    external fun bindPhysicalSlotMoonlight(
        deviceId: Int,
        connectionId: String,
        controllerNumber: Int,
    )

    external fun unbindPhysicalSlot(deviceId: Int)

    external fun clearAllPhysicalSlots()

    external fun forgetPhysicalDevice(deviceId: Int)

    // Without this, axes near rest stream tiny non-zero values upstream.
    external fun setDeviceDeadzones(
        deviceId: Int,
        flatX: Float,
        flatY: Float,
        flatZ: Float,
        flatRZ: Float,
    )

    external fun setDeviceQuirk(
        deviceId: Int,
        quirk: Int,
    )

    external fun releaseAllPhysicalReports()

    external fun processGamepadKeyEvent(
        deviceId: Int,
        source: Int,
        action: Int,
        keyCode: Int,
    ): Boolean

    // Flat parameter list (not packed) so each axis stays primitive Float: no per-event allocation.
    // Marker: the JNI edge of the framework input hot path (every MotionEvent of every routed pad).
    // A parameter object would cost an allocation or a GetFieldID plus GetFloatField per axis, per
    // event, which is exactly what the flat signature exists to avoid.
    @Suppress("LongParameterList")
    external fun processGamepadMotionEvent(
        deviceId: Int,
        source: Int,
        action: Int,
        x: Float,
        y: Float,
        z: Float,
        rz: Float,
        rx: Float,
        ry: Float,
        hatX: Float,
        hatY: Float,
        ltrigger: Float,
        rtrigger: Float,
        brake: Float,
        gas: Float,
    ): Boolean

    // Framework KeyEvent/MotionEvent updates applied for a routed device (USB Standard or Bluetooth).
    external fun getDeviceInputEventCount(deviceId: Int): Long

    // JSON snapshot of a device's wire-facing input state; empty string for an unknown device.
    external fun deviceStateJson(deviceId: Int): String
}
