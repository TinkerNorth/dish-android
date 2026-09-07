// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

private const val MICROS_PER_MS = 1000.0

private fun parseObject(
    json: Json,
    raw: String,
): JsonObject? = if (raw.isEmpty()) null else runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()

private fun JsonObject.groupMs(
    group: String,
    field: String,
): Double? =
    runCatching {
        this[group]
            ?.jsonObject
            ?.get(field)
            ?.jsonPrimitive
            ?.float
    }.getOrNull()?.let { it / MICROS_PER_MS }

private fun JsonObject.groupInt(
    group: String,
    field: String,
): Int? =
    runCatching {
        this[group]
            ?.jsonObject
            ?.get(field)
            ?.jsonPrimitive
            ?.int
    }.getOrNull()

private fun JsonObject.longField(name: String): Long? = runCatching { this[name]?.jsonPrimitive?.long }.getOrNull()

private fun JsonObject.intField(name: String): Int? = runCatching { this[name]?.jsonPrimitive?.int }.getOrNull()

private fun JsonObject.stringField(name: String): String? = runCatching { this[name]?.jsonPrimitive?.content }.getOrNull()

internal fun parseDeviceLatency(
    json: Json,
    raw: String,
): DeviceLatency? {
    val root = parseObject(json, raw) ?: return null
    val samples = root.groupInt("stage1_hotpath_us", "n") ?: 0
    return DeviceLatency(
        samples = samples,
        stage1P50Ms = root.groupMs("stage1_hotpath_us", "p50"),
        stage1P99Ms = root.groupMs("stage1_hotpath_us", "p99"),
        gapP50Ms = root.groupMs("urb_gap_us", "p50"),
        gapP99Ms = root.groupMs("urb_gap_us", "p99"),
    )
}

internal fun parseDeviceInfo(
    json: Json,
    raw: String,
): DirectDeviceInfo? {
    val root = parseObject(json, raw) ?: return null
    return DirectDeviceInfo(
        model = root.stringField("model").orEmpty(),
        parser = root.stringField("parser").orEmpty(),
        init = root.stringField("init").orEmpty(),
        reportBytes = root.intField("reportBytes") ?: 0,
        endpointOut = runCatching { root["endpointOut"]?.jsonPrimitive?.boolean }.getOrNull() ?: false,
        lastUrbStatus = root.intField("lastUrbStatus") ?: 0,
    )
}

internal fun parseSessionStats(
    json: Json,
    raw: String,
): SatelliteSessionStats? {
    val root = parseObject(json, raw) ?: return null
    val recent =
        runCatching {
            root["rtt_recent_us"]?.jsonArray?.map { (it.jsonPrimitive.float / MICROS_PER_MS).toFloat() }
        }.getOrNull().orEmpty()
    return SatelliteSessionStats(
        rttP50Ms = root.groupMs("rtt_us", "p50"),
        rttP99Ms = root.groupMs("rtt_us", "p99"),
        rttSamples = root.groupInt("rtt_us", "n") ?: 0,
        rttRecentMs = if (recent.size < 2) emptyList() else recent,
        pings = root.longField("pings") ?: 0L,
        acks = root.longField("acks") ?: 0L,
        missed = root.intField("missed") ?: 0,
    )
}
