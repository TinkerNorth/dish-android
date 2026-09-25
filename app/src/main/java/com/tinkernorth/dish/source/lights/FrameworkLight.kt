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
