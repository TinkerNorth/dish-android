// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether a pad may claim an endpoint, and above all when it may not.
 *
 * Every "no route" row here is a claim NOT made. Advertising `mic` or `speaker` for a physical pad
 * tells a host to stream audio at a specific device, so a wrong match is a slot playing out of
 * somebody else's controller, and there is no public API that could disprove a guess: an
 * AudioDeviceInfo carries no vendor:product.
 */
class PadAudioMatcherTest {
    private val ds5 = PadAudioRoutes.key(DS5_VID, DS5_PID)
    private val ds4v2 = PadAudioRoutes.key(DS5_VID, DS4V2_PID)

    private fun pad(
        vendorId: Int = DS5_VID,
        productId: Int = DS5_PID,
        productName: String? = SONY_PAD_NAME,
        audio: Boolean = true,
        hapticLanes: Boolean = productId == DS5_PID,
    ) = UsbAudioPad(vendorId, productId, productName, hasAudioFunction = audio, hasHapticLanes = hapticLanes)

    private fun sink(
        deviceId: Int,
        name: String? = SONY_PAD_NAME,
        channelCounts: List<Int> = emptyList(),
    ) = UsbAudioEndpoint(deviceId, name, sink = true, source = false, channelCounts = channelCounts)

    private fun source(
        deviceId: Int,
        name: String? = SONY_PAD_NAME,
    ) = UsbAudioEndpoint(deviceId, name, sink = false, source = true)

    @Test
    fun `a pad with both endpoints claims both and names them`() {
        val routes = resolvePadAudioRoutes(listOf(pad()), listOf(sink(11), source(12)))
        val route = routes[ds5]!!
        assertTrue(route.microphone)
        assertTrue(route.speaker)
        assertEquals(12, route.captureDeviceId)
        assertEquals(11, route.playbackDeviceId)
    }

    @Test
    fun `a pad with only an output claims only the speaker`() {
        val route = resolvePadAudioRoutes(listOf(pad()), listOf(sink(11)))[ds5]!!
        assertFalse(route.microphone)
        assertTrue(route.speaker)
        assertEquals(NO_AUDIO_DEVICE, route.captureDeviceId)
        assertEquals(11, route.playbackDeviceId)
    }

    @Test
    fun `a pad with only an input claims only the microphone`() {
        val route = resolvePadAudioRoutes(listOf(pad()), listOf(source(12)))[ds5]!!
        assertTrue(route.microphone)
        assertFalse(route.speaker)
        assertEquals(12, route.captureDeviceId)
        assertEquals(NO_AUDIO_DEVICE, route.playbackDeviceId)
    }

    @Test
    fun `an endpoint one device combines both ways is matched in both directions`() {
        // A USB headset is enumerated as one AudioDeviceInfo that is both a sink and a source.
        val both = UsbAudioEndpoint(9, SONY_PAD_NAME, sink = true, source = true)
        val route = resolvePadAudioRoutes(listOf(pad()), listOf(both))[ds5]!!
        assertEquals(9, route.captureDeviceId)
        assertEquals(9, route.playbackDeviceId)
    }

    @Test
    fun `a pad with no endpoints at all publishes nothing`() {
        assertEquals(emptyMap<Int, PadAudioRoute>(), resolvePadAudioRoutes(listOf(pad()), emptyList()))
    }

    @Test
    fun `a pad with no USB audio interface never borrows another device's endpoints`() {
        // An unrelated USB audio dongle can share a product string; the audio-class interface is
        // what says this pad has a function of its own.
        val routes = resolvePadAudioRoutes(listOf(pad(audio = false)), listOf(sink(11), source(12)))
        assertEquals(emptyMap<Int, PadAudioRoute>(), routes)
    }

    @Test
    fun `two pads sharing one product name are both unmatchable`() {
        // Two DualSenses, or a DualSense next to a DualShock 4: Sony gives both the string
        // "Wireless Controller", and routing a slot to the wrong pad's speaker is worse than
        // routing it nowhere.
        val routes =
            resolvePadAudioRoutes(
                listOf(pad(), pad(productId = DS4V2_PID)),
                listOf(sink(11), source(12), sink(13), source(14)),
            )
        assertNull(routes[ds5])
        assertNull(routes[ds4v2])
        assertTrue(routes.isEmpty())
    }

    @Test
    fun `two endpoints under one name in the same direction are unmatchable`() {
        val routes = resolvePadAudioRoutes(listOf(pad()), listOf(sink(11), sink(13), source(12)))
        // The microphone still resolves: only the output direction is ambiguous.
        val route = routes[ds5]!!
        assertTrue(route.microphone)
        assertEquals(12, route.captureDeviceId)
        assertFalse(route.speaker)
        assertEquals(NO_AUDIO_DEVICE, route.playbackDeviceId)
    }

