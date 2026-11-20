// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// {"sent":{"input":n,...},"received":{"rumble":n,...}}; null for a session that closed.
internal fun parseMessageCounts(
    json: Json,
    raw: String,
): SessionMessageCounts? {
    if (raw.isEmpty()) return null
    val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    return SessionMessageCounts(sent = root.counts(SENT), received = root.counts(RECEIVED))
}

private fun JsonObject.counts(group: String): Map<String, Long> =
    runCatching { get(group)?.jsonObject?.mapValues { it.value.jsonPrimitive.long } }.getOrNull().orEmpty()

private const val SENT = "sent"
private const val RECEIVED = "received"
