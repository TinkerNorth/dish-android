// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.overlay

// One entry of Display.supportedModes, reduced to what highestRefreshRateModeId compares.
internal data class DisplayModeInfo(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
)
