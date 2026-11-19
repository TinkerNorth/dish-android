// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.source.audio.PlayoutLane
import javax.inject.Inject
import javax.inject.Singleton

/** One output the platform refused to open: the slot and lane it was for, at the endpoint and width it was tried at. */
data class PadAudioRefusal(
    val slotId: String,
    val lane: PlayoutLane,
    val playbackDeviceId: Int,
    val deviceChannels: Int,
)

/**
 * The outputs the platform refused, by slot and lane. SpeakerEngine writes it as it opens and
 * CapabilityComposer reads it into a slot's runtime-down layer, so a refused lane leaves `live`,
 * and with it the descriptor, and the host falls back to rumble instead of streaming a waveform
 * nobody plays. A store so neither depends on the other.
 */
@Singleton
class PadAudioRefusalStore
    @Inject
    constructor() :
    AbstractStateSource<Map<Pair<String, PlayoutLane>, PadAudioRefusal>>(emptyMap()) {
        fun record(refusal: PadAudioRefusal) {
            setState { it + ((refusal.slotId to refusal.lane) to refusal) }
        }

        fun clear(
            slotId: String,
            lane: PlayoutLane,
        ) {
            setState { withoutEntry(it, slotId to lane) }
        }

        /** A fresh start is a fresh attempt at every output. */
        fun clearAll() {
            setState(emptyMap())
        }
    }

// The features a slot's refusals take down at its current endpoint. A refusal at another device
// id or width is spent: a replugged pad enumerates afresh, and a route that moved to another width
// is a new attempt.
internal fun refusedAudioFeatures(
    refusals: Map<Pair<String, PlayoutLane>, PadAudioRefusal>,
    slotId: String,
    playbackDeviceId: Int,
    deviceChannels: Int,
): CapabilitySet {
    val down = mutableSetOf<Feature>()
    for (lane in PlayoutLane.entries) {
        val refusal = refusals[slotId to lane] ?: continue
        val sameDevice = refusal.playbackDeviceId == playbackDeviceId
        val sameWidth = refusal.deviceChannels == deviceChannels
        if (sameDevice && sameWidth) down += laneFeature(lane)
    }
    return CapabilitySet(down)
}

private fun laneFeature(lane: PlayoutLane): Feature =
    when (lane) {
        PlayoutLane.SPEAKER -> Feature.SPEAKER
        PlayoutLane.HAPTICS -> Feature.HAPTIC_AUDIO
    }
