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
// device behind it (true through API 37). The one field both sides share is the product name,
// the USB device's iProduct string: Android 16 lists it as is, Android 10 to 15 wrap it into the
// ALSA card name (alsaCardNameFor), so an endpoint is a pad's under either spelling.
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

// What Android 10 to 15 call a USB sound card: the ALSA card name, "<driver> - <shortname>",
// where snd-usb-audio's driver string is "USB-Audio" and the shortname is the iProduct string cut
// to the 31 bytes of snd_card.shortname and trimmed. Android 16 names it by the product string.
private const val ALSA_USB_AUDIO_DRIVER = "USB-Audio"
private const val ALSA_SHORTNAME_MAX_CHARS = 31

internal fun alsaCardNameFor(productName: String): String =
    "$ALSA_USB_AUDIO_DRIVER - ${productName.take(ALSA_SHORTNAME_MAX_CHARS).trimEnd()}"

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
    val world =
        EndpointWorld(
            sinksByName = named.filter { it.sink }.groupBy { it.productName!!.trim() },
            sourcesByName = named.filter { it.source }.groupBy { it.productName!!.trim() },
            seen = named.map { it.productName!!.trim() }.distinct(),
            padsByName =
                pads
                    .filter { it.hasAudioFunction && !it.productName.isNullOrBlank() }
                    .groupBy { it.productName!!.trim() },
        )
    return pads.associate { pad -> vidPidKey(pad.vendorId, pad.productId) to factsFor(pad, world) }
}

// The two lists the platform has, indexed by trimmed name once per resolve.
private data class EndpointWorld(
    val sinksByName: Map<String, List<UsbAudioEndpoint>>,
    val sourcesByName: Map<String, List<UsbAudioEndpoint>>,
    val seen: List<String>,
    val padsByName: Map<String, List<UsbAudioPad>>,
)

private fun factsFor(
    pad: UsbAudioPad,
    world: EndpointWorld,
): PadAudioFacts {
    val name = pad.productName?.trim()
    if (!pad.hasAudioFunction || name.isNullOrBlank()) {
        return PadAudioFacts(PadAudioReason.NO_AUDIO_FUNCTION, PadAudioRoute.NONE, world.seen)
    }
    val padNameIsShared = world.padsByName.getValue(name).size > 1
    if (padNameIsShared) {
        return PadAudioFacts(PadAudioReason.PAD_NAME_SHARED, PadAudioRoute.NONE, world.seen)
    }
    val spellings = platformNamesFor(name)
    val sinks = spellings.flatMap { world.sinksByName[it].orEmpty() }
    val sources = spellings.flatMap { world.sourcesByName[it].orEmpty() }
    return matchedFacts(pad, sinks, sources, world.seen)
}

// The spellings under which Android lists one pad's sound card: the product string itself
// (Android 16) or the ALSA card name built from it (Android 10 to 15).
private fun platformNamesFor(name: String): List<String> = listOf(name, alsaCardNameFor(name))

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
