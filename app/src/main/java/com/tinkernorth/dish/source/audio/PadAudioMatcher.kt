// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import com.tinkernorth.dish.core.input.vidPidKey

/**
 * One attached USB device, flattened out of [android.hardware.usb.UsbDevice] so the matching rule
 * below stays pure.
 *
 * [hasAudioFunction] is whether any of its interfaces is USB Audio Class. It is the fact that makes
 * a pad a candidate at all: the app claims only the HID interface precisely so this one stays with
 * the OS, and a pad without it can never have an endpoint of its own.
 */
data class UsbAudioPad(
    val vendorId: Int,
    val productId: Int,
    val productName: String?,
    val hasAudioFunction: Boolean,
    // Whether that function's render endpoint carries the HD-haptics lanes (channels 3/4
    // driving the voice-coil actuators). The DualSense alone; the DualShock 4 v2 function
    // is headset-only. A candidacy fact like [hasAudioFunction]: the endpoint still has
    // to present the lanes before a route names them.
    val hasHapticLanes: Boolean = false,
)

/**
 * One USB-typed [android.media.AudioDeviceInfo], flattened. The caller filters by type before
 * building these: only TYPE_USB_DEVICE and TYPE_USB_HEADSET are a plugged pad's own function
 * (TYPE_USB_ACCESSORY is this phone acting as somebody else's accessory, which is the other
 * direction entirely).
 */
data class UsbAudioEndpoint(
    val deviceId: Int,
    val productName: String?,
    val sink: Boolean,
    val source: Boolean,
    // The channel counts the platform will open this endpoint at ([android.media.AudioDeviceInfo.getChannelCounts]);
    // empty means it would not say. A sink that lists 4 is one whose actuator lanes a
    // quad track can reach.
    val channelCounts: List<Int> = emptyList(),
)

// Matches a plugged pad to its own audio endpoints, conservatively.
// There is no public API that puts a vendor:product on an [android.media.AudioDeviceInfo]: the
// class exposes an id, a type, a product name and an address, and nothing that names the USB
// device behind it (true through API 37). The one field both sides genuinely share is the product
// name, which on either side is the USB device's own iProduct string descriptor, so that is what
// this matches on.
// Because it is only a name, every ambiguity resolves to "no route", never to a guess:
// - The pad must actually carry a USB Audio Class interface. A name alone would let an unrelated
// USB audio dongle lend its endpoints to a pad that has none.
// - The name must identify exactly one attached device. Two DualSenses (or a DualSense next to a
// DualShock 4, which shares the string "Wireless Controller") are indistinguishable here, and
// routing a slot to the wrong pad's speaker is worse than not routing it.
// - The name must identify at most one endpoint per direction, for the same reason.
// A pad that resolves to nothing simply advertises neither cap, which is the honest answer for a
// pad whose audio function this device cannot confidently name.

/** The DualSense's own render endpoint: speaker pair then haptic pair. */
const val HAPTIC_ENDPOINT_CHANNELS = 4

fun resolvePadAudioRoutes(
    pads: List<UsbAudioPad>,
    endpoints: List<UsbAudioEndpoint>,
): Map<Int, PadAudioRoute> = routesOf(explainPadAudio(pads, endpoints))

/** Why a pad has the route it has. [ROUTED] is the only reason that carries a route. */
enum class PadAudioReason { NO_AUDIO_FUNCTION, PAD_NAME_SHARED, NO_ENDPOINT, ENDPOINT_NAME_SHARED, ROUTED }

data class PadAudioFacts(
    val reason: PadAudioReason,
    val route: PadAudioRoute,
    // Every USB audio product name the platform listed, so a pad that matched nothing shows
    // what it was compared against.
    val endpointNames: List<String>,
)

fun routesOf(facts: Map<Int, PadAudioFacts>): Map<Int, PadAudioRoute> =
    facts.filterValues { it.reason == PadAudioReason.ROUTED }.mapValues { it.value.route }

fun explainPadAudio(
    pads: List<UsbAudioPad>,
    endpoints: List<UsbAudioEndpoint>,
): Map<Int, PadAudioFacts> {
    val named = endpoints.filter { !it.productName.isNullOrBlank() }.distinctBy { it.deviceId }
    val sinksByName = named.filter { it.sink }.groupBy { it.productName!!.trim() }
    val sourcesByName = named.filter { it.source }.groupBy { it.productName!!.trim() }
    val seen = named.map { it.productName!!.trim() }.distinct()
    val padsByName =
        pads
            .filter { it.hasAudioFunction && !it.productName.isNullOrBlank() }
            .groupBy { it.productName!!.trim() }
    val out = HashMap<Int, PadAudioFacts>()
    for (pad in pads) {
        val name = pad.productName?.trim()
        val reason =
            when {
                !pad.hasAudioFunction || name.isNullOrBlank() -> PadAudioReason.NO_AUDIO_FUNCTION
                padsByName.getValue(name).size > 1 -> PadAudioReason.PAD_NAME_SHARED
                else -> null
            }
        out[vidPidKey(pad.vendorId, pad.productId)] =
            if (reason != null) {
                PadAudioFacts(reason, PadAudioRoute.NONE, seen)
            } else {
                matchedFacts(pad, sinksByName[name].orEmpty(), sourcesByName[name].orEmpty(), seen)
            }
    }
    return out
}

private fun matchedFacts(
    pad: UsbAudioPad,
    sinks: List<UsbAudioEndpoint>,
    sources: List<UsbAudioEndpoint>,
    seen: List<String>,
): PadAudioFacts {
    val sink = sinks.singleOrNull()
    val source = sources.singleOrNull()
    if (sink == null && source == null) {
        val shared = sinks.size > 1 || sources.size > 1
        val reason = if (shared) PadAudioReason.ENDPOINT_NAME_SHARED else PadAudioReason.NO_ENDPOINT
        return PadAudioFacts(reason, PadAudioRoute.NONE, seen)
    }
    // The widest count the platform offers, so a pad that lists both stereo and quad opens at
    // quad and keeps its lane pairs apart.
    val playbackChannels = sink?.channelCounts?.maxOrNull() ?: 0
    val route =
        PadAudioRoute(
            microphone = source != null,
            speaker = sink != null,
            captureDeviceId = source?.deviceId ?: NO_AUDIO_DEVICE,
            playbackDeviceId = sink?.deviceId ?: NO_AUDIO_DEVICE,
            haptics = sink != null && pad.hasHapticLanes && playbackChannels >= HAPTIC_ENDPOINT_CHANNELS,
            playbackChannels = playbackChannels,
        )
    return PadAudioFacts(PadAudioReason.ROUTED, route, seen)
}
