// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.diagnostics.WriteVerdict
import com.tinkernorth.dish.source.store.FeedbackKind
import com.tinkernorth.dish.source.store.pairFirst
import com.tinkernorth.dish.source.store.pairSecond
import com.tinkernorth.dish.source.store.rgbBlue
import com.tinkernorth.dish.source.store.rgbGreen
import com.tinkernorth.dish.source.store.rgbRed
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// What went into a controller and what came out of it, whatever path reads it: counts and the
// last value, never a stream.

// The last state the slot published, as gamepad_input.h holds it.
data class BundleInputState(
    val buttons: Int,
    val lx: Int,
    val ly: Int,
    val rx: Int,
    val ry: Int,
    val lt: Int,
    val rt: Int,
    val ext: Int,
)

data class BundleInput(
    val frames: Long?,
    val rateHz: Int?,
    val last: BundleInputState?,
)

// One output lane: how many arrived, and the last one's packed value and time.
data class BundleLane(
    val count: Long,
    val value: Long,
    val atMs: Long,
)

enum class VoiceState(
    val wire: String,
) {
    OPEN("open"),
    CLOSED("closed"),
    REFUSED("refused"),
}

enum class VoiceDevice(
    val wire: String,
) {
    PAD("pad"),
    DEFAULT("default"),
}

data class BundleVoice(
    val voice: VoiceState,
    val device: VoiceDevice?,
    val frames: Long?,
)

data class BundleOutput(
    val target: String,
    val lanes: Map<FeedbackKind, BundleLane>,
    val lastWrite: WriteVerdict?,
    val speaker: BundleVoice,
    val haptics: BundleVoice,
)

// A pad the phone's input framework reads.
data class BundleStandard(
    val sources: List<String>,
    val vibrator: Boolean?,
    val lights: Boolean?,
)

// A Bluetooth pad by its profile and transport layer, never by its address.
data class BundleBluetooth(
    val profile: String,
    val transportLayer: String?,
)

private const val HEX_WORD_FORMAT = "%04x"
private const val HEX_BYTE_FORMAT = "%02x"

internal fun feedbackWire(kind: FeedbackKind): String =
    when (kind) {
        FeedbackKind.RUMBLE -> "rumble"
        FeedbackKind.TRIGGER_RUMBLE -> "triggerRumble"
        FeedbackKind.LIGHTBAR -> "lightbar"
        FeedbackKind.PLAYER_LEDS -> "playerLeds"
        FeedbackKind.TRIGGER_EFFECTS -> "triggerEffects"
        FeedbackKind.MIC_LED -> "micLed"
    }

internal fun inputJson(input: BundleInput): JsonObject =
    buildJsonObject {
        put("frames", input.frames)
        put("rateHz", input.rateHz)
        put("last", input.last?.let(::inputStateJson) ?: JsonNull)
    }

private fun inputStateJson(state: BundleInputState): JsonObject =
    buildJsonObject {
        put("buttons", HEX_WORD_FORMAT.format(state.buttons))
        put("lx", state.lx)
        put("ly", state.ly)
        put("rx", state.rx)
        put("ry", state.ry)
        put("lt", state.lt)
        put("rt", state.rt)
        put("ext", HEX_WORD_FORMAT.format(state.ext))
    }

internal fun outputJson(
    output: BundleOutput,
    lastWrite: JsonObject?,
): JsonObject =
    buildJsonObject {
        FeedbackKind.entries.forEach { kind -> put(feedbackWire(kind), laneJson(kind, output, lastWrite)) }
        put("speaker", voiceJson(output.speaker))
        put("haptics", voiceJson(output.haptics))
    }

private fun laneJson(
    kind: FeedbackKind,
    output: BundleOutput,
    lastWrite: JsonObject?,
): JsonObject {
    val lane = output.lanes[kind]
    return buildJsonObject {
        put("count", lane?.count ?: 0L)
        put("target", output.target)
        put("last", lane?.let { laneLastJson(kind, it) } ?: JsonNull)
        if (kind == FeedbackKind.RUMBLE) put("lastWrite", lastWrite ?: JsonNull)
    }
}

private fun laneLastJson(
    kind: FeedbackKind,
    lane: BundleLane,
): JsonObject =
    buildJsonObject {
        laneValue(kind, lane.value)
        put("atMs", lane.atMs)
    }

private fun JsonObjectBuilder.laneValue(
    kind: FeedbackKind,
    value: Long,
) {
    when (kind) {
        FeedbackKind.RUMBLE -> {
            put("strong", pairFirst(value))
            put("weak", pairSecond(value))
        }
        FeedbackKind.TRIGGER_RUMBLE -> {
            put("left", pairFirst(value))
            put("right", pairSecond(value))
        }
        FeedbackKind.LIGHTBAR -> {
            put("r", rgbRed(value))
            put("g", rgbGreen(value))
            put("b", rgbBlue(value))
        }
        FeedbackKind.PLAYER_LEDS -> put("mask", HEX_BYTE_FORMAT.format(value))
        FeedbackKind.MIC_LED -> put("state", value.toInt())
        FeedbackKind.TRIGGER_EFFECTS -> Unit
    }
}

private fun voiceJson(voice: BundleVoice): JsonObject =
    buildJsonObject {
        put("voice", voice.voice.wire)
        put("device", voice.device?.wire)
        put("frames", voice.frames)
    }

internal fun standardJson(standard: BundleStandard): JsonObject =
    buildJsonObject {
        put("sources", JsonArray(standard.sources.map(::JsonPrimitive)))
        put("vibrator", standard.vibrator)
        put("lights", standard.lights)
    }

internal fun bluetoothJson(bluetooth: BundleBluetooth): JsonObject =
    buildJsonObject {
        put("profile", bluetooth.profile)
        put("transportLayer", bluetooth.transportLayer)
    }
