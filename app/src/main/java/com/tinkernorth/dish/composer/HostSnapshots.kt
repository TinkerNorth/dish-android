// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.net.HttpReply
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection

// What each host could say about itself, and why it said less. A Satellite of 2.3.0 or later
// answers GET /api/connections/{id}/diagnostics within the client's two seconds; an older one is
// never asked and is filled from what the app already fetched from it; a Moonlight host from the
// serverinfo the app already parsed. The host's document is embedded as it arrives: the client
// asserts nothing about its fields.

const val SATELLITE_CAPABILITIES_FORMAT = "satellite-capabilities"
const val MOONLIGHT_SERVERINFO_FORMAT = "moonlight-serverinfo"
const val SATELLITE_DIAGNOSTICS_FORMAT = "satellite-connection-diagnostics.v1"

enum class SnapshotError(
    val wire: String,
) {
    OFFLINE("offline"),
    UNAUTHORIZED("unauthorized"),
    OLDER_SATELLITE("olderSatellite"),
    TIMEOUT("timeout"),

    // A Satellite that has the route did not know this connection.
    UNKNOWN_CONNECTION("unknownConnection"),

    // The host answered, but not with a snapshot: a server error, or a body of another shape.
    BAD_REPLY("badReply"),
}

data class HostSnapshot(
    val snapshot: JsonObject?,
    val error: SnapshotError?,
) {
    companion object {
        val NONE = HostSnapshot(snapshot = null, error = null)
    }
}

// How the diagnostics request ended, as the gateway saw it.
sealed interface SnapshotReply {
    data class Answered(
        val status: Int,
        val body: String,
    ) : SnapshotReply

    data object TimedOut : SnapshotReply

    data object Unreachable : SnapshotReply

    data object NoCredentials : SnapshotReply
}

fun snapshotReplyOf(reply: HttpReply): SnapshotReply =
    when {
        reply.timedOut -> SnapshotReply.TimedOut
        reply.status == NO_ANSWER -> SnapshotReply.Unreachable
        else -> SnapshotReply.Answered(reply.status, reply.body)
    }

private const val NO_ANSWER = 0

fun satelliteHostSnapshot(
    reply: SnapshotReply,
    fallback: JsonObject?,
    json: Json,
): HostSnapshot =
    when (reply) {
        is SnapshotReply.Answered -> answeredSnapshot(reply, fallback, json)
        SnapshotReply.TimedOut -> HostSnapshot(fallback, SnapshotError.TIMEOUT)
        SnapshotReply.Unreachable -> HostSnapshot(fallback, SnapshotError.OFFLINE)
        SnapshotReply.NoCredentials -> HostSnapshot(fallback, SnapshotError.UNAUTHORIZED)
    }

// The first Satellite with GET /api/connections/{id}/diagnostics.
private val ROUTE_SINCE = listOf(2, 3, 0)

// Decided from the serverVersion the app already holds, never from a 404: a host that has the route
// also answers 404 for a connection it does not know. A version the app never read is not older,
// so the route is asked and the answer says what it is.
fun isOlderSatellite(serverVersion: String?): Boolean {
    val parts = serverVersion?.substringBefore('-')?.split('.')?.map { it.toIntOrNull() } ?: return false
    if (parts.isEmpty() || parts.any { it == null }) return false
    val numbers = parts.filterNotNull()
    val padded = ROUTE_SINCE.indices.map { numbers.getOrElse(it) { 0 } }
    val firstDifference = padded.indices.firstOrNull { padded[it] != ROUTE_SINCE[it] } ?: return false
    return padded[firstDifference] < ROUTE_SINCE[firstDifference]
}

// An older Satellite is not asked: what the app fetched from it stands in.
fun olderSatelliteSnapshot(fallback: JsonObject?): HostSnapshot = HostSnapshot(fallback, SnapshotError.OLDER_SATELLITE)

// A satellite whose session is not live is not asked.
fun offlineSatelliteSnapshot(fallback: JsonObject?): HostSnapshot = HostSnapshot(fallback, SnapshotError.OFFLINE)

private fun answeredSnapshot(
    reply: SnapshotReply.Answered,
    fallback: JsonObject?,
    json: Json,
): HostSnapshot {
    val isTheDocument = reply.status == HttpURLConnection.HTTP_OK
    val isUnknown = reply.status == HttpURLConnection.HTTP_NOT_FOUND
    val isRefused = reply.status == HttpURLConnection.HTTP_UNAUTHORIZED || reply.status == HttpURLConnection.HTTP_FORBIDDEN
    val document = if (isTheDocument) documentIn(reply.body, json) else null
    return when {
        document != null -> HostSnapshot(document, error = null)
        isUnknown -> HostSnapshot(fallback, SnapshotError.UNKNOWN_CONNECTION)
        isRefused -> HostSnapshot(fallback, SnapshotError.UNAUTHORIZED)
        else -> HostSnapshot(fallback, SnapshotError.BAD_REPLY)
    }
}

// The contract's document, or nothing: a body of another shape is not this route's answer.
private fun documentIn(
    body: String,
    json: Json,
): JsonObject? {
    val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val format = (parsed["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return parsed.takeIf { format == SATELLITE_DIAGNOSTICS_FORMAT }
}
