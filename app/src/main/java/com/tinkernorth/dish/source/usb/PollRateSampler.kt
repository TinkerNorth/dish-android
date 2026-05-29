// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

import android.os.SystemClock
import com.tinkernorth.dish.core.jni.SatelliteNative
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// Periodically reads the native URB-completion counter for each direct-mode controller, derives
// an instantaneous Hz from the delta over the sampling window, and pushes the result into the
// PhysicalGamepadRegistry. The UI then updates through the existing reactive flow.
//
// The sampler holds one snapshot per known device. Devices that disappear between samples are
// pruned. A device whose count hasn't moved (no input, no kernel completions) reports 0 Hz
// rather than freezing at the previous reading, which matches the user's intuition: "if I'm not
// holding it and nothing's polling, the rate is zero."
@Singleton
class PollRateSampler
    @Inject
    constructor(
        private val registry: PhysicalGamepadRegistry,
        private val scope: CoroutineScope,
    ) {
        private data class Snapshot(
            val count: Long,
            val elapsedMs: Long,
        )

        private val snapshots = HashMap<Int, Snapshot>()
        private var job: Job? = null

        fun install() {
            if (job != null) return
            job =
                scope.launch {
                    while (true) {
                        delay(SAMPLE_INTERVAL_MS)
                        sampleAll()
                    }
                }
        }

        private fun sampleAll() {
            val nowMs = SystemClock.elapsedRealtime()
            val present = registry.devices.value
            for ((id, device) in present) {
                if (!device.isUsbSynthetic) continue
                val count = SatelliteNative.getDeviceUrbCount(id)
                val prev = snapshots[id]
                snapshots[id] = Snapshot(count, nowMs)
                if (prev == null) continue
                val deltaCount = count - prev.count
                val deltaMs = nowMs - prev.elapsedMs
                if (deltaMs <= 0) continue
                val rate = ((deltaCount * MILLIS_PER_SEC) / deltaMs).toInt()
                registry.updateMeasuredPollRate(id, rate)
            }
            snapshots.keys.retainAll(present.keys)
        }

        private companion object {
            // 500 ms gives a responsive but stable reading: long enough that a 1 ms-poll-rate pad
            // accumulates ~500 samples (smoothing transients), short enough that a controller
            // plug-in shows a live rate within a second.
            const val SAMPLE_INTERVAL_MS = 500L
            const val MILLIS_PER_SEC = 1000L
        }
    }
