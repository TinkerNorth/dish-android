// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.source.store.isUp

// Only a satellite carries controller audio: the Moonlight control protocol has no audio channel
// and a Bluetooth HID gamepad has no audio endpoints to be. Unstable is up, as it is for the reports.
internal fun isStreamingSatellite(summary: ConnectionSummary?): Boolean {
    if (summary == null) return false
    val isSatellite = summary.kind == ConnectionKind.SATELLITE
    val linkIsUp = isUp(summary.live)
    return isSatellite && linkIsUp
}
