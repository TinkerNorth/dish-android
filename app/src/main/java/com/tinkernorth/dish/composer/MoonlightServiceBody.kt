// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

// What the Moonlight service notification says about the first host: the line has room for one.
internal sealed interface MoonlightServiceBody {
    data object Idle : MoonlightServiceBody

    data class Starting(
        val hostLabel: String,
    ) : MoonlightServiceBody

    data class Pads(
        val hostLabel: String,
        val padCount: Int,
    ) : MoonlightServiceBody
}

internal fun moonlightServiceBodyFor(
    hostLabel: String?,
    padCount: Int,
): MoonlightServiceBody {
    if (hostLabel == null) return MoonlightServiceBody.Idle
    if (padCount > 0) return MoonlightServiceBody.Pads(hostLabel, padCount)
    return MoonlightServiceBody.Starting(hostLabel)
}
