// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.audio

/**
 * Native -> Kotlin upcall target for the emulated pad's speaker stream
 * (MSG_SPEAKER_AUDIO), the audio-side sibling of
 * [com.tinkernorth.dish.hotpath.input.FeedbackBridge].
 *
 * Native owns everything up to PCM: the receive thread queues the Opus packet,
 * a dedicated dispatch thread runs the 2-frame reorder window, decodes, and
 * conceals the frames that never arrived. What arrives here is therefore
 * already in stream order and already one whole 20 ms window: 960 stereo
 * frames at 48 kHz, so 1920 interleaved signed 16-bit samples, with
 * [concealed] telling the sink whether the frame was decoded from a packet or
 * synthesized to cover a gap.
 *
 * The [Sink] MUST NOT block for long: it is called on the audio dispatch
 * thread, whose queue is 8 frames deep and drops the oldest when it overruns.
 * The playback engine is expected to hand the buffer to a non-blocking
 * AudioTrack write (or its own ring) and return.
 */
object SpeakerAudioBridge {
    init {
        System.loadLibrary("satellite")
    }

    fun interface Sink {
        fun onSpeakerFrame(
            sessionHandle: Int,
            controllerIndex: Int,
            pcmStereo: ShortArray,
            concealed: Boolean,
        )
    }

    @Volatile private var sink: Sink? = null

    /**
     * Must run from a JVM call so the app classloader is on the stack (FindClass
     * in JNI_OnLoad would fail). Also starts the native dispatch thread, so a
     * build with no playback engine never spawns it.
     */
    fun install(sink: Sink) {
        this.sink = sink
        nativeInstall()
    }

    /** Frames keep being decoded natively; with no sink they are simply dropped. */
    fun uninstall() {
        sink = null
    }

    @JvmStatic
    private external fun nativeInstall()

    @JvmStatic
    fun dispatchSpeakerFrame(
        sessionHandle: Int,
        controllerIndex: Int,
        pcmStereo: ShortArray,
        concealed: Boolean,
    ) {
        sink?.onSpeakerFrame(sessionHandle, controllerIndex, pcmStereo, concealed)
    }
}
