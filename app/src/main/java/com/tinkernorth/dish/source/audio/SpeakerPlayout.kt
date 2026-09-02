// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/** One open output for a slot's emulated speaker, taking whole 20 ms windows. */
interface SpeakerPlayoutSession : AutoCloseable {
    /**
     * Hands one window to the sink WITHOUT blocking, and returns how many samples it took. A short
     * return is not an error: it means playback is behind and the buffer is full, and the samples
     * that did not fit are gone. The caller runs on the native audio dispatch thread, which is
     * shared by every stream, so blocking here would stall the other pads' audio as well as this
     * one's.
     */
    fun write(pcmStereo: ShortArray): Int

    override fun close()
}

/** Opens one output at the wire's format, or reports that this device would not. */
fun interface SpeakerPlayoutSink {
    fun open(
        frameSamples: Int,
        preferredDeviceId: Int,
    ): SpeakerPlayoutSession?
}

/**
 * The real thing: an [AudioTrack] pinned to the format MSG_SPEAKER_AUDIO fixes (48 kHz, stereo,
 * signed 16-bit interleaved), written one 20 ms window at a time.
 *
 * USAGE_GAME says what this is: latency-sensitive audio belonging to a game, which is what the
 * emulated pad's speaker endpoint carries. CONTENT_TYPE_MUSIC puts it on the media stream, so the
 * volume rocker moves it like everything else the user is listening to; a controller whose sound
 * cannot be turned down would be a worse pad than one that can.
 *
 * There is deliberately no resampling and no format fallback. 48 kHz stereo 16-bit is both the
 * wire's format and the platform's own native output format, so a device that refuses it has
 * refused to play at all, and inventing a converter for a case that does not arise would put
 * untested arithmetic in the one path where a bug is audible.
 */
@Singleton
class AudioTrackSpeakerSink
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SpeakerPlayoutSink {
        private val audioManager: AudioManager? =
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        override fun open(
            frameSamples: Int,
            preferredDeviceId: Int,
        ): SpeakerPlayoutSession? {
            val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING)
            if (minBytes <= 0) return null
            // Room for a few windows so a scheduling hiccup on the dispatch thread does not empty
            // the track; the start threshold below is what decides the latency, not this.
            val bufferBytes = max(minBytes, frameSamples * BYTES_PER_SAMPLE * BUFFERED_FRAMES)
            val track = buildTrack(bufferBytes) ?: return null
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return null
            }
            preferOwnEndpoint(track, preferredDeviceId)
            return TrackSession(track, startThresholdSamples = frameSamples * START_THRESHOLD_FRAMES)
        }

        private fun buildTrack(bufferBytes: Int): AudioTrack? =
            try {
                AudioTrack
                    .Builder()
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    ).setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setEncoding(ENCODING)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_MASK)
                            .build(),
                    ).setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: UnsupportedOperationException) {
                Log.w(TAG, "AudioTrack unavailable: ${e.message}")
                null
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "AudioTrack rejected the wire format: ${e.message}")
                null
            }

        /**
         * Point the track at the pad's OWN speaker where the route table named one. A failure here
         * is not fatal: the track still plays, out of whatever the platform picked, which is the
         * same thing that happens for the on-screen pad.
         */
        private fun preferOwnEndpoint(
            track: AudioTrack,
            preferredDeviceId: Int,
        ) {
            if (preferredDeviceId == NO_AUDIO_DEVICE) return
            val device =
                audioManager
                    ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    ?.firstOrNull { it.id == preferredDeviceId }
            if (device == null) {
                Log.i(TAG, "endpoint $preferredDeviceId is gone, playing out the default output")
                return
            }
            if (!track.setPreferredDevice(device)) {
                Log.i(TAG, "endpoint $preferredDeviceId refused, playing out the default output")
            }
        }

        /**
         * Playback starts once [startThresholdSamples] have been buffered, not on the first frame:
         * a track started empty plays the first window and then underruns, and every underrun is an
         * audible click. Two windows is the whole cushion, which is the same 40 ms the reorder
         * window upstream already costs, so this adds no latency the stream did not have.
         *
         * The lock is not for throughput, it is for lifetime: writes come from the native dispatch
         * thread and [close] comes from the collector, and releasing an AudioTrack out from under a
         * write in progress is a use-after-free in native code. Both sides are bounded (the write
         * never blocks), so the lock is held for microseconds.
         */
        private class TrackSession(
            private val track: AudioTrack,
            private val startThresholdSamples: Int,
        ) : SpeakerPlayoutSession {
            private val lock = Any()
            private var buffered = 0
            private var playing = false
            private var closed = false
            private var reportedFailure = false
            private var lastSeenUnderruns = 0
            private val cushion = ShortArray(startThresholdSamples)

            override fun write(pcmStereo: ShortArray): Int =
                synchronized(lock) {
                    if (closed) return 0
                    refillCushion()
                    // WRITE_NON_BLOCKING accounts in whole frames, so a partial write never leaves
                    // half a stereo pair behind and the channels cannot swap; the tail is simply
                    // dropped, which is the right thing for a live stream whose buffer is full.
                    val written = track.write(pcmStereo, 0, pcmStereo.size, AudioTrack.WRITE_NON_BLOCKING)
                    if (written < 0) {
                        if (!reportedFailure) {
                            reportedFailure = true
                            Log.w(TAG, "AudioTrack write failed ($written), dropping until the plan changes")
                        }
                        return 0
                    }
                    if (!playing) startWhenPrimed(written)
                    written
                }

            private fun startWhenPrimed(written: Int) {
                buffered += written
                if (buffered < startThresholdSamples) return
                runCatching { track.play() }
                    .onFailure { Log.w(TAG, "AudioTrack would not start: ${it.message}") }
                playing = true
                // Underruns from before playback started are not a drained cushion, so the
                // baseline is taken here rather than at construction.
                lastSeenUnderruns = runCatching { track.underrunCount }.getOrDefault(0)
            }

            private fun refillCushion() {
                val underruns = runCatching { track.underrunCount }.getOrNull() ?: return
                val refill =
                    SpeakerCushionPolicy.refillSamples(
                        playing = playing,
                        underruns = underruns,
                        lastSeenUnderruns = lastSeenUnderruns,
                        cushionSamples = cushion.size,
                    )
                lastSeenUnderruns = underruns
                if (refill <= 0) return
                track.write(cushion, 0, refill, AudioTrack.WRITE_NON_BLOCKING)
            }

            override fun close() {
                synchronized(lock) {
                    if (closed) return
                    closed = true
                    // stop() throws if the track already died under us; release() still has to run.
                    runCatching { track.stop() }
                    track.release()
                }
            }
        }

        private companion object {
            const val TAG = "SpeakerPlayout"
            const val SAMPLE_RATE = SpeakerEngine.SAMPLE_RATE
            const val CHANNEL_MASK = AudioFormat.CHANNEL_OUT_STEREO
            const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
            const val BYTES_PER_SAMPLE = 2
            const val BUFFERED_FRAMES = 4
            const val START_THRESHOLD_FRAMES = 2
        }
    }
