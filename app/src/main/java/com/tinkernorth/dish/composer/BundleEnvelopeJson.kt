// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.source.controllerdb.ControllerDbStatus
import com.tinkernorth.dish.source.store.DiagnosticsLogEntry
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The bundle around its controllers: the app, the device, the database, the hosts and the log.
internal fun appJson(app: BundleApp): JsonObject =
    buildJsonObject {
        put("name", BUNDLE_APP_NAME)
        put("version", app.version)
        put("build", app.build)
        put("flavor", app.flavor)
    }

internal fun deviceJson(device: BundleDevice): JsonObject =
    buildJsonObject {
        put("model", device.model)
        put("os", device.os)
        put("arch", device.arch)
    }

internal fun controllerDbJson(status: ControllerDbStatus): JsonObject =
    buildJsonObject {
        put("bundledVersion", status.bundledVersion)
        put("activeVersion", status.activeVersion)
        put("source", status.source.wire)
        put("lastCheckAt", status.lastCheckAtMs.takeIf { it > 0L }?.let { JsonPrimitive(isoUtc(it)) } ?: JsonNull)
        put("lastCheckResult", status.lastCheckResult?.let { JsonPrimitive(it.wire) } ?: JsonNull)
    }

internal fun hostJson(
    index: Int,
    host: BundleHost,
): JsonObject =
    buildJsonObject {
        put("alias", "Host ${index + 1}")
        put("kind", host.kind)
        put("version", host.version)
        put("protocol", host.protocol)
        put("link", host.link)
        put("grants", host.grants?.let(::grantsJson) ?: JsonNull)
    }

private fun grantsJson(grants: BundleGrants): JsonObject =
    buildJsonObject {
        put("mic", grants.mic)
        put("speaker", grants.speaker)
        put("hapticAudio", grants.hapticAudio)
    }

internal fun logJson(
    entry: DiagnosticsLogEntry,
    scrub: Scrubber,
): JsonObject =
    buildJsonObject {
        put("atMs", entry.atMs)
        put("tag", entry.tag)
        put("message", scrub(entry.message))
    }
