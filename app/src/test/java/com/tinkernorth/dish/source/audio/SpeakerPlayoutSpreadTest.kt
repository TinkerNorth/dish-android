// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerPlayoutSpreadTest {
    private val window = shortArrayOf(1, 2, 3, 4)

    @Test
    fun `a stereo device takes the window as it is`() {
        val scratch = ShortArray(0)

        assertSame(window, spreadStereoToDevice(window, channels = PlayoutLane.STEREO_CHANNELS, pairOffset = 0, scratch = scratch))
    }

    @Test
    fun `the speaker pair lands at offset zero and the haptic pair stays silent`() {
        val out = spreadStereoToDevice(window, channels = PlayoutLane.QUAD_CHANNELS, pairOffset = 0, scratch = ShortArray(0))

        assertArrayEquals(shortArrayOf(1, 2, 0, 0, 3, 4, 0, 0), out)
    }

    @Test
    fun `the haptic pair lands at offset two and the speaker pair stays silent`() {
        val out = spreadStereoToDevice(window, channels = PlayoutLane.QUAD_CHANNELS, pairOffset = 2, scratch = ShortArray(0))

        assertArrayEquals(shortArrayOf(0, 0, 1, 2, 0, 0, 3, 4), out)
    }

    @Test
    fun `a scratch of the right size is reused`() {
        val scratch = ShortArray(8)

        val out = spreadStereoToDevice(window, channels = PlayoutLane.QUAD_CHANNELS, pairOffset = 0, scratch = scratch)

        assertSame(scratch, out)
    }

    @Test
    fun `a scratch of the wrong size is replaced`() {
        val scratch = ShortArray(4)

        val out = spreadStereoToDevice(window, channels = PlayoutLane.QUAD_CHANNELS, pairOffset = 0, scratch = scratch)

        assertNotSame(scratch, out)
        assertEquals(8, out.size)
    }

    @Test
    fun `samples written are counted in stereo samples whatever the device width`() {
        assertEquals(4, stereoSamplesWritten(written = 8, channels = PlayoutLane.QUAD_CHANNELS))
        assertEquals(8, stereoSamplesWritten(written = 8, channels = PlayoutLane.STEREO_CHANNELS))
    }

    @Test
    fun `a partial device write rounds down to whole stereo frames`() {
        assertEquals(2, stereoSamplesWritten(written = 6, channels = PlayoutLane.QUAD_CHANNELS))
    }

    @Test
    fun `the haptic lane is refused on a stereo endpoint`() {
        assertFalse(laneFitsEndpoint(PlayoutLane.HAPTICS, channels = PlayoutLane.STEREO_CHANNELS))
    }

    @Test
    fun `both lanes fit a quad endpoint`() {
        assertTrue(laneFitsEndpoint(PlayoutLane.SPEAKER, channels = PlayoutLane.QUAD_CHANNELS))
        assertTrue(laneFitsEndpoint(PlayoutLane.HAPTICS, channels = PlayoutLane.QUAD_CHANNELS))
    }

    @Test
    fun `the speaker lane fits a stereo endpoint`() {
        assertTrue(laneFitsEndpoint(PlayoutLane.SPEAKER, channels = PlayoutLane.STEREO_CHANNELS))
    }
}
