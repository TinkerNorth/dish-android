// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

// Pure capability resolution for the type cards (Stage 3 Bluetooth-host pick-type
// and Stage 4 configure). Each capability is available only when Input AND
// Destination AND Type all carry it (the design's R AND L AND P rule). Kept free
// of Android types so the gating is unit-tested directly.
enum class SetupCapabilityKind { RUMBLE, MOTION, TOUCHPAD }

data class SetupCapabilityRow(
    val kind: SetupCapabilityKind,
    val inputOk: Boolean,
    val destinationOk: Boolean,
    val typeOk: Boolean,
) {
    val available: Boolean get() = inputOk && destinationOk && typeOk
}

object SetupCapability {
    // All three ride a Satellite link only: a Bluetooth host has no channel back to
    // the phone (the PC can't drive rumble, motion, or touchpad over the HID role).
    // Rumble works on any type and any input because the phone vibrates as a
    // universal fallback; motion and touchpad additionally need a PlayStation type,
    // and motion needs a gyro.
    fun rows(
        isPlayStation: Boolean,
        destinationIsSatellite: Boolean,
        hasGyro: Boolean,
    ): List<SetupCapabilityRow> =
        listOf(
            SetupCapabilityRow(
                SetupCapabilityKind.RUMBLE,
                inputOk = true,
                destinationOk = destinationIsSatellite,
                typeOk = true,
            ),
            SetupCapabilityRow(
                SetupCapabilityKind.MOTION,
                inputOk = hasGyro,
                destinationOk = destinationIsSatellite,
                typeOk = isPlayStation,
            ),
            SetupCapabilityRow(
                SetupCapabilityKind.TOUCHPAD,
                inputOk = true,
                destinationOk = destinationIsSatellite,
                typeOk = isPlayStation,
            ),
        )
}
