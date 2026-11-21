// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CatalogDto
import com.tinkernorth.dish.core.model.ServerBackendDto
import com.tinkernorth.dish.core.model.ServerCapabilitiesDto
import com.tinkernorth.dish.core.model.ServerControllerAudioDto
import com.tinkernorth.dish.source.store.MoonlightHostFacts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// What a host that cannot speak for itself is told by: what the app already read from it.

// The capabilities and catalog the app already fetched from this satellite; null when it fetched
// neither. Capabilities name only the ceiling the host speaks; every Satellite accepts from 1.
fun capabilitiesSnapshotOf(
    caps: ServerCapabilitiesDto?,
    catalog: CatalogDto?,
): JsonObject? {
    val nothingFetched = caps == null && catalog == null
    if (nothingFetched) return null
    val serverVersion = caps?.serverVersion?.takeIf { it.isNotBlank() } ?: catalog?.serverVersion?.takeIf { it.isNotBlank() }
    val protocol = caps?.protocolVersion ?: catalog?.protocolVersion
    return buildJsonObject {
        put("format", SATELLITE_CAPABILITIES_FORMAT)
        put("serverVersion", serverVersion)
        put("protocol", JsonArray(listOf(JsonPrimitive(SATELLITE_PROTOCOL_FLOOR), JsonPrimitive(protocol))))
        put("backends", JsonArray(caps?.backends.orEmpty().map(::backendJson)))
        put("controllerAudio", caps?.controllerAudio?.let(::controllerAudioJson) ?: JsonNull)
        put("catalogVersion", catalog?.catalogVersion)
    }
}

private fun backendJson(backend: ServerBackendDto): JsonObject =
    buildJsonObject {
        put("id", backend.id)
        put("supported", backend.supported)
        put("available", backend.available)
        put("errorCode", backend.errorCode)
        put("audio", backend.audio)
    }

private fun controllerAudioJson(audio: ServerControllerAudioDto): JsonObject =
    buildJsonObject {
        put("enabled", audio.enabled)
        put("mic", audio.mic)
        put("speaker", audio.speaker)
        put("hapticAudio", audio.hapticAudio)
    }

private const val SATELLITE_PROTOCOL_FLOOR = 1

// The host's version from its serverinfo, never its hostname. The plaintext serverinfo reports every
// host free and every caller unpaired, so its state and pairing are null rather than wrong.
fun moonlightSnapshotOf(facts: MoonlightHostFacts?): JsonObject? =
    facts?.let {
        buildJsonObject {
            put("format", MOONLIGHT_SERVERINFO_FORMAT)
            put("appVersion", it.appVersion)
            put("state", JsonNull)
            put("pairStatus", JsonNull)
        }
    }
