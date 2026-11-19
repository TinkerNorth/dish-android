// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.diagnostics.WriteVerdict
import com.tinkernorth.dish.core.diagnostics.errName
import com.tinkernorth.dish.core.diagnostics.reportIdHex
import com.tinkernorth.dish.source.store.TestOutcome
import com.tinkernorth.dish.source.usb.UsbHardwareFacts
import com.tinkernorth.dish.source.usb.UsbInterfaceSummary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// One controller of the bundle, its hardware as hex.

private const val HEX_BYTE_FORMAT = "%02x"
private const val HEX_ID_FORMAT = "%04x"
private const val BYTE_MASK = 0xFF
private const val ID_MASK = 0xFFFF

internal fun controllerJson(
    controller: BundleController,
    createdAtMs: Long,
    scrub: Scrubber,
): JsonObject =
    buildJsonObject {
        put("record", controller.record)
        put("verification", controller.verification)
        put("transport", controller.transport)
        put("path", controller.path)
        put("hardware", controller.hardware?.let { hardwareJson(it, controller.firmware, scrub) } ?: JsonNull)
        put("expectMismatch", JsonArray(controller.expectMismatch.map(::JsonPrimitive)))
        put("rateHz", controller.rateHz)
        put("lastFeedback", controller.lastFeedback?.let(::feedbackJson) ?: JsonNull)
        put("lastWrite", controller.lastWrite?.let { writeJson(it, createdAtMs) } ?: JsonNull)
        put("audio", controller.audio?.let(::audioJson) ?: JsonNull)
        put("outcomes", JsonArray(controller.outcomes.map(::outcomeJson)))
        put("capture", controller.capture?.let(::captureJson) ?: JsonNull)
    }

private fun hardwareJson(
    hardware: UsbHardwareFacts,
    firmware: String?,
    scrub: Scrubber,
): JsonObject =
    buildJsonObject {
        put("vid", HEX_ID_FORMAT.format(hardware.vendorId and ID_MASK))
        put("pid", HEX_ID_FORMAT.format(hardware.productId and ID_MASK))
        put("bcdDevice", hardware.bcdDevice)
        put("product", hardware.product?.let(scrub::invoke))
        put("manufacturer", hardware.manufacturer?.let(scrub::invoke))
        put("interfaces", JsonArray(hardware.interfaces.map(::interfaceJson)))
        put(
            "endpoints",
            buildJsonObject {
                put("in", hardware.endpointIn?.let { JsonPrimitive(hexByte(it)) } ?: JsonNull)
                put("out", hardware.endpointOut?.let { JsonPrimitive(hexByte(it)) } ?: JsonNull)
            },
        )
        put("descriptorSha256", hardware.descriptorSha256)
        put("firmware", firmware)
    }

private fun interfaceJson(intf: UsbInterfaceSummary): JsonObject =
    buildJsonObject {
        put("number", intf.number)
        put("class", hexByte(intf.interfaceClass))
        put("subclass", hexByte(intf.interfaceSubclass))
        put("protocol", hexByte(intf.interfaceProtocol))
    }

private fun hexByte(value: Int): String = HEX_BYTE_FORMAT.format(value and BYTE_MASK)

private fun feedbackJson(feedback: BundleFeedback): JsonObject =
    buildJsonObject {
        put("kind", feedback.kind)
        put("atMs", feedback.atMs)
        put("count", feedback.count)
    }

// The verdict's age, read when the bundle was made, turned back into a time on its clock.
private fun writeJson(
    verdict: WriteVerdict,
    createdAtMs: Long,
): JsonObject =
    buildJsonObject {
        put("route", verdict.route.wire)
        put("reportId", reportIdHex(verdict.reportId))
        put("ok", verdict.ok)
        put("err", if (verdict.ok) JsonNull else JsonPrimitive(errName(verdict.err)))
        put("atMs", createdAtMs - verdict.ageMs)
    }

private fun audioJson(audio: BundleAudio): JsonObject =
    buildJsonObject {
        put("channels", audio.channels)
        put("refused", audio.refused)
    }

private fun outcomeJson(outcome: TestOutcome): JsonObject =
    buildJsonObject {
        put("test", outcome.test)
        put("result", outcome.result)
        put("atMs", outcome.atMs)
    }
