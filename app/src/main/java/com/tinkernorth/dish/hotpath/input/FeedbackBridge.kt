// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

/**
 * Native -> Kotlin upcall target for the non-rumble feedback messages a
 * satellite returns (MSG_LIGHTBAR / MSG_TRIGGER_EFFECTS / MSG_PLAYER_LEDS /
 * MSG_MIC_LED), the sibling of [RumbleBridge]: the native receive thread
 * decodes the inner frame and calls the matching static below; the
 * [FeedbackRouter] resolves the (session, controller index) pair to a slot and
 * actuates.
 */
object FeedbackBridge {
    init {
        System.loadLibrary("satellite")
    }

    @Volatile private var router: FeedbackRouter? = null

    // Must run from a JVM call so the app classloader is on the stack (FindClass in JNI_OnLoad would fail).
    fun install(router: FeedbackRouter) {
        this.router = router
        nativeInstall()
    }

    @JvmStatic
    private external fun nativeInstall()

    @JvmStatic
    fun dispatchLightbar(
        sessionHandle: Int,
        controllerIndex: Int,
        r: Int,
        g: Int,
        b: Int,
    ) {
        router?.dispatchLightbar(sessionHandle, controllerIndex, r, g, b)
    }

    @JvmStatic
    fun dispatchTriggerEffects(
        sessionHandle: Int,
        controllerIndex: Int,
        blocks: ByteArray,
    ) {
        router?.dispatchTriggerEffects(sessionHandle, controllerIndex, blocks)
    }

    @JvmStatic
    fun dispatchPlayerLeds(
        sessionHandle: Int,
        controllerIndex: Int,
        ledMask: Int,
    ) {
        router?.dispatchPlayerLeds(sessionHandle, controllerIndex, ledMask)
    }

    /**
     * Mic-mute lamp state the game asked for: 0 off, 1 on, 2 pulse. Native has
     * already dropped anything outside that range, so this only ever sees a
     * state the pad can render.
     */
    @JvmStatic
    fun dispatchMicLed(
        sessionHandle: Int,
        controllerIndex: Int,
        state: Int,
    ) {
        router?.dispatchMicLed(sessionHandle, controllerIndex, state)
    }
}
