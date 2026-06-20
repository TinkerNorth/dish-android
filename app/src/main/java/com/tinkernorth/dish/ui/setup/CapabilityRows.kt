// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities

// View-row shapes for the setup type cards (Stage 3 Bluetooth-host pick-type and
// Stage 4 configure). The capability math lives in the composer/resolver now;
// this mapper only projects a resolved SlotCapabilities onto the three rows the
// table renders, breaking each into its Input/Destination/Type columns.
enum class SetupCapabilityKind { RUMBLE, MOTION, TOUCHPAD }

data class SetupCapabilityRow(
    val kind: SetupCapabilityKind,
    val inputOk: Boolean,
    val destinationOk: Boolean,
    val typeOk: Boolean,
) {
    val available: Boolean get() = inputOk && destinationOk && typeOk
}

// Rows are returned in rumble, motion, touchpad order to match the card layout.
fun capabilityRows(caps: SlotCapabilities): List<SetupCapabilityRow> =
    listOf(
        rowFor(SetupCapabilityKind.RUMBLE, Feature.RUMBLE, caps),
        rowFor(SetupCapabilityKind.MOTION, Feature.MOTION, caps),
        rowFor(SetupCapabilityKind.TOUCHPAD, Feature.TOUCHPAD, caps),
    )

private fun rowFor(
    kind: SetupCapabilityKind,
    feature: Feature,
    caps: SlotCapabilities,
): SetupCapabilityRow =
    SetupCapabilityRow(
        kind = kind,
        inputOk = caps.inputOk(feature),
        destinationOk = caps.destinationOk(feature),
        typeOk = caps.typeOk(feature),
    )
