// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.inputrate

import android.os.SystemClock
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.lowpower.LowPowerSignal
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

// Measured input rates, sampled from the hot-path event counters. Controller events come from
// native (framework events or Direct URBs per device), gyro from sensor callbacks or throttled
// Direct motion sends per slot, and screen from one device-wide counter: the phone has a single
// touch surface, so every overlay feeds the same measurement regardless of which slot it drives.
// Sampling pauses while the low-power dim is active so displayed numbers hold still.
@Singleton
class InputRateStore
    @Inject
    constructor(
        private val registry: PhysicalGamepadRegistry,
        private val native: PhysicalInputNative,
        private val lowPowerSignal: LowPowerSignal,
        private val scope: CoroutineScope,
    ) : AbstractStateSource<InputRates>(InputRates()) {
        private class SlotTrackers {
            val controller = InputRateTracker()
            val gyro = InputRateTracker()

            fun rebaseline() {
                controller.rebaseline()
                gyro.rebaseline()
            }

            fun rates(): SlotInputRates =
                SlotInputRates(
                    controllerHz = controller.lastHz,
                    controllerPeakHz = controller.peakHz,
                    gyroHz = gyro.lastHz,
                )
        }

        private val trackers = HashMap<String, SlotTrackers>()
        private val screenTracker = InputRateTracker()

        private val motionCounts = ConcurrentHashMap<String, AtomicLong>()
        private val screenCount = AtomicLong()

        private var rebaselineNeeded = false
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

        // Called per emitted motion sample for routed (Standard/Bluetooth) pads and for the
        // virtual slot's phone gyro; Direct-mode motion is counted natively.
        fun recordMotionSample(slotId: String) {
            motionCounts.computeIfAbsent(slotId) { AtomicLong() }.incrementAndGet()
        }

        // Called per touch-driven input emission from any overlay surface (on-screen gamepad,
        // touchpad pads); all surfaces share the one screen.
        fun recordScreenSample() {
            screenCount.incrementAndGet()
        }

        internal fun sampleAll(nowMs: Long) {
            if (lowPowerSignal.state.value) {
                rebaselineNeeded = true
                return
            }
            val devices = registry.devices.value
            if (rebaselineNeeded) {
                rebaselineNeeded = false
                trackers.values.forEach { it.rebaseline() }
                screenTracker.rebaseline()
            }
            val slotIds = HashSet<String>(devices.size * 2 + 2)
            slotIds.add(VIRTUAL_SLOT_ID)
            val slots = HashMap<String, SlotInputRates>(devices.size * 2 + 2)
            for ((id, device) in devices) {
                val slotId = id.toString()
                slotIds.add(slotId)
                val t = trackers.getOrPut(slotId) { SlotTrackers() }
                if (device.isUsbSynthetic) {
                    t.controller.update(native.getDeviceUrbCount(id), nowMs)
                    t.gyro.update(native.getDeviceMotionCount(id), nowMs)
                } else {
                    t.controller.update(native.getDeviceInputEventCount(id), nowMs)
                    t.gyro.update(motionCounts[slotId]?.get() ?: 0L, nowMs)
                }
                val rates = t.rates()
                if (rates.hasAny) slots[slotId] = rates
            }
            val virtual = trackers.getOrPut(VIRTUAL_SLOT_ID) { SlotTrackers() }
            virtual.gyro.update(motionCounts[VIRTUAL_SLOT_ID]?.get() ?: 0L, nowMs)
            val virtualRates = virtual.rates()
            if (virtualRates.hasAny) slots[VIRTUAL_SLOT_ID] = virtualRates
            screenTracker.update(screenCount.get(), nowMs)
            trackers.keys.retainAll(slotIds)
            motionCounts.keys.retainAll(slotIds)
            setState(InputRates(screenPeakHz = screenTracker.peakHz, slots = slots))
        }

        private companion object {
            const val SAMPLE_INTERVAL_MS = 500L
        }
    }
