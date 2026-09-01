// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "No endpoint". Android numbers real [android.media.AudioDeviceInfo] entries from 1, so zero is
 * free, and it is what [android.media.AudioTrack.setPreferredDevice] means by a null preference:
 * let the platform route.
 */
const val NO_AUDIO_DEVICE = 0

/**
 * Which of a physical pad's own audio endpoints Android currently routes to us. A
 * DualSense or DualShock 4 v2 carries a USB Audio Class function alongside its HID
 * interface; the app claims ONLY the HID interface, so that function stays with the OS
 * and its endpoints appear as ordinary [android.media.AudioDeviceInfo] entries.
 *
 * The two flags are the capability answer: whether this pad has an endpoint to be captured from
 * or played to at all. The two ids are the routing answer: WHICH endpoint, for
 * `setPreferredDevice` on the recorder and the track. The resolver always names an endpoint
 * alongside the flag it sets, because a claim it cannot point at is a claim it should not make;
 * a route built without one falls back to the platform's own routing.
 */
data class PadAudioRoute(
    val microphone: Boolean,
    val speaker: Boolean,
    val captureDeviceId: Int = NO_AUDIO_DEVICE,
    val playbackDeviceId: Int = NO_AUDIO_DEVICE,
) {
    companion object {
        val NONE = PadAudioRoute(microphone = false, speaker = false)
    }
}

/**
 * The pad-to-audio-endpoint table behind the physical path's `mic` / `speaker` caps.
 *
 * Advertising either cap for a physical pad is a claim that a specific device can be
 * captured from or played to, so the claim is made ONLY where a matching endpoint really
 * exists: the OS may not enumerate a pad's audio function at all (no driver, a hub that
 * swallowed it, a pad without one), and a cap the client cannot honor would have the host
 * stream audio into nothing.
 *
 * The table is a state source rather than a plain lookup because it moves at runtime: an
 * endpoint appears and vanishes with the cable. Publishing a new map re-runs the
 * capability composition, which re-declares the affected slot's descriptor.
 *
 * [PadAudioRouteResolver] owns the publishing; [PadAudioMatcher] owns the rule that decides what
 * belongs to which pad. This is only the table they write and everything else reads.
 */
@Singleton
class PadAudioRoutes
    @Inject
    constructor() : AbstractStateSource<Map<Int, PadAudioRoute>>(emptyMap()) {
        /** Absent (a pad with no endpoints, or one that could not be matched) is [PadAudioRoute.NONE]. */
        fun routeFor(
            vendorId: Int,
            productId: Int,
        ): PadAudioRoute = state.value[key(vendorId, productId)] ?: PadAudioRoute.NONE

        /** Republish the whole table; the resolver owns it wholesale, one map per device change. */
        fun publishRoutes(routes: Map<Int, PadAudioRoute>) {
            setState(routes)
        }

        companion object {
            /** Same vendor:product packing the USB path keys its per-model preferences on. */
            fun key(
                vendorId: Int,
                productId: Int,
            ): Int = (vendorId shl 16) or productId
        }
    }
