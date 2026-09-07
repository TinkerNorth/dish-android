// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.input.QUIRK_SWAP_AB
import com.tinkernorth.dish.core.input.QUIRK_SWAP_XY
import com.tinkernorth.dish.core.input.QUIRK_SWITCH_LAYOUT
import com.tinkernorth.dish.source.store.StickTestRecord
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L

internal fun Context.agoLabel(
    nowMs: Long,
    atMs: Long,
): String {
    if (atMs <= 0L) return getString(R.string.diagnostics_never)
    val seconds = ((nowMs - atMs) / MS_PER_SECOND).coerceAtLeast(0L)
    val minutes = seconds / SECONDS_PER_MINUTE
    return when {
        minutes < 1 -> getString(R.string.diagnostics_seconds_ago, seconds)
        minutes < MINUTES_PER_HOUR -> getString(R.string.diagnostics_minutes_ago, minutes)
        else -> getString(R.string.diagnostics_hours_ago, minutes / MINUTES_PER_HOUR)
    }
}

internal fun Context.durationLabel(ms: Long): String {
    val seconds = (ms / MS_PER_SECOND).coerceAtLeast(0L)
    val minutes = seconds / SECONDS_PER_MINUTE
    return when {
        minutes < 1 -> getString(R.string.diagnostics_duration_seconds, seconds)
        minutes < MINUTES_PER_HOUR -> getString(R.string.diagnostics_duration_minutes, minutes)
        else -> getString(R.string.diagnostics_duration_hours, minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
    }
}

internal fun Context.p50p99(
    p50: Double?,
    p99: Double?,
): String =
    when {
        p50 == null -> getString(R.string.diagnostics_unknown)
        p99 == null -> getString(R.string.diagnostics_ms, p50)
        else -> getString(R.string.diagnostics_ms_p50_p99, p50, p99)
    }

internal fun Context.quirkLabel(bits: Int): String {
    val names =
        buildList {
            if (bits and QUIRK_SWAP_AB != 0) add(getString(R.string.diagnostics_quirk_swap_ab))
            if (bits and QUIRK_SWAP_XY != 0) add(getString(R.string.diagnostics_quirk_swap_xy))
            if (bits and QUIRK_SWITCH_LAYOUT != 0) add(getString(R.string.diagnostics_quirk_switch_layout))
        }
    return if (names.isEmpty()) getString(R.string.diagnostics_none) else names.joinToString(" · ")
}

internal fun Context.padDeviceLines(
    diag: ControllerDiag,
    nowMs: Long,
): List<String> {
    val facts = diag.facts ?: return emptyList()
    val lines = mutableListOf<String>()
    facts.direct?.let { direct ->
        if (direct.model.isNotBlank()) lines += diagKv(R.string.diagnostics_model, direct.model)
        if (direct.parser.isNotBlank()) lines += diagKv(R.string.diagnostics_host_protocol, direct.parser)
        lines += diagKv(R.string.diagnostics_init, direct.init.ifBlank { getString(R.string.diagnostics_none) })
    }
    facts.endpoint?.let { lines += diagKv(R.string.diagnostics_endpoint, endpointValue(it)) }
    if (diag.isUsbSynthetic) {
        val status = facts.direct?.lastUrbStatus ?: 0
        val count = facts.urbErrors.toString()
        val errors = if (status == 0) count else getString(R.string.diagnostics_joined, count, status.toString())
        lines += diagKv(R.string.diagnostics_urb_errors, errors)
    } else if (facts.quirkBits != 0) {
        lines += diagKv(R.string.diagnostics_quirk, quirkLabel(facts.quirkBits))
    }
    bluetoothLinkTypeLabel(facts.linkType)?.let { lines += diagKv(R.string.diagnostics_bt_link_type, it) }
    val countLabel = if (diag.isUsbSynthetic) R.string.diagnostics_reports else R.string.diagnostics_events_count
    lines += diagKv(countLabel, facts.reportCount.toString())
    lines += diagKv(R.string.diagnostics_last_input, agoLabel(nowMs, facts.lastInputAtMs))
    lines += padTimingLines(facts)
    lines += stickHistoryLines(facts.stickHistory)
    return lines
}

internal fun Context.padTimingLines(facts: PadFacts): List<String> {
    val lines = mutableListOf<String>()
    facts.directTiming?.takeIf { it.samples > 0 }?.let { timing ->
        lines += diagKv(R.string.diagnostics_phone_path, p50p99(timing.stage1P50Ms, timing.stage1P99Ms))
        lines += diagKv(R.string.diagnostics_polling_jitter, p50p99(timing.gapP50Ms, timing.gapP99Ms))
    }
    facts.frameworkTiming?.let { timing ->
        lines += diagKv(R.string.diagnostics_event_gap, p50p99(timing.gapP50Ms.toDouble(), timing.gapP99Ms.toDouble()))
        lines += diagKv(R.string.diagnostics_delivery_delay, p50p99(timing.delayP50Ms.toDouble(), timing.delayP99Ms.toDouble()))
    }
    return lines
}

private fun Context.stickHistoryLines(record: StickTestRecord?): List<String> {
    record ?: return emptyList()
    val lines = mutableListOf<String>()
    val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    record.driftAtMs?.let { at ->
        lines +=
            diagKv(
                R.string.diagnostics_last_drift,
                getString(
                    R.string.diagnostics_drift_history,
                    percentLabel(record.driftLeft),
                    percentLabel(record.driftRight),
                    percentLabel(record.suggestedDeadzone),
                    format.format(Date(at)),
                ),
            )
    }
    record.rangeAtMs?.let { at ->
        lines +=
            diagKv(
                R.string.diagnostics_last_range,
                getString(
                    R.string.diagnostics_range_history,
                    percentLabel(record.reachLeft),
                    percentLabel(record.reachRight),
                    format.format(Date(at)),
                ),
            )
    }
    return lines
}

internal fun Context.percentLabel(fraction: Float?): String =
    fraction?.let { getString(R.string.inspector_percent, (it * 100).roundToInt()) } ?: getString(R.string.inspector_na)
