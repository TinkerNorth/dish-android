// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

object RumbleBridge {
    init {
        System.loadLibrary("satellite")
    }

    @Volatile private var router: RumbleRouter? = null

    // Must run from a JVM call so the app classloader is on the stack (FindClass in JNI_OnLoad would fail).
    fun install(router: RumbleRouter) {
        this.router = router
        nativeInstall()
    }

    @JvmStatic
    private external fun nativeInstall()

    @JvmStatic
    fun dispatchRumble(
        sessionHandle: Int,
        controllerIndex: Int,
        strongMagnitude: Int,
        weakMagnitude: Int,
        durationMs: Int,
    ) {
        router?.dispatch(sessionHandle, controllerIndex, strongMagnitude, weakMagnitude, durationMs)
    }
}
