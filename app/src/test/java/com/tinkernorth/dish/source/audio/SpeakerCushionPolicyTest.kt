// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cushion-refill rule, one row at a time.
 *
 * This exists because the satellite stopped sending anything for a digitally silent window. That
 * makes a quiet stream drain the track, and a drained track plays the next sound with no cushion at
 * all, which is the condition the start threshold was written to prevent. The rule has to fire on
 * exactly the drained case and on nothing else: refilling when the track kept up would add 40 ms of
 * latency for free, and not refilling when it drained leaves the stream one hiccup from a click.
 */
class SpeakerCushionPolicyTest {
    @Test
    fun `a track that underran since the last window gets the cushion back`() {
        assertEquals(
            CUSHION,
            SpeakerCushionPolicy.refillSamples(
                playing = true,
                underruns = 1,
                lastSeenUnderruns = 0,
                cushionSamples = CUSHION,
            ),
        )
    }

    @Test
    fun `a track that kept up is written straight through`() {
        assertEquals(
            0,
            SpeakerCushionPolicy.refillSamples(
                playing = true,
                underruns = 4,
                lastSeenUnderruns = 4,
                cushionSamples = CUSHION,
            ),
        )
    }

    @Test
    fun `the cushion is paid once per drain, not once per window after it`() {
        // The caller records the count it acted on, so the run of windows behind the resumed one
        // must not each buy another 40 ms. Two refills for one silence would compound into
        // latency the stream never gets back.
        var seen = 0
        val first =
            SpeakerCushionPolicy.refillSamples(
                playing = true,
                underruns = 3,
                lastSeenUnderruns = seen,
                cushionSamples = CUSHION,
            )
        seen = 3
        val second =
            SpeakerCushionPolicy.refillSamples(
                playing = true,
                underruns = 3,
                lastSeenUnderruns = seen,
                cushionSamples = CUSHION,
            )
        assertEquals(CUSHION, first)
        assertEquals(0, second)
    }

    @Test
    fun `several underruns in one silence still buy exactly one cushion`() {
        assertEquals(
            CUSHION,
            SpeakerCushionPolicy.refillSamples(
                playing = true,
                underruns = 50,
                lastSeenUnderruns = 0,
                cushionSamples = CUSHION,
            ),
        )
    }

    @Test
    fun `nothing is written before playback has started`() {
        // The start threshold owns the cushion until it releases the track; refilling underneath it
        // would count the same silence twice and start the stream 40 ms later than it has to.
        assertEquals(
            0,
            SpeakerCushionPolicy.refillSamples(
                playing = false,
                underruns = 9,
                lastSeenUnderruns = 0,
                cushionSamples = CUSHION,
            ),
        )
    }

    @Test
    fun `a counter that went backwards is a reset, not a drain`() {
        // A flush or a fresh track on the same session restarts the count. Treating that as an
        // underrun would inject a cushion into a track that never missed a sample.
        assertEquals(
            0,
            SpeakerCushionPolicy.refillSamples(
                playing = true,
                underruns = 0,
                lastSeenUnderruns = 12,
                cushionSamples = CUSHION,
            ),
        )
    }

    @Test
    fun `a sink that asked for no cushion is left alone`() {
        for (size in intArrayOf(0, -1)) {
            assertEquals(
                0,
                SpeakerCushionPolicy.refillSamples(
                    playing = true,
                    underruns = 1,
                    lastSeenUnderruns = 0,
                    cushionSamples = size,
                ),
            )
        }
    }

    @Test
    fun `every combination of the two gates, and exactly one of them refills`() {
        // Two bits: playing, and the counter having moved. Only both-true may write silence.
        for (bits in 0 until 4) {
            val playing = bits and 1 != 0
            val drained = bits and 2 != 0
            val refill =
                SpeakerCushionPolicy.refillSamples(
                    playing = playing,
                    underruns = if (drained) 1 else 0,
                    lastSeenUnderruns = 0,
                    cushionSamples = CUSHION,
                )
            assertEquals(
                "playing=$playing drained=$drained",
                if (playing && drained) CUSHION else 0,
                refill,
            )
        }
    }

    private companion object {
        // Two 20 ms stereo windows, which is what the sink primes with.
        const val CUSHION = 960 * 2 * 2
    }
}
