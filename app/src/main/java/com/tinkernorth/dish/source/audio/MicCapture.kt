// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/** One open microphone, handing out whole 20 ms windows. */
interface MicCaptureSession : AutoCloseable {
    /**
     * True when the recorder is on VOICE_COMMUNICATION and the platform's echo cancellation and
     * noise suppression come with it. False means the fallback source, which still captures but
     * will feed the emulated pad's endpoint the phone's own speaker output along with the voice.
     */
    val voiceProcessed: Boolean

    /**
     * Blocks until [out] holds one whole window, and returns how many samples it wrote. Anything
     * short of `out.size` means the recorder stopped or errored: the caller must not send a
     * partial window, since a mis-framed packet is one the far end cannot place in its timeline.
     */
    fun read(out: ShortArray): Int

    override fun close()
}

/**
 * Opens a microphone at the wire's format, or reports that this device would not.
 *
 * [preferredDeviceId] is an [android.media.AudioDeviceInfo] id from the pad route table, or
 * [NO_AUDIO_DEVICE] for the phone's own microphone. It is a parameter rather than a setting because
 * a preferred device is fixed for the life of a recorder, which is exactly why the engine runs one
 * recorder per distinct route.
 */
fun interface MicCaptureSource {
    fun open(
        frameSamples: Int,
        preferredDeviceId: Int,
    ): MicCaptureSession?
}

/**
 * The real thing: an [AudioRecord] pinned to the format MSG_MIC_AUDIO fixes (48 kHz, mono, signed
 * 16-bit), read one 20 ms window at a time.
 *
 * VOICE_COMMUNICATION is the source we want, because it brings the platform's acoustic echo
 * cancellation and noise suppression for free, and a phone held as a controller has its own
 * speaker pointed straight at its own microphone. A device that refuses it falls back to plain MIC
 * rather than failing: a microphone with no echo cancellation is worse than one with it and far
 * better than none. There is deliberately no sample-rate fallback and no resampler; 48 kHz mono
 * 16-bit is the platform's own native capture format, so a device that refuses THAT has refused
 * to record at all, and inventing a rate converter for a case that does not arise would put
 * untested arithmetic in the one path where a bug is audible on somebody else's PC.
 */
@Singleton
class AudioRecordMicSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : MicCaptureSource {
        private val audioManager: AudioManager? =
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        override fun open(
            frameSamples: Int,
            preferredDeviceId: Int,
        ): MicCaptureSession? {
            openWith(MediaRecorder.AudioSource.VOICE_COMMUNICATION, frameSamples, preferredDeviceId)
                ?.let { return it }
            Log.w(TAG, "VOICE_COMMUNICATION refused at 48 kHz mono, falling back to MIC (no AEC/NS)")
            return openWith(MediaRecorder.AudioSource.MIC, frameSamples, preferredDeviceId)
        }

        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        private fun openWith(
            source: Int,
            frameSamples: Int,
            preferredDeviceId: Int,
        ): MicCaptureSession? {
            val record = buildRecorder(source, frameSamples) ?: return null
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            preferOwnEndpoint(record, preferredDeviceId)
            return try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    record.release()
                    null
                } else {
                    RecorderSession(record, source == MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioRecord source=$source would not start: ${e.message}")
                record.release()
                null
            }
        }

        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        private fun buildRecorder(
            source: Int,
            frameSamples: Int,
        ): AudioRecord? {
            val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING)
            if (minBytes <= 0) return null
            // Room for several windows so a scheduling hiccup on the capture thread drops nothing;
            // the recorder itself is what would overrun, and it overruns silently.
            val bufferBytes = max(minBytes, frameSamples * BYTES_PER_SAMPLE * BUFFERED_FRAMES)
            return try {
                AudioRecord
                    .Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setEncoding(ENCODING)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_MASK)
                            .build(),
                    ).setBufferSizeInBytes(bufferBytes)
                    .build()
            } catch (e: UnsupportedOperationException) {
                Log.w(TAG, "AudioRecord source=$source unavailable: ${e.message}")
                null
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "AudioRecord source=$source rejected the wire format: ${e.message}")
                null
            } catch (e: SecurityException) {
                // The grant can vanish between the eligibility check and here (revoked in system
                // settings); that is a refusal, not a crash.
                Log.w(TAG, "AudioRecord source=$source denied: ${e.message}")
                null
            }
        }

        /**
         * Capture from the pad's OWN microphone where the route table named one, so a Direct-claimed
         * DualSense's headset is what the emulated pad's microphone endpoint carries. A failure here
         * is not fatal: the recorder still captures, from whatever the platform picked, which is the
         * phone microphone the virtual pad uses anyway.
         */
        private fun preferOwnEndpoint(
            record: AudioRecord,
            preferredDeviceId: Int,
        ) {
            if (preferredDeviceId == NO_AUDIO_DEVICE) return
            val device =
                audioManager
                    ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    ?.firstOrNull { it.id == preferredDeviceId }
            if (device == null) {
                Log.i(TAG, "endpoint $preferredDeviceId is gone, capturing from the default input")
                return
            }
            if (!record.setPreferredDevice(device)) {
                Log.i(TAG, "endpoint $preferredDeviceId refused, capturing from the default input")
            }
        }

        private class RecorderSession(
            private val record: AudioRecord,
            override val voiceProcessed: Boolean,
        ) : MicCaptureSession {
            override fun read(out: ShortArray): Int {
                var filled = 0
                // Blocking reads normally return the whole request, but the contract only
                // guarantees "up to"; loop so a short read becomes a complete window instead of a
                // packet the far end has to guess at.
                while (filled < out.size) {
                    val n = record.read(out, filled, out.size - filled)
                    if (n <= 0) return filled
                    filled += n
                }
                return filled
            }

            override fun close() {
                // stop() throws if the recorder already died under us; release() still has to run.
                runCatching { record.stop() }
                record.release()
            }
        }

        private companion object {
            const val TAG = "MicCapture"
            const val SAMPLE_RATE = MicEngine.SAMPLE_RATE
            const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
            const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
            const val BYTES_PER_SAMPLE = 2
            const val BUFFERED_FRAMES = 4
        }
    }
