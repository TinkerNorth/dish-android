// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.jni

/**
 * Opt-in instrumentation gates. Each costs one relaxed atomic load per report while off.
 */
object InstrumentationNative {
    init {
        System.loadLibrary("satellite")
    }

    // Opt-in latency benchmark: stage-1 USB-direct hot path (URB reap -> sendto) and
    // stage-2 heartbeat RTT. Off by default; one relaxed atomic load when disabled.
    external fun setHotPathBench(on: Boolean)

    // JSON snapshot of the benchmark window (microsecond percentiles). reset clears it.
    external fun hotPathBenchJson(reset: Boolean): String

    // Heartbeat probe mode: densifies pings for RTT sampling while the diagnostics
    // latency panel is open; steady-state cadence resumes when off.
    external fun setLatencyProbe(on: Boolean)

    // Inspector mirror gate: while on, USB-direct reports also copy motion/touch into the
    // snapshot state. Off costs one relaxed atomic load per report, like the bench markers.
    external fun setInputInspection(on: Boolean)
}
