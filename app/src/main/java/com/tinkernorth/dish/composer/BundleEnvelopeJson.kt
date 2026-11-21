// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.source.controllerdb.ControllerDbStatus
import com.tinkernorth.dish.source.store.DiagnosticsLogEntry
import kotlinx.serialization.json.JsonElement
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
        put("battery", device.battery?.let(::batteryJson) ?: JsonNull)
        put("thermal", device.thermal)
        put("powerSave", device.powerSave)
        put("wifi", device.wifi?.let(::wifiJson) ?: JsonNull)
        put("usbHost", device.usbHost)
    }

private fun batteryJson(battery: BundleBattery): JsonObject =
    buildJsonObject {
        put("level", battery.level)
        put("charging", battery.charging)
    }

private fun wifiJson(wifi: BundleWifi): JsonObject =
    buildJsonObject {
        put("band", wifi.band)
        put("rssiDbm", wifi.rssiDbm)
        put("linkSpeedMbps", wifi.linkSpeedMbps)
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
    scrub: Scrubber,
): JsonObject =
    buildJsonObject {
        put("alias", hostAlias(index))
        put("kind", host.kind)
        put("version", host.version)
        put("protocol", host.protocol)
        put("link", host.link)
        put("grants", host.grants?.let(::grantsJson) ?: JsonNull)
        put("session", host.session?.let(::sessionJson) ?: JsonNull)
        put("snapshot", host.snapshot.snapshot?.let { snapshotJson(it, host.ownNames, hostAlias(index), scrub) } ?: JsonNull)
        put("snapshotError", host.snapshot.error?.wire)
    }

internal fun hostAlias(index: Int): String = "Host ${index + 1}"

private fun snapshotJson(
    snapshot: JsonObject,
    ownNames: Set<String>,
    alias: String,
    scrub: Scrubber,
): JsonElement {
    val aliased = aliasedJson(snapshot, AliasReplacer(ownNames, alias))
    return scrubbedJson(aliased, scrub)
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
