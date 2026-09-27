// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.composer.ConnectionKind

data class HostLatencyRow(
    val label: String,
    val kind: ConnectionKind,
    val oneWayMs: Double?,
    val samples: Int,
    val controlRttMs: Long?,
)

data class PadLatencyRow(
    val name: String,
    val facts: PadFacts,
)

data class LatencyRows(
    val hosts: List<HostLatencyRow>,
    val pads: List<PadLatencyRow>,
)

// The one figure a host's latency row can show: Moonlight measures its control round trip,
// a satellite halves its heartbeat round trip over the last pings, and anything without a
// measurement reads unknown.
internal sealed interface HostLatencyFigure {
    data class ControlRoundTrip(
        val ms: Long,
    ) : HostLatencyFigure

    data class OneWay(
        val ms: Double,
        val samples: Int,
    ) : HostLatencyFigure

    data object Unknown : HostLatencyFigure
}

internal fun hostLatencyFigure(row: HostLatencyRow): HostLatencyFigure =
    when {
        row.kind == ConnectionKind.MOONLIGHT ->
            row.controlRttMs?.let { HostLatencyFigure.ControlRoundTrip(it) } ?: HostLatencyFigure.Unknown
        row.oneWayMs != null -> HostLatencyFigure.OneWay(row.oneWayMs, row.samples)
        else -> HostLatencyFigure.Unknown
    }

internal fun latencyRows(
    controllers: List<ControllerDiag>,
    hosts: List<HostDiag>,
): LatencyRows =
    LatencyRows(
        hosts =
            hosts
                .filter { it.kind != ConnectionKind.BLUETOOTH }
                .map { host ->
                    val stats = host.satellite?.stats
                    HostLatencyRow(
                        label = host.label,
                        kind = host.kind,
                        oneWayMs = stats?.rttP50Ms?.let { it / 2 },
                        samples = stats?.rttSamples ?: 0,
                        controlRttMs = host.moonlight?.rttMs,
                    )
                },
        pads =
            controllers.mapNotNull { pad ->
                val facts = pad.facts ?: return@mapNotNull null
                val measured = facts.directTiming?.takeIf { it.samples > 0 } != null || facts.frameworkTiming != null
                if (measured) PadLatencyRow(pad.name, facts) else null
            },
    )
