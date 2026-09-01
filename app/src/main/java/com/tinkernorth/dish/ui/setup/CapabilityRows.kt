// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities

// View-row shapes for the setup type cards (Stage 3 Bluetooth-host pick-type and
// Stage 4 configure) and the binding candidate cards. The capability math lives
// in the composer/resolver now; this mapper only projects a resolved
// SlotCapabilities onto rows the table renders, breaking each into its
// Input/Destination/Type columns.
enum class SetupCapabilityKind {
    RUMBLE,
    MOTION,
    TOUCHPAD,
    BATTERY,
    LIGHTBAR,
    TRIGGER_RUMBLE,
    TRIGGER_EFFECTS,
    PLAYER_LEDS,
}

data class SetupCapabilityRow(
    val kind: SetupCapabilityKind,
    val inputOk: Boolean,
    val destinationOk: Boolean,
    val typeOk: Boolean,
    val inputUnknown: Boolean = false,
) {
    val available: Boolean get() = inputOk && destinationOk && typeOk

    val unknown: Boolean get() = inputUnknown && destinationOk && typeOk
}

// The three classic rows always render (their off-state is informative); the
// feedback/battery rows appended in protocol 2 render only when SOME layer can
// carry them, so a plain Xbox-over-Bluetooth card stays three rows instead of
// eight rows of crosses.
fun capabilityRows(
    caps: SlotCapabilities,
    inputUnknown: Boolean = false,
): List<SetupCapabilityRow> {
    val rows =
        mutableListOf(
            rowFor(SetupCapabilityKind.RUMBLE, Feature.RUMBLE, caps, inputUnknown),
            rowFor(SetupCapabilityKind.MOTION, Feature.MOTION, caps, inputUnknown),
            rowFor(SetupCapabilityKind.TOUCHPAD, Feature.TOUCHPAD, caps, inputUnknown),
        )
    for ((kind, feature) in EXTENDED_ROWS) {
        val row = rowFor(kind, feature, caps, inputUnknown)
        if (row.inputOk || row.destinationOk || row.typeOk) rows += row
    }
    return rows
}

private val EXTENDED_ROWS =
    listOf(
        SetupCapabilityKind.BATTERY to Feature.BATTERY,
        SetupCapabilityKind.LIGHTBAR to Feature.LIGHTBAR,
        SetupCapabilityKind.TRIGGER_RUMBLE to Feature.TRIGGER_RUMBLE,
        SetupCapabilityKind.TRIGGER_EFFECTS to Feature.TRIGGER_EFFECTS,
        SetupCapabilityKind.PLAYER_LEDS to Feature.PLAYER_LEDS,
    )

private fun rowFor(
    kind: SetupCapabilityKind,
    feature: Feature,
    caps: SlotCapabilities,
    inputUnknown: Boolean,
): SetupCapabilityRow =
    SetupCapabilityRow(
        kind = kind,
        inputOk = caps.inputOk(feature),
        destinationOk = caps.destinationOk(feature),
        typeOk = caps.typeOk(feature),
        inputUnknown = inputUnknown,
    )
