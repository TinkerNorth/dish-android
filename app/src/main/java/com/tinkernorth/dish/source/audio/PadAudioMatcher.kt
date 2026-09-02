// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

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
)

/**
 * Matches a plugged pad to its own audio endpoints, conservatively.
 *
 * There is no public API that puts a vendor:product on an [android.media.AudioDeviceInfo]: the
 * class exposes an id, a type, a product name and an address, and nothing that names the USB
 * device behind it (true through API 37). The one field both sides genuinely share is the product
 * name, which on either side is the USB device's own iProduct string descriptor, so that is what
 * this matches on.
 *
 * Because it is only a name, every ambiguity resolves to "no route", never to a guess:
 *
 *  - The pad must actually carry a USB Audio Class interface. A name alone would let an unrelated
 *    USB audio dongle lend its endpoints to a pad that has none.
 *  - The name must identify exactly one attached device. Two DualSenses (or a DualSense next to a
 *    DualShock 4, which shares the string "Wireless Controller") are indistinguishable here, and
 *    routing a slot to the wrong pad's speaker is worse than not routing it.
 *  - The name must identify at most one endpoint per direction, for the same reason.
 *
 * A pad that resolves to nothing simply advertises neither cap, which is the honest answer for a
 * pad whose audio function this device cannot confidently name.
 */
object PadAudioMatcher {
    fun resolve(
        pads: List<UsbAudioPad>,
        endpoints: List<UsbAudioEndpoint>,
    ): Map<Int, PadAudioRoute> {
        val padsByName =
            pads
                .filter { it.hasAudioFunction && !it.productName.isNullOrBlank() }
                .groupBy { it.productName!!.trim() }
        val sinks = uniqueByName(endpoints.filter { it.sink })
        val sources = uniqueByName(endpoints.filter { it.source })

        val out = HashMap<Int, PadAudioRoute>()
        for ((name, candidates) in padsByName) {
            val pad = candidates.singleOrNull() ?: continue
            val sink = sinks[name]
            val source = sources[name]
            if (sink == null && source == null) continue
            out[PadAudioRoutes.key(pad.vendorId, pad.productId)] =
                PadAudioRoute(
                    microphone = source != null,
                    speaker = sink != null,
                    captureDeviceId = source?.deviceId ?: NO_AUDIO_DEVICE,
                    playbackDeviceId = sink?.deviceId ?: NO_AUDIO_DEVICE,
                )
        }
        return out
    }

    // Named endpoints only, and only where the name picks out one of them. Duplicate ids are
    // folded first: some platforms hand the same endpoint back in more than one query.
    private fun uniqueByName(endpoints: List<UsbAudioEndpoint>): Map<String, UsbAudioEndpoint> =
        endpoints
            .filter { !it.productName.isNullOrBlank() }
            .distinctBy { it.deviceId }
            .groupBy { it.productName!!.trim() }
            .mapNotNull { (name, matches) -> matches.singleOrNull()?.let { name to it } }
            .toMap()
}
