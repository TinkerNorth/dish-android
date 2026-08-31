// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager

/**
 * Native -> Kotlin upcall target for physical pads bound to a Moonlight host,
 * the sibling of [BluetoothGamepadBridge]: the native capture path publishes a
 * changed XUSB state for a SLOT_MOONLIGHT binding and the shared bridge
 * dispatch thread calls [dispatchReport], which seals and sends it on the live
 * control session. Moonlight's low-16 button flags share XInput's bit layout,
 * so the XUSB wButtons pass straight through.
 */
object MoonlightGamepadBridge {
    init {
        System.loadLibrary("satellite")
    }

    @Volatile private var manager: MoonlightConnectionManager? = null

    // Must run from a JVM call so the app classloader is on the stack (FindClass in JNI_OnLoad would fail).
    fun install(manager: MoonlightConnectionManager) {
        this.manager = manager
        nativeInstall()
    }

    @JvmStatic
    private external fun nativeInstall()

    @JvmStatic
    @Suppress("LongParameterList") // fixed native upcall signature, mirrors BluetoothGamepadBridge
    fun dispatchReport(
        connectionId: String,
        controllerNumber: Int,
        wButtons: Int,
        bLT: Int,
        bRT: Int,
        sLX: Int,
        sLY: Int,
        sRX: Int,
        sRY: Int,
    ) {
        val m = manager ?: return
        m.get(connectionId)?.sendControllerState(
            controllerNumber = controllerNumber,
            buttons = wButtons,
            leftTrigger = bLT,
            rightTrigger = bRT,
            leftX = sLX,
            leftY = sLY,
            rightX = sRX,
            rightY = sRY,
        )
    }

    /** USB-direct IMU sample for a Moonlight-bound pad, satellite wire scale. */
    @JvmStatic
    @Suppress("LongParameterList")
    fun dispatchMotion(
        connectionId: String,
        controllerNumber: Int,
        gyroX: Int,
        gyroY: Int,
        gyroZ: Int,
        accelX: Int,
        accelY: Int,
        accelZ: Int,
        timestampDeltaUs: Int,
    ) {
        val conn = manager?.get(connectionId) ?: return
        val slotId = conn.slotIdForNumber(controllerNumber) ?: return
        conn.sendMotion(
            slotId = slotId,
            gyroX = gyroX.toShort(),
            gyroY = gyroY.toShort(),
            gyroZ = gyroZ.toShort(),
            accelX = accelX.toShort(),
            accelY = accelY.toShort(),
            accelZ = accelZ.toShort(),
            timestampDeltaUs = timestampDeltaUs,
        )
    }

    /** USB-direct touchpad frame for a Moonlight-bound pad; the connection diffs it into events. */
    @JvmStatic
    @Suppress("LongParameterList")
    fun dispatchTouch(
        connectionId: String,
        controllerNumber: Int,
        finger0Active: Boolean,
        finger0Id: Int,
        finger0X: Int,
        finger0Y: Int,
        finger1Active: Boolean,
        finger1Id: Int,
        finger1X: Int,
        finger1Y: Int,
        clickDown: Boolean,
    ) {
        val conn = manager?.get(connectionId) ?: return
        val slotId = conn.slotIdForNumber(controllerNumber) ?: return
        conn.sendTouchpad(
            slotId = slotId,
            finger0Active = finger0Active,
            finger1Active = finger1Active,
            buttonPressed = clickDown,
            rightPressed = false,
            middlePressed = false,
            finger0TrackingId = finger0Id,
            finger0X = finger0X.toShort(),
            finger0Y = finger0Y.toShort(),
            finger1TrackingId = finger1Id,
            finger1X = finger1X.toShort(),
            finger1Y = finger1Y.toShort(),
            eventTimeMs = 0L,
            scrollDelta = 0,
        )
    }
}
