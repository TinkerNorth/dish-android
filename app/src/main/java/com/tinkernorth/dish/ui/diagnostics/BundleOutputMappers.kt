// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.composer.BundleLane
import com.tinkernorth.dish.composer.BundleVoice
import com.tinkernorth.dish.composer.VoiceDevice
import com.tinkernorth.dish.composer.VoiceState
import com.tinkernorth.dish.source.audio.NO_AUDIO_DEVICE
import com.tinkernorth.dish.source.audio.PlayoutLane
import com.tinkernorth.dish.source.audio.SpeakerTarget
import com.tinkernorth.dish.source.store.FeedbackKind
import com.tinkernorth.dish.source.store.LaneActivity
import com.tinkernorth.dish.source.store.PadAudioRefusal
import com.tinkernorth.dish.source.system.WifiBand

// What came out of each controller, and the report's own choices about it.

internal fun bundleLanesOf(lanes: Map<FeedbackKind, LaneActivity>): Map<FeedbackKind, BundleLane> =
    lanes.mapValues { (_, lane) -> BundleLane(lane.count, lane.value, lane.atMs) }

internal fun feedbackTargetWire(target: FeedbackTargetKind): String =
    when (target) {
        FeedbackTargetKind.PHONE -> "phone"
        FeedbackTargetKind.PAD_DIRECT -> "directUsb"
        FeedbackTargetKind.PAD_FRAMEWORK -> "framework"
        FeedbackTargetKind.NONE -> "none"
    }

// A lane the platform refused to open is refused; one the playout plan holds a voice for is open
// on the pad's own endpoint or the phone's default output; any other is closed.
internal fun bundleVoiceOf(
    slotId: String,
    lane: PlayoutLane,
    voices: Map<Long, SpeakerTarget>,
    refusals: Map<Pair<String, PlayoutLane>, PadAudioRefusal>,
): BundleVoice {
    val refused = refusals.containsKey(slotId to lane)
    if (refused) return BundleVoice(VoiceState.REFUSED, device = null, frames = null)
    val voice = voices.values.firstOrNull { it.slotId == slotId && it.lane == lane }
    voice ?: return BundleVoice(VoiceState.CLOSED, device = null, frames = null)
    val onThePad = voice.playbackDeviceId != NO_AUDIO_DEVICE
    return BundleVoice(VoiceState.OPEN, if (onThePad) VoiceDevice.PAD else VoiceDevice.DEFAULT, frames = null)
}

internal fun wifiBandWire(band: WifiBand): String? =
    when (band) {
        WifiBand.GHZ_2_4 -> "2.4"
        WifiBand.GHZ_5 -> "5"
        WifiBand.GHZ_6 -> "6"
        WifiBand.UNKNOWN -> null
    }

// The report names a controller by its record, else by the name it gives itself.
internal fun reportControllerOf(diag: ControllerDiag): String = diag.facts?.record?.id ?: diag.name

// The controller the report starts on: the first physical pad that is attached, never the
// on-screen one; null when there is none.
internal fun defaultReportSlotOf(controllers: List<ControllerDiag>): String? =
    controllers.firstOrNull { !it.isVirtual && it.state == ControllerDiagState.CONNECTED }?.slotId
