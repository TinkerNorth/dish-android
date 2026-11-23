// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The client's own view of a host's session: its slots, the last heartbeat ack, the round trips
// the latency profiler kept and the datagrams each way by wire message kind.

data class BundleSessionSlot(
    val index: Int,
    val record: String?,
    val requestedType: String?,
    val appliedType: String?,
    val backend: String?,
    val result: String?,
    val touchpadMode: String?,
)

data class BundleAck(
    val backendAvailable: Boolean,
    val activeControllers: Int,
    val epoch: Int,
    val activeBitmap: Int,
)

data class BundleRtt(
    val p50Ms: Double?,
    val p99Ms: Double?,
    val samples: Int,
)

data class BundleSession(
    val slots: List<BundleSessionSlot>,
    val ack: BundleAck?,
    val rtt: BundleRtt?,
    val sent: Map<String, Long>,
    val received: Map<String, Long>,
)

private const val BITMAP_FORMAT = "%04x"

internal fun sessionJson(session: BundleSession): JsonObject =
    buildJsonObject {
        put("slots", JsonArray(session.slots.map(::sessionSlotJson)))
        put("ack", session.ack?.let(::ackJson) ?: JsonNull)
        put("rtt", session.rtt?.let(::rttJson) ?: JsonNull)
        put("sent", countsJson(session.sent))
        put("received", countsJson(session.received))
    }

private fun sessionSlotJson(slot: BundleSessionSlot): JsonObject =
    buildJsonObject {
        put("index", slot.index)
        put("record", slot.record)
        put("requestedType", slot.requestedType)
        put("appliedType", slot.appliedType)
        put("backend", slot.backend)
        put("result", slot.result)
        put("touchpadMode", slot.touchpadMode)
    }

private fun ackJson(ack: BundleAck): JsonObject =
    buildJsonObject {
        put("backendAvailable", ack.backendAvailable)
        put("activeControllers", ack.activeControllers)
        put("epoch", ack.epoch)
        put("activeBitmap", BITMAP_FORMAT.format(ack.activeBitmap))
    }

private fun rttJson(rtt: BundleRtt): JsonObject =
    buildJsonObject {
        put("p50Ms", rtt.p50Ms)
        put("p99Ms", rtt.p99Ms)
        put("samples", rtt.samples)
    }

private fun countsJson(counts: Map<String, Long>): JsonObject = JsonObject(counts.mapValues { JsonPrimitive(it.value) })

// Whatever a host said about itself passes through the same scrub as the log: its own redaction
// is the host's promise, this is the client's. A version reads like an IPv4 address ("1.21.442.0"),
// so a value under a version's key keeps its digits and loses only the forbidden values.
internal fun scrubbedJson(
    element: JsonElement,
    scrub: Scrubber,
): JsonElement =
    when (element) {
        is JsonObject -> JsonObject(element.entries.associate { (key, value) -> scrub(key) to scrubbedField(key, value, scrub) })
        is JsonArray -> JsonArray(element.map { scrubbedJson(it, scrub) })
        is JsonPrimitive -> scrubbedPrimitive(element, scrub::invoke)
    }

private fun scrubbedField(
    key: String,
    value: JsonElement,
    scrub: Scrubber,
): JsonElement {
    val isAVersion = isVersionKey(key) && value is JsonPrimitive
    return if (isAVersion) scrubbedPrimitive(value, scrub::forbiddenOnly) else scrubbedJson(value, scrub)
}

private val VERSION_KEYS = setOf("driver", "bundled")

internal fun isVersionKey(key: String): Boolean {
    val lower = key.lowercase()
    return lower.endsWith("version") || lower in VERSION_KEYS
}

private fun scrubbedPrimitive(
    primitive: JsonPrimitive,
    clean: (String) -> String,
): JsonPrimitive = if (primitive.isString) JsonPrimitive(clean(primitive.content)) else primitive

// A host's own label and address, wherever its snapshot names them, keys included, become its alias.
internal class AliasReplacer(
    names: Set<String>,
    private val alias: String,
) {
    private val values = names.filter { it.isNotBlank() }.sortedByDescending { it.length }

    operator fun invoke(text: String): String {
        var out = text
        for (value in values) out = out.replace(value, alias, ignoreCase = true)
        return out
    }
}

internal fun aliasedJson(
    element: JsonElement,
    alias: AliasReplacer,
): JsonElement =
    when (element) {
        is JsonObject -> JsonObject(element.entries.associate { (key, value) -> alias(key) to aliasedJson(value, alias) })
        is JsonArray -> JsonArray(element.map { aliasedJson(it, alias) })
        is JsonPrimitive -> scrubbedPrimitive(element, alias::invoke)
    }
