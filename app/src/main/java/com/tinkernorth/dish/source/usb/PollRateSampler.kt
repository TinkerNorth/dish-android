// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

import android.os.SystemClock
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PollRateSampler
    @Inject
    constructor(
        private val registry: PhysicalGamepadRegistry,
        private val scope: CoroutineScope,
        private val native: PhysicalInputNative,
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
                        sampleAll(SystemClock.elapsedRealtime())
                    }
                }
        }

        internal fun sampleAll(nowMs: Long) {
            val present = registry.devices.value
            for ((id, device) in present) {
                if (!device.isUsbSynthetic) continue
                val count = native.getDeviceUrbCount(id)
                val prev = snapshots[id]
                snapshots[id] = Snapshot(count, nowMs)
                if (prev == null) continue
                registry.updateMeasuredPollRate(
                    id,
                    measuredPollRateHz(count - prev.count, nowMs - prev.elapsedMs),
                )
            }
            snapshots.keys.retainAll(present.keys)
        }

        private companion object {
            const val SAMPLE_INTERVAL_MS = 500L
        }
    }
