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
    // Motion and the touchpad only ride a Satellite link (Bluetooth-host has no
    // channel for them), and both need a PlayStation type on the host; rumble
    // rides any connected host regardless of type.
    fun rows(
        isPlayStation: Boolean,
        destinationIsSatellite: Boolean,
        hasDestination: Boolean,
        hasGyro: Boolean,
        hasRumble: Boolean,
    ): List<SetupCapabilityRow> =
        listOf(
            SetupCapabilityRow(
                SetupCapabilityKind.RUMBLE,
                inputOk = hasRumble,
                destinationOk = hasDestination,
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
