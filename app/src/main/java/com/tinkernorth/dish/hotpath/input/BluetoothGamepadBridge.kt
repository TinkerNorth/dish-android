// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.core.input.hidButtonsOf
import com.tinkernorth.dish.core.input.hidHatOf
import com.tinkernorth.dish.core.input.xusbToHid
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry

object BluetoothGamepadBridge {
    init {
        System.loadLibrary("satellite")
    }

    @Volatile private var registry: BluetoothGamepadRegistry? = null

    // Must run from a JVM call so the app classloader is on the stack (FindClass in JNI_OnLoad would fail).
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
        val packed = xusbToHid(wButtons)
        val report =
            r.buildReport(
                connectionId,
                hidButtonsOf(packed),
                hidHatOf(packed),
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
