// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.setup

import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.ui.main.StringLookup

// The destination-facing chip rows, shared by the bind-controller host picker and
// the setup review: what a destination GETS from this phone and what it SENDS
// back, one chip per feature the path can carry.
internal fun destinationGetFlows(potential: CapabilitySet): List<ReviewFlow> =
    buildList {
        add(GAMEPAD_FLOW)
        if (Feature.MOTION in potential) add(MOTION_FLOW)
        if (Feature.TOUCHPAD in potential) add(TOUCHPAD_FLOW)
        if (Feature.MOUSE in potential) add(MOUSE_FLOW)
        if (Feature.BATTERY in potential) add(BATTERY_FLOW)
        if (Feature.MIC in potential) add(MIC_FLOW)
    }

internal fun destinationSendFlows(potential: CapabilitySet): List<ReviewFlow> =
    buildList {
        if (Feature.RUMBLE in potential) add(RUMBLE_FLOW)
        if (Feature.TRIGGER_RUMBLE in potential) {
            add(ReviewFlow(R.drawable.ic_trigger_rumble, R.string.setup_cap_trigger_rumble))
        }
        if (Feature.LIGHTBAR in potential) add(ReviewFlow(R.drawable.ic_lightbar, R.string.setup_cap_lightbar))
        if (Feature.TRIGGER_EFFECTS in potential) {
            add(ReviewFlow(R.drawable.ic_trigger_effects, R.string.setup_cap_trigger_effects))
        }
        if (Feature.PLAYER_LEDS in potential) add(ReviewFlow(R.drawable.ic_player_leds, R.string.setup_cap_player_leds))
        if (Feature.SPEAKER in potential) add(ReviewFlow(R.drawable.ic_speaker, R.string.setup_cap_speaker))
        if (Feature.HAPTIC_AUDIO in potential) add(ReviewFlow(R.drawable.ic_rumble, R.string.setup_cap_haptics))
    }

internal fun destinationNodes(
    input: ReviewInput,
    model: ReviewModel,
    strings: StringLookup,
): List<ReviewNode> =
    when (input.hostKind) {
        ConnectionKind.BLUETOOTH -> listOf(bluetoothDestinationNode(input, model, strings))
        ConnectionKind.SATELLITE -> satelliteDestinationNodes(input, model, strings)
        ConnectionKind.MOONLIGHT -> moonlightDestinationNodes(input, model, strings)
    }

// A Bluetooth host is the whole destination: it takes the gamepad and returns rumble only when
// the path carries it.
private fun bluetoothDestinationNode(
    input: ReviewInput,
    model: ReviewModel,
    strings: StringLookup,
): ReviewNode =
    ReviewNode(
        kind = R.string.binding_label_destination,
        icon = R.drawable.ic_bluetooth,
        name = input.hostLabel,
        sublabel = strings.format(R.string.setup_cfg_dest_bluetooth),
        sends = if (model.rumbleOn) listOf(RUMBLE_FLOW) else emptyList(),
        gets = listOf(GAMEPAD_FLOW),
    )

// Satellite injects the mouse itself; the virtual pad it creates carries the gamepad, motion,
// DS4 touchpad, battery and microphone, and the rumble it sends back.
private fun satelliteDestinationNodes(
    input: ReviewInput,
    model: ReviewModel,
    strings: StringLookup,
): List<ReviewNode> =
    listOf(
        ReviewNode(
            kind = R.string.binding_label_destination,
            icon = R.drawable.ic_satellite,
            name = input.hostLabel,
            sublabel = strings.format(R.string.setup_cfg_dest_satellite),
            sends = emptyList(),
            gets = if (model.mouseMode) listOf(MOUSE_FLOW) else emptyList(),
            compat = input.hostCompat,
        ),
        ReviewNode(
            kind = R.string.binding_label_destination,
            icon = R.drawable.ic_gamepad,
            name = input.padTypeLabel,
            sublabel = strings.format(R.string.setup_cfg_virtual_sublabel),
            sends = feedbackFlows(model),
            gets = emulatedPadGets(model, withMic = true),
        ),
    )

// The host runs an emulated pad of its own, so it reads like the satellite pair: the PC itself
// (which also takes the mouse, straight over the control stream), then the controller it plugs
// in for us and the feedback that comes back. That pad carries no microphone.
private fun moonlightDestinationNodes(
    input: ReviewInput,
    model: ReviewModel,
    strings: StringLookup,
): List<ReviewNode> =
    listOf(
        ReviewNode(
            kind = R.string.binding_label_destination,
            icon = R.drawable.ic_pc_monitor,
            name = input.hostLabel,
            sublabel = strings.format(R.string.ml_dest_sublabel, input.moonlightAddress),
            sends = emptyList(),
            gets = if (model.mouseMode) listOf(MOUSE_FLOW) else emptyList(),
        ),
        ReviewNode(
            kind = R.string.binding_label_destination,
            icon = R.drawable.ic_gamepad,
            name = input.padTypeLabel,
            sublabel = strings.format(R.string.setup_cfg_virtual_sublabel),
            sends = feedbackFlows(model),
            gets = emulatedPadGets(model, withMic = false),
        ),
    )

private fun emulatedPadGets(
    model: ReviewModel,
    withMic: Boolean,
): List<ReviewFlow> =
    buildList {
        add(GAMEPAD_FLOW)
        if (model.motionOn) add(MOTION_FLOW)
        if (model.padMode) add(TOUCHPAD_FLOW)
        if (model.batteryOn) add(BATTERY_FLOW)
        if (withMic && model.micOn) add(MIC_FLOW)
    }
