// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.core.net.DishProtocol
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.moonlight.MoonlightSessionState
import com.tinkernorth.dish.ui.common.statusChipTextRes

internal fun Context.hostSections(
    host: HostDiag,
    nowMs: Long,
): HostSections =
    HostSections(
        link = hostLinkLines(host, nowMs),
        host =
            when (host.kind) {
                ConnectionKind.SATELLITE -> satelliteHostLines(host)
                ConnectionKind.MOONLIGHT -> moonlightHostLines(host)
                ConnectionKind.BLUETOOTH -> bluetoothHostLines(host)
            },
        network = hostNetworkLines(host),
    )

internal fun Context.hostLinkLines(
    host: HostDiag,
    nowMs: Long,
): List<String> {
    val lines = mutableListOf<String>()
    lines += diagKv(R.string.diagnostics_transport, kindLabel(host.kind))
    lines += diagKv(R.string.diagnostics_link, getString(statusChipTextRes(host.live)))
    if (host.detail.isNotBlank()) lines += host.detail
    host.sameSubnet?.let { lines += diagKv(R.string.diagnostics_same_network, diagYesNo(it)) }
    host.history?.let { history ->
        history.upSinceMs?.let { lines += diagKv(R.string.diagnostics_uptime, durationLabel(nowMs - it)) }
        lines += diagKv(R.string.diagnostics_connects, history.connects.toString())
        lines += diagKv(R.string.diagnostics_drops, history.drops.toString())
        history.lastDropAtMs?.let { lines += diagKv(R.string.diagnostics_last_drop, agoLabel(nowMs, it)) }
    }
    return lines
}

internal fun Context.satelliteHostLines(host: HostDiag): List<String> {
    val lines = mutableListOf<String>()
    val telemetry = host.telemetry
    if (telemetry == null) {
        lines += diagKv(R.string.diagnostics_host, getString(R.string.diagnostics_offline))
    } else {
        val vigem = getString(if (telemetry.vigemAvailable) R.string.diagnostics_available else R.string.diagnostics_unavailable)
        lines += diagKv(R.string.diagnostics_vigem, vigem)
        val max = host.satellite?.facts?.maxControllers
        val active =
            max?.let { getString(R.string.diagnostics_active_of_max, telemetry.activeControllers, it) }
                ?: telemetry.activeControllers.toString()
        lines += diagKv(R.string.diagnostics_active_controllers, active)
        lines += diagKv(R.string.diagnostics_server_epoch, telemetry.epoch.toString())
    }
    host.serverVersion?.let { lines += diagKv(R.string.diagnostics_host_version, it) }
    host.features?.let { features ->
        if (features.protocolVersion > 0) lines += diagKv(R.string.diagnostics_host_protocol, protocolValue(features.protocolVersion))
        lines += diagKv(R.string.diagnostics_host_features, hostFeatureList(features))
    }
    val closeReason = host.satellite?.closeReason ?: -1
    if (closeReason >= 0) lines += diagKv(R.string.diagnostics_close_reason, closeReasonLabel(closeReason))
    return lines
}

private fun Context.protocolValue(version: Int): String {
    val base = getString(R.string.diagnostics_protocol_value, version)
    val compat =
        when (DishProtocol.compatFor(version)) {
            DishProtocol.Compat.CURRENT -> getString(R.string.diagnostics_protocol_current)
            DishProtocol.Compat.SATELLITE_UPDATE_AVAILABLE -> getString(R.string.chip_satellite_update_available)
            DishProtocol.Compat.SATELLITE_UPDATE_REQUIRED -> getString(R.string.chip_satellite_update_required)
            DishProtocol.Compat.APP_UPDATE_REQUIRED -> getString(R.string.chip_app_update_required)
            DishProtocol.Compat.UNKNOWN -> null
        }
    return compat?.let { getString(R.string.diagnostics_joined, base, it) } ?: base
}

