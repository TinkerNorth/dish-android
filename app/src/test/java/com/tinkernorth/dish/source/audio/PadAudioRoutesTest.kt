// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The pad-to-audio-endpoint table. The resolver that fills it is not built yet, so these
// pin the contract the capability layer already depends on: absent means absent, a
// published table is read per pad, and the key really does separate models.
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
}
