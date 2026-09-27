// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_OFF

// Whether the phone IMU should be running for the on-screen pad. Motion carries to a
// Satellite (always) and to a Moonlight host (the connection drops samples until
// MOTION_EVENT asks); a Bluetooth-bound slot must not spin the phone IMU. Link-liveness is
// read from the summary, not the capability model, so a reconnect re-arms the gate.
internal fun motionGateOpen(
    capability: SlotCapabilities,
    summary: ConnectionSummary?,
): Boolean {
    val inputAllowsMotion = capability.inputOk(Feature.MOTION) && capability.userWants(Feature.MOTION)
    val hostCarriesMotion = summary?.kind == ConnectionKind.SATELLITE || summary?.kind == ConnectionKind.MOONLIGHT
    val linkIsLive = summary?.live?.isLiveLink() == true
    return inputAllowsMotion && hostCarriesMotion && linkIsLive
}

// The trackpad zone appears only when the emulated type really carries one, and does only
// what the wire can deliver: a satellite gets the full touch stream once the descriptor
// declares a mode, a Moonlight host gets CONTROLLER_TOUCH events, and everything else hides it.
internal fun trackpadShownFor(
    kind: ConnectionKind?,
    typeHasTouchpad: Boolean,
    wireMode: String,
): Boolean {
    if (!typeHasTouchpad) return false
    return when (kind) {
        ConnectionKind.SATELLITE -> wireMode != TOUCHPAD_MODE_OFF
        ConnectionKind.MOONLIGHT -> true
        ConnectionKind.BLUETOOTH, null -> false
    }
}

// A momentary button's transition between two frames: only DOWN is an action.
internal enum class PressEdge { NONE, DOWN, UP }

internal fun pressEdge(
    wasDown: Boolean,
    isDown: Boolean,
): PressEdge =
    when {
        wasDown == isDown -> PressEdge.NONE
        isDown -> PressEdge.DOWN
        else -> PressEdge.UP
    }
