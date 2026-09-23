// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind

internal fun Context.hostNetworkLines(host: HostDiag): List<String> =
    when (host.kind) {
        ConnectionKind.SATELLITE -> satelliteNetworkLines(host)
        ConnectionKind.MOONLIGHT -> moonlightNetworkLines(host)
        ConnectionKind.BLUETOOTH -> listOf(getString(R.string.diagnostics_bt_no_round_trip))
    }

private fun Context.satelliteNetworkLines(host: HostDiag): List<String> {
    val stats = host.satellite?.stats
    val lines = mutableListOf(diagKv(R.string.diagnostics_round_trip, satelliteRttText(stats)))
    stats?.let { lines += diagKv(R.string.diagnostics_pings, pingCountsText(it)) }
    host.satellite?.let { lines += diagKv(R.string.diagnostics_packets_sent, it.packetsSent.toString()) }
    return lines
}

// One-way is reported as half the round trip, which is all a single-ended measurement can say.
private fun Context.satelliteRttText(stats: SatelliteSessionStats?): String {
    val p50 = stats?.rttP50Ms ?: return getString(R.string.diagnostics_unknown)
    return getString(
        R.string.diagnostics_rtt_value,
        p50,
        stats.rttP99Ms ?: p50,
        p50 / 2,
        resources.getQuantityString(R.plurals.diagnostics_pings_count, stats.rttSamples, stats.rttSamples),
    )
}

private fun Context.pingCountsText(stats: SatelliteSessionStats): String =
    getString(
        R.string.diagnostics_pings_value,
        resources.getQuantityString(R.plurals.diagnostics_pings_sent, stats.pings.pluralCount(), stats.pings),
        resources.getQuantityString(R.plurals.diagnostics_pings_acked, stats.acks.pluralCount(), stats.acks),
        resources.getQuantityString(R.plurals.diagnostics_pings_missed, stats.missed, stats.missed),
    )

private fun Context.moonlightNetworkLines(host: HostDiag): List<String> {
    val rtt =
        host.moonlight?.rttMs?.let { getString(R.string.diagnostics_ms_whole, it) }
            ?: getString(R.string.diagnostics_unknown)
    return listOf(diagKv(R.string.diagnostics_control_round_trip, rtt))
}
