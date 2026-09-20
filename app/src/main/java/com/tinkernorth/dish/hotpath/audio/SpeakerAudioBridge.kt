// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.audio

/**
 * Native -> Kotlin upcall target for the emulated pad's outbound audio streams
 * (MSG_SPEAKER_AUDIO and, on protocol 3, MSG_HAPTIC_AUDIO), the audio-side sibling of
 * [com.tinkernorth.dish.hotpath.input.FeedbackBridge]. The two streams share the shape
 * and the thread; [lane] says which pair of the pad's endpoint a window is for.
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

    /** Wire lanes, as native numbers them: 0 speaker, 1 haptics. */
    const val LANE_SPEAKER = 0
    const val LANE_HAPTICS = 1

    fun interface Sink {
        fun onAudioFrame(
            sessionHandle: Int,
            controllerIndex: Int,
            lane: Int,
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
    fun dispatchAudioFrame(
        sessionHandle: Int,
        controllerIndex: Int,
        lane: Int,
        pcmStereo: ShortArray,
        concealed: Boolean,
    ) {
        sink?.onAudioFrame(sessionHandle, controllerIndex, lane, pcmStereo, concealed)
    }
}
