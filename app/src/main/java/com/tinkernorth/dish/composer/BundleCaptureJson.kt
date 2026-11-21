// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// A controller capture, format usb-descriptors-v1.
internal fun captureJson(capture: BundleCapture): JsonObject =
    buildJsonObject {
        put("format", CAPTURE_FORMAT)
        put("device", capture.device)
        put("configuration", capture.configuration)
        put("strings", JsonObject(capture.strings.mapValues { JsonPrimitive(it.value) }))
        put("hidReportDescriptors", JsonArray(capture.hidReportDescriptors.map(::hidDescriptorJson)))
        put(
            "restReports",
            buildJsonArray {
                capture.restReports.forEach { report -> add(capturedReportJson(report)) }
            },
        )
    }

// Every descriptor here is read off the pad with a control transfer, never the OS's copy.
private const val SOURCE_DEVICE = "device"

private fun hidDescriptorJson(descriptor: BundleHidDescriptor): JsonObject =
    buildJsonObject {
        put("interface", descriptor.interfaceNumber)
        put("hex", descriptor.hex)
        put("source", SOURCE_DEVICE)
    }

private fun capturedReportJson(report: BundleCapturedReport): JsonElement =
    buildJsonObject {
        put("atMs", report.atMs)
        put("hex", report.hex)
    }
