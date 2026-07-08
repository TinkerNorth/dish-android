// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry

// Reducer: turns consecutive state snapshots into flight-recorder lines. Pure so the
// event derivation is unit-testable; the recorder just feeds it emissions. Lines stay
// English by design: they are export material for bug reports, like a logcat.
object DiagnosticsLogDiff {
    fun connectionEvents(
        prev: List<ConnectionSummary>,
        next: List<ConnectionSummary>,
    ): List<String> {
        val out = mutableListOf<String>()
        val prevById = prev.associateBy { it.id }
        val nextById = next.associateBy { it.id }
        for ((id, summary) in nextById) {
            val before = prevById[id]
            when {
                before == null -> out += "${summary.label}: appeared (${summary.kind}, ${summary.live})"
                before.live != summary.live -> out += "${summary.label}: ${before.live} -> ${summary.live}"
            }
        }
        for ((id, summary) in prevById) {
            if (id !in nextById) out += "${summary.label}: removed"
        }
        return out
    }

    fun deviceEvents(
        prev: Map<Int, PhysicalGamepadRegistry.Device>,
        next: Map<Int, PhysicalGamepadRegistry.Device>,
    ): List<String> {
        val out = mutableListOf<String>()
        for ((id, device) in next) {
            val before = prev[id]
            if (before == null) {
                val path = if (device.isUsbSynthetic) "USB direct" else device.transport.toString()
                out += "${device.name}: attached ($path, ${vidPid(device)})"
                continue
            }
            if (!before.needsReplug && device.needsReplug) out += "${device.name}: needs replug"
            if (!before.restoreStuck && device.restoreStuck) out += "${device.name}: restore stuck"
            if (before.directFailure == null && device.directFailure != null) {
                out += "${device.name}: direct claim failed (${device.directFailure})"
            }
            if (!before.isDisconnecting && device.isDisconnecting) out += "${device.name}: disconnecting"
        }
        for ((id, device) in prev) {
            if (id !in next) out += "${device.name}: detached"
        }
        return out
    }

    private fun vidPid(device: PhysicalGamepadRegistry.Device): String = "%04x:%04x".format(device.vendorId, device.productId)
}
