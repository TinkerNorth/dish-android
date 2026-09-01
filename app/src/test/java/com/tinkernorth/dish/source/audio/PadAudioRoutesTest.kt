// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The pad-to-audio-endpoint table: absent means absent, a published table is read per pad, the key
// really does separate models, and a claimed surface comes with the endpoint id that backs it.
// What decides WHICH endpoint belongs to which pad is PadAudioMatcherTest.
class PadAudioRoutesTest {
    private val ds5 = PadAudioRoutes.key(0x054C, 0x0CE6)
    private val ds4v2 = PadAudioRoutes.key(0x054C, 0x09CC)

    @Test
    fun `an empty table reports no route for anything`() {
        val routes = PadAudioRoutes()
        assertEquals(PadAudioRoute.NONE, routes.routeFor(0x054C, 0x0CE6))
        assertFalse(routes.routeFor(0x054C, 0x0CE6).microphone)
        assertFalse(routes.routeFor(0x054C, 0x0CE6).speaker)
        assertTrue(routes.state.value.isEmpty())
    }

    @Test
    fun `publishRoutes republishes the whole table`() {
        val routes = PadAudioRoutes()
        routes.publishRoutes(mapOf(ds5 to PadAudioRoute(microphone = true, speaker = true)))

        assertTrue(routes.routeFor(0x054C, 0x0CE6).microphone)
        assertTrue(routes.routeFor(0x054C, 0x0CE6).speaker)

        // The resolver owns the table wholesale: a pad that vanished from the new map is
        // gone, not merged forward, or its caps would outlive the cable.
        routes.publishRoutes(mapOf(ds4v2 to PadAudioRoute(microphone = false, speaker = true)))
        assertEquals(PadAudioRoute.NONE, routes.routeFor(0x054C, 0x0CE6))
        assertTrue(routes.routeFor(0x054C, 0x09CC).speaker)
        assertFalse(routes.routeFor(0x054C, 0x09CC).microphone)
    }

    @Test
    fun `the two endpoints are reported independently`() {
        val routes = PadAudioRoutes()
        routes.publishRoutes(mapOf(ds5 to PadAudioRoute(microphone = true, speaker = false)))
        assertTrue(routes.routeFor(0x054C, 0x0CE6).microphone)
        assertFalse(routes.routeFor(0x054C, 0x0CE6).speaker)
    }

    @Test
    fun `the key separates models of the same vendor and vendors of the same model`() {
        assertNotEquals(ds5, ds4v2)
        assertNotEquals(PadAudioRoutes.key(0x054C, 0x0CE6), PadAudioRoutes.key(0x057E, 0x0CE6))
        assertEquals(PadAudioRoutes.key(0x054C, 0x0CE6), PadAudioRoutes.key(0x054C, 0x0CE6))
    }

    @Test
    fun `the state flow is what re-publishes the capability map`() {
        // The composer combines on this flow, so a device change has to move it.
        val routes = PadAudioRoutes()
        val before = routes.state.value
        routes.publishRoutes(mapOf(ds5 to PadAudioRoute(microphone = true, speaker = true)))
        assertNotEquals(before, routes.state.value)
    }

    @Test
    fun `a route carries the endpoint ids the engines set as their preferred device`() {
        val routes = PadAudioRoutes()
        routes.publishRoutes(
            mapOf(
                ds5 to
                    PadAudioRoute(
                        microphone = true,
                        speaker = true,
                        captureDeviceId = 12,
                        playbackDeviceId = 11,
                    ),
            ),
        )
        assertEquals(12, routes.routeFor(0x054C, 0x0CE6).captureDeviceId)
        assertEquals(11, routes.routeFor(0x054C, 0x0CE6).playbackDeviceId)
    }

    @Test
    fun `an absent route names no endpoint, which is the platform's own routing`() {
        val routes = PadAudioRoutes()
        assertEquals(NO_AUDIO_DEVICE, routes.routeFor(0x054C, 0x0CE6).captureDeviceId)
        assertEquals(NO_AUDIO_DEVICE, routes.routeFor(0x054C, 0x0CE6).playbackDeviceId)
        assertEquals(NO_AUDIO_DEVICE, PadAudioRoute.NONE.playbackDeviceId)
    }

    @Test
    fun `an endpoint id changing is a change the engines can see`() {
        // A replug renumbers the endpoint while the caps stay true, and a track built against the
        // old id would play out of nothing.
        val routes = PadAudioRoutes()
        val first = mapOf(ds5 to PadAudioRoute(microphone = false, speaker = true, playbackDeviceId = 11))
        val second = mapOf(ds5 to PadAudioRoute(microphone = false, speaker = true, playbackDeviceId = 13))
        routes.publishRoutes(first)
        val before = routes.state.value
        routes.publishRoutes(second)
        assertNotEquals(before, routes.state.value)
        assertEquals(13, routes.routeFor(0x054C, 0x0CE6).playbackDeviceId)
    }
}
