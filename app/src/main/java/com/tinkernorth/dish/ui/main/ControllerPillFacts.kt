// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.net.moonlight.AUTO
import com.tinkernorth.dish.core.net.moonlight.fromStored
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.ui.common.bundledControllerTypeLabelRes
import com.tinkernorth.dish.ui.common.moonlightTypeLabelRes

// Pure pill reducers for the dashboard card's connection, emulate and function rows, siblings
// of pointerFuncFacts: each returns facts the adapter turns into pills, so the rules that pick
// them are testable without a view.

// The connection row's pills, each with the label, glyph and tone it wears.
internal enum class ConnectionPillFact(
    @param:StringRes val labelRes: Int,
    @param:DrawableRes val iconRes: Int,
    val tone: PillTone,
) {
    ONSCREEN(R.string.binding_link_onscreen, R.drawable.ic_gamepad_virtual, PillTone.FACT),
    BLUETOOTH(R.string.binding_link_bluetooth, R.drawable.ic_bluetooth, PillTone.FACT),
    USB(R.string.binding_link_usb, R.drawable.ic_usb, PillTone.FACT),
    DIRECT(R.string.binding_mode_direct, R.drawable.ic_bolt, PillTone.ON),

    // Direct on an unrecognized model runs a guessed layout, and the badge says so.
    DIRECT_GUESSED(R.string.binding_mode_direct, R.drawable.ic_bolt, PillTone.WARN),
    STANDARD(R.string.binding_mode_standard, R.drawable.ic_cable, PillTone.CAP),
    WIRED_AVAILABLE(R.string.binding_usb_available, R.drawable.ic_usb, PillTone.WARN),
}

// The link the input rides, then the USB path it is on, then the cable a Bluetooth pad could
// switch to.
internal fun connectionPillFacts(row: ControllerRow): List<ConnectionPillFact> {
    val card = row.pathCard
    val virtual = row.slot.inputType == SlotInputType.VIRTUAL
    val isUsb = !virtual && card?.transport == Transport.Usb
    val isBt = !virtual && card?.transport == Transport.Bluetooth
    val link =
        when {
            virtual -> ConnectionPillFact.ONSCREEN
            isBt -> ConnectionPillFact.BLUETOOTH
            else -> ConnectionPillFact.USB
        }
    val facts = mutableListOf(link)
    if (isUsb) facts.add(usbModeFact(card))
    if (isBt && card.wiredSwitchAvailable) facts.add(ConnectionPillFact.WIRED_AVAILABLE)
    return facts
}

private fun usbModeFact(card: PathCard): ConnectionPillFact =
    when {
        card.currentMode != InputPathMode.Direct -> ConnectionPillFact.STANDARD
        card.risk == PathRisk.GuessedLayout -> ConnectionPillFact.DIRECT_GUESSED
        else -> ConnectionPillFact.DIRECT
    }

// The emulate row's one pill: a bundled type name, or the profile name a Bluetooth host fixed.
internal sealed interface EmulatePill {
    data class Bundled(
        @StringRes val labelRes: Int,
    ) : EmulatePill

    data class Profile(
        val name: String,
    ) : EmulatePill
}

// A Moonlight host has its own type table; its ids overlap the catalog's, so the label comes
// from the Moonlight mapper and never the bundled one. Null hides the row.
internal fun emulatePillFor(
    kind: ConnectionKind,
    storedType: Int?,
    btProfile: String?,
): EmulatePill? =
    when (kind) {
        ConnectionKind.SATELLITE -> EmulatePill.Bundled(bundledControllerTypeLabelRes(storedType ?: CONTROLLER_TYPE_XBOX))
        ConnectionKind.BLUETOOTH -> btProfile?.let { EmulatePill.Profile(it) }
        ConnectionKind.MOONLIGHT -> EmulatePill.Bundled(moonlightTypeLabelRes(fromStored(storedType ?: AUTO)))
    }

// The function row's pills in display order. The unknown trio stands in for the classic
// three while a Direct pad's layout is guessed; the rest report the configured (not
// live-gated) routing.
internal sealed interface FunctionPillFact {
    data object RumbleUnknown : FunctionPillFact

    data object GyroUnknown : FunctionPillFact

    data object TouchpadUnknown : FunctionPillFact

    data object Rumble : FunctionPillFact

    data class Motion(
        val on: Boolean,
    ) : FunctionPillFact

    data class Pointer(
        val fact: PointerPillFact,
    ) : FunctionPillFact

    data class Feedback(
        val fact: FeedbackPillFact,
    ) : FunctionPillFact

    data class Audio(
        val fact: AudioPillFact,
    ) : FunctionPillFact
}

internal fun functionPillFacts(
    row: ControllerRow,
    bound: ConnectionSummary,
): List<FunctionPillFact> {
    if (inputFunctionsUnknown(row.pathCard)) return unknownFunctionPillFacts(row)
    return buildList {
        if (rumblePresent(row.pathCard)) add(FunctionPillFact.Rumble)
        motionPillFor(row, bound)?.let { add(it) }
        addAll(pointerPillsFor(row, bound))
        addAll(feedbackFuncFacts(row.motionCap).map { FunctionPillFact.Feedback(it) })
        addAll(audioFuncFacts(row.motionCap).map { FunctionPillFact.Audio(it) })
    }
}

private fun unknownFunctionPillFacts(row: ControllerRow): List<FunctionPillFact> =
    buildList {
        add(FunctionPillFact.RumbleUnknown)
        add(FunctionPillFact.GyroUnknown)
        add(FunctionPillFact.TouchpadUnknown)
        if (row.pointer?.mouseOpenable == true) add(FunctionPillFact.Pointer(PointerPillFact.MOUSE_READY))
    }

// The motor on the path the pad is actually on.
private fun rumblePresent(card: PathCard?): Boolean {
    if (card == null) return false
    return if (card.currentMode == InputPathMode.Direct) card.direct.rumble else card.standard.rumble
}

// Motion streams to a Satellite and, since protocol 2 shipped the Moonlight telemetry, to a
// Moonlight host too (its type layer gates which emulated pads carry it); Bluetooth stays
// gamepad-only. The pill's tone is the user's toggle.
private fun motionPillFor(
    row: ControllerRow,
    bound: ConnectionSummary,
): FunctionPillFact.Motion? {
    val inputHasMotion = row.motionCap.inputOk(Feature.MOTION)
    val hostCarriesMotion = bound.kind != ConnectionKind.BLUETOOTH
    val typeHasMotion = row.motionCap.typeOk(Feature.MOTION)
    val available = inputHasMotion && hostCarriesMotion && typeHasMotion
    if (!available) return null
    return FunctionPillFact.Motion(on = row.motionCap.userWants(Feature.MOTION))
}

private fun pointerPillsFor(
    row: ControllerRow,
    bound: ConnectionSummary,
): List<FunctionPillFact> =
    when (bound.kind) {
        ConnectionKind.SATELLITE -> pointerFuncFacts(row).map { FunctionPillFact.Pointer(it) }
        ConnectionKind.MOONLIGHT -> moonlightPointerFacts(row).map { FunctionPillFact.Pointer(it) }
        ConnectionKind.BLUETOOTH -> emptyList()
    }
