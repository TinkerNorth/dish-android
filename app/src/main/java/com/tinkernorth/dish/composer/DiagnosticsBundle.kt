// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.diagnostics.WriteVerdict
import com.tinkernorth.dish.source.controllerdb.ControllerDbStatus
import com.tinkernorth.dish.source.store.DiagnosticsLogEntry
import com.tinkernorth.dish.source.store.TestOutcome
import com.tinkernorth.dish.source.usb.UsbHardwareFacts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// The diagnostics bundle, dish-diagnostics.v1 (dish-website docs/diagnostics-bundle.md), built from
// snapshots the app already holds. It never holds pairing keys, pairing secrets, certificate
// fingerprints, IP addresses, Wi-Fi names or the names of hosts: hosts are "Host 1", "Host 2",
// and every free text (the flight recorder, logcat) is scrubbed of the values the caller names and
// of anything shaped like an address.

const val BUNDLE_VERSION = 1
const val BUNDLE_APP_NAME = "dish-android"

data class BundleApp(
    val version: String,
    val build: String,
    val flavor: String,
)

data class BundleBattery(
    val level: Int?,
    val charging: Boolean?,
)

// The phone's Wi-Fi link by its radio facts alone: never its name or its address.
data class BundleWifi(
    val band: String?,
    val rssiDbm: Int,
    val linkSpeedMbps: Int,
)

// A fact the platform does not expose, or that could not be read, is null.
data class BundleDevice(
    val model: String,
    val os: String,
    val arch: String,
    val battery: BundleBattery? = null,
    val thermal: String? = null,
    val powerSave: Boolean? = null,
    val wifi: BundleWifi? = null,
    val usbHost: Boolean? = null,
)

data class BundleFeedback(
    val kind: String,
    val atMs: Long,
    val count: Long,
)

data class BundleAudio(
    val channels: Int,
    val refused: Boolean,
)

const val CAPTURE_FORMAT = "usb-descriptors-v1"

// Section "Capture", format usb-descriptors-v1: one controller the person chose, as hex. The
// strings are the descriptors by their index; the serial number is never among them.
data class BundleCapture(
    val device: String?,
    val configuration: String?,
    val strings: Map<String, String>,
    val hidReportDescriptors: List<BundleHidDescriptor>,
    val restReports: List<BundleCapturedReport>,
)

data class BundleHidDescriptor(
    val interfaceNumber: Int,
    val hex: String,
)

data class BundleCapturedReport(
    val atMs: Long,
    val hex: String,
)

data class BundleController(
    val record: String?,
    val verification: String?,
    val transport: String?,
    val path: String,
    val hardware: UsbHardwareFacts?,
    val firmware: String?,
    val expectMismatch: List<String>,
    val rateHz: Int,
    val lastFeedback: BundleFeedback?,
    val lastWrite: WriteVerdict?,
    val audio: BundleAudio?,
    val outcomes: List<TestOutcome>,
    val capture: BundleCapture? = null,
    val input: BundleInput? = null,
    val output: BundleOutput? = null,
    val standard: BundleStandard? = null,
    val bluetooth: BundleBluetooth? = null,
)

data class BundleGrants(
    val mic: Boolean,
    val speaker: Boolean,
    val hapticAudio: Boolean,
)

// A host by what it is, never by what it is called or where it is: the client's own view of the
// session, then whatever the host itself was able to say.
data class BundleHost(
    val kind: String,
    val version: String?,
    val protocol: Int?,
    val link: String,
    val grants: BundleGrants?,
    val session: BundleSession? = null,
    val snapshot: HostSnapshot = HostSnapshot.NONE,
    // The host's own label and address, which its snapshot names as its alias instead.
    val ownNames: Set<String> = emptySet(),
)

data class BundleInputs(
    val createdAtMs: Long,
    val app: BundleApp,
    val device: BundleDevice,
    val controllerDb: ControllerDbStatus,
    val controllers: List<BundleController>,
    val hosts: List<BundleHost>,
    val log: List<DiagnosticsLogEntry>,
    val logcat: String,
    // Every value the app knows must not leave: host labels, connection ids, Wi-Fi names, keys.
    val forbidden: Set<String>,
    // The person's own report when they started from "Report a problem"; null otherwise.
    val report: BundleReport? = null,
)

fun diagnosticsBundle(inputs: BundleInputs): JsonObject {
    val scrub = Scrubber(inputs.forbidden)
    return buildJsonObject {
        put("bundle", BUNDLE_VERSION)
        put("createdAt", isoUtc(inputs.createdAtMs))
        put("report", inputs.report?.let(::reportJson) ?: JsonNull)
        put("app", appJson(inputs.app))
        put("device", deviceJson(inputs.device))
        put("controllerDb", controllerDbJson(inputs.controllerDb))
        put("controllers", JsonArray(inputs.controllers.map { controllerJson(it, inputs.createdAtMs, scrub) }))
        put("hosts", JsonArray(inputs.hosts.mapIndexed { index, host -> hostJson(index, host, scrub) }))
        put("log", JsonArray(inputs.log.map { logJson(it, scrub) }))
        put("extra", buildJsonObject { put("logcat", scrub(inputs.logcat)) })
    }
}

fun diagnosticsBundleText(inputs: BundleInputs): String = BUNDLE_JSON.encodeToString(JsonObject.serializer(), diagnosticsBundle(inputs))

private val BUNDLE_JSON = Json { prettyPrint = true }

// "dish-diagnostics-dish-android-20261119-210411.json", in UTC like createdAt.
fun bundleFileName(createdAtMs: Long): String = "dish-diagnostics-$BUNDLE_APP_NAME-${utc("yyyyMMdd-HHmmss", createdAtMs)}.json"

fun isoUtc(ms: Long): String = utc("yyyy-MM-dd'T'HH:mm:ss'Z'", ms)

private fun utc(
    pattern: String,
    ms: Long,
): String {
    val format = SimpleDateFormat(pattern, Locale.ROOT)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date(ms))
}
