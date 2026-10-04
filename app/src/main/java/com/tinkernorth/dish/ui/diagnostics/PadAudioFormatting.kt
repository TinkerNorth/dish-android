// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.audio.PadAudioFacts
import com.tinkernorth.dish.source.audio.PadAudioReason
import com.tinkernorth.dish.source.audio.PadAudioRoute

// The "Controller audio" line of a pad's section: what a USB pad's audio function routes to, or
// which of the matcher's conditions it failed.
internal fun Context.padAudioValue(facts: PadAudioFacts): String =
    when (facts.reason) {
        PadAudioReason.NO_AUDIO_FUNCTION -> getString(R.string.diagnostics_pad_audio_no_function)
        PadAudioReason.PAD_NAME_SHARED -> getString(R.string.diagnostics_pad_audio_name_shared)
        PadAudioReason.NO_ENDPOINT ->
            getString(R.string.diagnostics_pad_audio_no_endpoint, seenEndpoints(facts.endpointNames))
        PadAudioReason.ENDPOINT_NAME_SHARED -> getString(R.string.diagnostics_pad_audio_endpoint_shared)
        PadAudioReason.ROUTED -> routedAudioValue(facts.route)
    }

private fun Context.seenEndpoints(names: List<String>): String =
    if (names.isEmpty()) getString(R.string.diagnostics_none) else names.joinToString(", ")

private fun Context.routedAudioValue(route: PadAudioRoute): String =
    buildList {
        if (route.microphone) add(getString(R.string.setup_cap_mic))
        if (route.speaker) add(getString(R.string.setup_cap_speaker))
        if (route.haptics) add(getString(R.string.setup_cap_haptics))
        if (route.playbackChannels > 0) {
            val channels = route.playbackChannels
            add(resources.getQuantityString(R.plurals.diagnostics_pad_audio_channels, channels, channels))
        }
    }.joinToString(" · ")
