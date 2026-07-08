// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The bench measures the full heartbeat round trip; one-way network latency is half of it
// (symmetric-path estimate). The RTT window slides, so the figure answers "now"; the sample
// count travels with it so a barely-seeded window reads as tentative instead of authoritative.
data class LatencyPanel(
    val phonePathP50Ms: Double?,
    val phonePathP99Ms: Double?,
    val pollingJitterP50Ms: Double?,
    val pollingJitterP99Ms: Double?,
    val networkOneWayP50Ms: Double?,
    val rttSamples: Int?,
    val rttHistoryMs: List<Float>,
)

fun parseLatencyPanel(
    rawJson: String,
    json: Json,
): LatencyPanel? {
    val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
    return LatencyPanel(
        phonePathP50Ms = microToMs(root, STAGE1, P50),
        phonePathP99Ms = microToMs(root, STAGE1, P99),
        pollingJitterP50Ms = microToMs(root, URB_GAP, P50),
        pollingJitterP99Ms = microToMs(root, URB_GAP, P99),
        networkOneWayP50Ms = microToMs(root, RTT, P50)?.let { it / 2 },
        rttSamples = intField(root, RTT, "n"),
        rttHistoryMs = rttHistoryMs(root),
    )
}

// Recent full-RTT samples in ms for the sparkline; empty hides it until two samples exist.
private fun rttHistoryMs(root: JsonObject): List<Float> {
    val recent =
        runCatching {
            root[RTT_RECENT]?.jsonArray?.map { it.jsonPrimitive.float / MICROS_PER_MS.toFloat() }
        }.getOrNull() ?: return emptyList()
    return if (recent.size < 2) emptyList() else recent
}

private fun microToMs(
    root: JsonObject,
    group: String,
    field: String,
): Double? {
    val value =
        runCatching {
            root[group]
                ?.jsonObject
                ?.get(field)
                ?.jsonPrimitive
                ?.float
        }.getOrNull() ?: return null
    return value / MICROS_PER_MS
}

private fun intField(
    root: JsonObject,
    group: String,
    field: String,
): Int? =
    runCatching {
        root[group]
            ?.jsonObject
            ?.get(field)
            ?.jsonPrimitive
            ?.int
    }.getOrNull()

private const val MICROS_PER_MS = 1000.0
private const val STAGE1 = "stage1_hotpath_us"
private const val URB_GAP = "urb_gap_us"
private const val RTT = "rtt_us"
private const val RTT_RECENT = "rtt_recent_us"
private const val P50 = "p50"
private const val P99 = "p99"
