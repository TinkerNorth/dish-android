// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

/**
 * Native -> Kotlin upcall for a Direct-claimed pad's OWN mic-mute button, the one signal on the
 * USB input path the app needs as an event rather than as a report.
 *
 * The DualSense's mute button is momentary; the mute STATE it toggles is what rides the wire
 * (`WBUTTON_MIC_MUTE`), so the latch has to live in the decoder: the pad's input report is built
 * and sent entirely on the USB reader thread, and a latch behind a JNI call would put the JVM in
 * that path. What comes up here is only the resulting state, and only when it changed, so the
 * capture engine and the mute lamp read one place along with the on-screen pad's own button.
 *
 * Sibling of [FeedbackBridge], opposite direction: that one carries what the host asked the pad to
 * do, this one carries what the user did to the pad.
 */
object MicMuteBridge {
    init {
        System.loadLibrary("satellite")
    }

    fun interface Sink {
        /**
         * [deviceId] is the synthetic USB device id, which is also this pad's slot id in string
         * form (see CapabilityComposer's per-device slots).
         */
        fun onPadMicMuteChanged(
            deviceId: Int,
            muted: Boolean,
        )
    }

    @Volatile private var sink: Sink? = null

    // Must run from a JVM call so the app classloader is on the stack (FindClass in JNI_OnLoad
    // would fail).
    fun install(sink: Sink) {
        this.sink = sink
        nativeInstall()
    }

    @JvmStatic
    private external fun nativeInstall()

    @JvmStatic
    fun dispatchPadMicMute(
        deviceId: Int,
        muted: Boolean,
    ) {
        sink?.onPadMicMuteChanged(deviceId, muted)
    }
}