private fun Context.closeReasonLabel(reason: Int): String =
    when (reason) {
        SatelliteConnection.CLOSE_REASON_SHUTDOWN -> getString(R.string.diagnostics_close_shutdown)
        SatelliteConnection.CLOSE_REASON_KICKED -> getString(R.string.diagnostics_close_kicked)
        SatelliteConnection.CLOSE_REASON_REPLACED -> getString(R.string.diagnostics_close_replaced)
        else -> reason.toString()
    }

internal fun Context.moonlightHostLines(host: HostDiag): List<String> {
    val lines = mutableListOf<String>()
    val ml = host.moonlight
    lines += diagKv(R.string.diagnostics_session_state, moonlightStateLabel(ml?.state ?: MoonlightSessionState.Idle))
    ml?.facts?.let { facts ->
        if (facts.hostname.isNotBlank()) lines += diagKv(R.string.diagnostics_host_name, facts.hostname)
        facts.appVersion?.takeIf { it.isNotBlank() }?.let { lines += diagKv(R.string.diagnostics_host_app, it) }
        facts.gfeVersion?.takeIf { it.isNotBlank() }?.let { lines += diagKv(R.string.diagnostics_gfe_version, it) }
        val running = if (facts.currentGame != 0) facts.currentGame.toString() else getString(R.string.diagnostics_none)
        lines += diagKv(R.string.diagnostics_running_app, running)
    }
    ml?.pads?.values?.sortedBy { it.number }?.forEach { pad ->
        val sent = ml.reportsByNumber[pad.number] ?: 0L
        lines += diagKv(R.string.diagnostics_reports_sent, getString(R.string.diagnostics_pad_reports, pad.number, sent))
    }
    return lines
}

private fun Context.moonlightStateLabel(state: MoonlightSessionState): String =
    getString(
        when (state) {
            MoonlightSessionState.Idle -> R.string.diagnostics_ml_idle
            MoonlightSessionState.Launching -> R.string.diagnostics_ml_launching
            MoonlightSessionState.Live -> R.string.diagnostics_ml_live
            MoonlightSessionState.Dropped -> R.string.diagnostics_ml_dropped
            MoonlightSessionState.Ended -> R.string.diagnostics_ml_ended
        },
    )

internal fun Context.bluetoothHostLines(host: HostDiag): List<String> {
    val lines = mutableListOf<String>()
    val state = host.bluetooth?.state
    (state?.profileName ?: host.btProfile)?.let { lines += diagKv(R.string.diagnostics_host_profile, it) }
    lines += diagKv(R.string.diagnostics_host_connected_as, state?.connectedName ?: getString(R.string.diagnostics_none))
    lines += diagKv(R.string.diagnostics_reports_sent, (host.bluetooth?.reportsSent ?: 0L).toString())
    return lines
}

internal fun Context.hostNetworkLines(host: HostDiag): List<String> {
    val lines = mutableListOf<String>()
    when (host.kind) {
        ConnectionKind.SATELLITE -> {
            val stats = host.satellite?.stats
            val rtt =
                stats?.rttP50Ms?.let { p50 ->
                    getString(R.string.diagnostics_rtt_value, p50, stats.rttP99Ms ?: p50, p50 / 2, stats.rttSamples)
                } ?: getString(R.string.diagnostics_unknown)
            lines += diagKv(R.string.diagnostics_round_trip, rtt)
            stats?.let {
                lines +=
                    diagKv(R.string.diagnostics_pings, getString(R.string.diagnostics_pings_value, it.pings, it.acks, it.missed))
            }
            host.satellite?.let { lines += diagKv(R.string.diagnostics_packets_sent, it.packetsSent.toString()) }
        }
        ConnectionKind.MOONLIGHT -> {
            val rtt = host.moonlight?.rttMs?.let { getString(R.string.diagnostics_ms_whole, it) } ?: getString(R.string.diagnostics_unknown)
            lines += diagKv(R.string.diagnostics_control_round_trip, rtt)
        }
        ConnectionKind.BLUETOOTH -> lines += getString(R.string.diagnostics_bt_no_round_trip)
    }
    return lines
}
