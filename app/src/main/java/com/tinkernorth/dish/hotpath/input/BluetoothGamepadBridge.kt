// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.core.input.xusbToHid
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry

/**
 * Java-side dispatch target for physical gamepad → Bluetooth-bound slots.
 *
 * The native GameActivity input thread processes each gamepad event, then —
 * for slots bound to a Bluetooth connection — calls [dispatchReport] from
 * native because [android.bluetooth.BluetoothHidDevice.sendReport] is Binder
 * IPC and must run on a JVM-attached thread.
 *
 * The class name and `dispatchReport` static method signature are referenced
 * by `nativeInstall` in `satellite_jni.cpp` — keep them in sync.
 */
object BluetoothGamepadBridge {
    init {
        // Defensive: the .so is normally already loaded via SatelliteNative,
        // but install() may be called before any other Kotlin entry point
        // touches that singleton (DishApplication.onCreate runs early).
        System.loadLibrary("satellite")
    }

    @Volatile private var registry: BluetoothGamepadRegistry? = null

    /**
     * Wire up the bridge once the Hilt singleton is available. Called from
     * [com.tinkernorth.dish.DishApplication.onCreate]. Also registers this
     * class with the native layer — must run from a JVM call so the app
     * classloader is on the stack (FindClass in JNI_OnLoad would fail).
     */
    fun install(registry: BluetoothGamepadRegistry) {
        this.registry = registry
        nativeInstall()
    }

    @JvmStatic
    private external fun nativeInstall()

    @JvmStatic
    fun dispatchReport(
        connectionId: String,
        wButtons: Int,
        bLT: Int,
        bRT: Int,
        sLX: Int,
        sLY: Int,
        sRX: Int,
        sRY: Int,
    ) {
        val r = registry ?: return
        val (hidButtons, hat) = xusbToHid(wButtons)
        val report =
            r.buildReport(
                connectionId,
                hidButtons,
                hat,
                sLX.toShort(),
                sLY.toShort(),
                sRX.toShort(),
                sRY.toShort(),
                bLT,
                bRT,
            ) ?: return
        r.sendReport(connectionId, report)
    }
}
