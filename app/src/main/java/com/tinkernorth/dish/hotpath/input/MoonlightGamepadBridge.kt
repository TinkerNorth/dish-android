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
}