    @Test
    fun `the same endpoint listed twice is one endpoint, not an ambiguity`() {
        val routes = resolvePadAudioRoutes(listOf(pad()), listOf(sink(11), sink(11)))
        assertEquals(11, routes[ds5]!!.playbackDeviceId)
    }

    @Test
    fun `a nameless pad or a nameless endpoint publishes nothing`() {
        assertTrue(resolvePadAudioRoutes(listOf(pad(productName = null)), listOf(sink(11))).isEmpty())
        assertTrue(resolvePadAudioRoutes(listOf(pad(productName = "  ")), listOf(sink(11))).isEmpty())
        assertTrue(resolvePadAudioRoutes(listOf(pad()), listOf(sink(11, name = null))).isEmpty())
        assertTrue(resolvePadAudioRoutes(listOf(pad()), listOf(sink(11, name = ""))).isEmpty())
    }

    @Test
    fun `names are compared trimmed, since the descriptor string carries whatever padding it carries`() {
        val routes = resolvePadAudioRoutes(listOf(pad(productName = " $SONY_PAD_NAME ")), listOf(sink(11)))
        assertEquals(11, routes[ds5]!!.playbackDeviceId)
    }

    @Test
    fun `an endpoint whose name matches no attached pad is ignored`() {
        val routes = resolvePadAudioRoutes(listOf(pad()), listOf(sink(11, name = "USB Audio Interface")))
        assertTrue(routes.isEmpty())
    }

    @Test
    fun `two pads with different names each get their own endpoints`() {
        val other = pad(vendorId = OTHER_VID, productId = OTHER_PID, productName = "Pro Controller")
        val routes =
            resolvePadAudioRoutes(
                listOf(pad(), other),
                listOf(sink(11), source(12), sink(21, name = "Pro Controller")),
            )
        assertEquals(11, routes[ds5]!!.playbackDeviceId)
        assertEquals(12, routes[ds5]!!.captureDeviceId)
        val otherRoute = routes[PadAudioRoutes.key(OTHER_VID, OTHER_PID)]!!
        assertEquals(21, otherRoute.playbackDeviceId)
        assertFalse(otherRoute.microphone)
    }

    @Test
    fun `an empty world publishes an empty table rather than stale routes`() {
        assertEquals(emptyMap<Int, PadAudioRoute>(), resolvePadAudioRoutes(emptyList(), emptyList()))
        assertEquals(emptyMap<Int, PadAudioRoute>(), resolvePadAudioRoutes(emptyList(), listOf(sink(11))))
    }

    private companion object {
        const val DS5_VID = 0x054C
        const val DS5_PID = 0x0CE6
        const val DS4V2_PID = 0x09CC
        const val OTHER_VID = 0x057E
        const val OTHER_PID = 0x2009

        // The iProduct string a DualSense and a DualShock 4 both report.
        const val SONY_PAD_NAME = "Wireless Controller"
    }

    // ---- protocol 3: the haptic route ----

    @Test
    fun `the haptic route needs the family's lanes AND an endpoint that opens at four channels`() {
        // A DualSense whose endpoint the platform will open at 4: the lanes are there.
        val quad = resolvePadAudioRoutes(listOf(pad()), listOf(sink(11, channelCounts = listOf(2, 4))))
        assertTrue(quad.getValue(ds5).speaker)
        assertTrue(quad.getValue(ds5).haptics)
        assertEquals(4, quad.getValue(ds5).playbackChannels)

        // Stereo only, or a platform that would not say: speaker only, and the width the
        // track opens at rides the route either way.
        val stereo = resolvePadAudioRoutes(listOf(pad()), listOf(sink(11, channelCounts = listOf(2))))
        assertTrue(stereo.getValue(ds5).speaker)
        assertFalse(stereo.getValue(ds5).haptics)
        assertEquals(2, stereo.getValue(ds5).playbackChannels)
        val unknown = resolvePadAudioRoutes(listOf(pad()), listOf(sink(11)))
        assertFalse(unknown.getValue(ds5).haptics)
        assertEquals(0, unknown.getValue(ds5).playbackChannels)

        // A DualShock 4 v2 in front of a 4-channel endpoint is still no actuator: the
        // family gate holds whatever the platform reports.
        val ds4 =
            resolvePadAudioRoutes(
                listOf(pad(productId = DS4V2_PID)),
                listOf(sink(11, channelCounts = listOf(4))),
            )
        assertTrue(ds4.getValue(ds4v2).speaker)
        assertFalse(ds4.getValue(ds4v2).haptics)
    }
}
