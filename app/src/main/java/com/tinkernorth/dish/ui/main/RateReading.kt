// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

// One measured input rate as the dashboard pills and the overlay readouts show it: a live
// window (Direct streams continuously), a peak window (event-driven paths deliver only while
// the user presses, shown with "~"), nothing measured yet, or a rate the slot never produces.
internal sealed interface RateReading {
    data object Off : RateReading

    data object Pending : RateReading

    data class LiveHz(
        val hz: Int,
    ) : RateReading

    data class PeakHz(
        val hz: Int,
    ) : RateReading
}
