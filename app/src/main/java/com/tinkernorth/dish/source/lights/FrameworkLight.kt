// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.lights

// One light the framework lists for an input device, lifted off android.hardware.lights.Light so
// the selection rule is JVM-testable. `input` is the LIGHT_TYPE_INPUT class (a single mono or RGB
// LED, as opposed to a player-id row or a keyboard backlight); `rgb` is what hasRgbControl reports.
data class FrameworkLight(
    val id: Int,
    val name: String,
    val input: Boolean,
    val rgb: Boolean,
)

// The host binding a light bar color arrived under: the connection, and the controller on it (a
// satellite's controller index, a Moonlight pad's number).
data class LightSource(
    val connectionId: String,
    val controller: Int,
)

private const val CHANNEL_MASK = 0xFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val OPAQUE_ALPHA = 0xFF000000.toInt()

// A lightbar colour at full opacity, in the ARGB layout the lights API and the skin both take.
internal fun opaqueArgb(
    r: Int,
    g: Int,
    b: Int,
): Int = OPAQUE_ALPHA or ((r and CHANNEL_MASK) shl RED_SHIFT) or ((g and CHANNEL_MASK) shl GREEN_SHIFT) or (b and CHANNEL_MASK)
