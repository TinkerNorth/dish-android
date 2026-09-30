// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.net.DishProtocolCompat
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_DS4
import com.tinkernorth.dish.source.inputrate.SlotInputRates

// Everything one dashboard card is drawn from. Plain data, so every pill and action the card
// shows is a function of it that a test can call without a view.
data class ControllerRow(
    val slot: ControllerSlot,
    val connections: List<ConnectionSummary>,
    val motionCap: SlotCapabilities = SlotCapabilities.NONE,
    val pointer: PointerSlotUi? = null,
    val pathCard: PathCard? = null,
    val inputRates: SlotInputRates? = null,
    val screenPeakHz: Int = 0,
    val hostCompat: DishProtocolCompat = DishProtocolCompat.UNKNOWN,
)

// What the card's function row says about the slot's pointer surfaces, kept pure so the
// facts stay testable: the pad surface reports its declared routing (or the Direct nudge
// that unlocks it), and the mouse rides as an on-demand chip wherever its surface can open.
internal enum class PointerPillFact { PAD_NEEDS_DIRECT, PAD_ON, PAD_OFF, MOUSE_READY }

// Direct on an unrecognized model reads a guessed layout, so what the pad supports is not known.
internal fun inputFunctionsUnknown(card: PathCard?): Boolean =
    card != null && card.currentMode == InputPathMode.Direct && card.risk == PathRisk.GuessedLayout

internal fun pointerFuncFacts(row: ControllerRow): List<PointerPillFact> =
    buildList {
        when {
            row.pathCard?.suggestDirectForTouch == true -> add(PointerPillFact.PAD_NEEDS_DIRECT)
            row.pointer?.mode == TOUCHPAD_MODE_DS4 -> add(PointerPillFact.PAD_ON)
            row.motionCap.typeOk(Feature.TOUCHPAD) -> add(PointerPillFact.PAD_OFF)
        }
        if (row.pointer?.mouseOpenable == true) add(PointerPillFact.MOUSE_READY)
    }

// The motion source can stream while motion is user-facing off (no host sink for the emulated
// type, broken backend): the card's motion rate hides in exactly the states the motion indicator
// renders as muted, so the two never disagree. Motion only carries to a Satellite, so the bound
// summary's kind and liveness gate it (the capability model omits link state).
internal fun motionRateUserFacingOn(
    cap: SlotCapabilities,
    boundStatus: ConnectionSummary?,
): Boolean {
    val inputHasMotion = cap.inputOk(Feature.MOTION)
    val userWantsIt = cap.userWants(Feature.MOTION)
    val boundToLiveSatellite = boundStatus?.kind == ConnectionKind.SATELLITE && boundStatus.live == LinkState.Connected
    val typeSinksIt = cap.typeOk(Feature.MOTION) && Feature.MOTION !in cap.runtimeDown
    return inputHasMotion && userWantsIt && boundToLiveSatellite && typeSinksIt
}

// Screen input can drive a slot only while an overlay surface exists for it: the on-screen
// gamepad for the virtual slot, or one of the slot's phone pointer surfaces (a pad streaming
// its own trackpad has neither). Outside those states the card's screen rate reads Off.
internal fun screenRateUserFacingOn(
    inputType: SlotInputType,
    boundKind: ConnectionKind?,
    pointer: PointerSlotUi?,
): Boolean =
    inputType == SlotInputType.VIRTUAL ||
        (boundKind == ConnectionKind.SATELLITE && pointer?.anyOpenable == true)
