// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.inputrate

// The measured input classes of one slot: controller (the physical pad's buttons/axes) and gyro
// (controller IMU or the phone's for the virtual slot). Screen is not per-slot: the phone has
// one touch surface, so its rate lives once on InputRates.
data class SlotInputRates(
    val controllerHz: Int = 0,
    val controllerPeakHz: Int = 0,
    val gyroHz: Int = 0,
) {
    val hasAny: Boolean get() = controllerHz > 0 || controllerPeakHz > 0 || gyroHz > 0
}

data class InputRates(
    val screenPeakHz: Int = 0,
    val slots: Map<String, SlotInputRates> = emptyMap(),
)
