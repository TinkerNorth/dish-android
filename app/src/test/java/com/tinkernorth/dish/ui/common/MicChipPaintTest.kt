// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.audio.MicIndicatorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the app-wide mic chip renders each indicator state. What matters is unambiguity: the two
 * visible states must differ by glyph AND colour AND wording, so "hot" can never be mistaken
 * for "silenced" by shape-blind, colour-blind or glance-fast reading alone.
 */
class MicChipPaintTest {
    @Test
    fun `hidden really hides the chip`() {
        assertFalse(micChipPaintFor(MicIndicatorState.HIDDEN).visible)
    }

    @Test
    fun `live reads as a hot microphone`() {
        val paint = micChipPaintFor(MicIndicatorState.LIVE)
        assertTrue(paint.visible)
        assertEquals(R.drawable.ic_mic, paint.iconRes)
        assertEquals("the one colour reserved for things demanding attention", R.color.colorError, paint.tintRes)
        assertEquals(R.string.mic_state_live, paint.labelRes)
        assertEquals(R.string.mic_chip_live_desc, paint.descriptionRes)
    }

    @Test
    fun `muted stays visible but calm`() {
        // The chip must not vanish on mute: the mic still exists, and the user has to be able
        // to find the mute to undo it.
        val paint = micChipPaintFor(MicIndicatorState.MUTED)
        assertTrue(paint.visible)
        assertEquals(R.drawable.ic_mic_off, paint.iconRes)
        assertEquals(R.color.colorMuted, paint.tintRes)
        assertEquals(R.string.mic_state_muted, paint.labelRes)
        assertEquals(R.string.mic_chip_muted_desc, paint.descriptionRes)
    }

    @Test
    fun `the two visible states are unmistakable for one another`() {
        val live = micChipPaintFor(MicIndicatorState.LIVE)
        val muted = micChipPaintFor(MicIndicatorState.MUTED)
        assertNotEquals(live.iconRes, muted.iconRes)
        assertNotEquals(live.tintRes, muted.tintRes)
        assertNotEquals(live.labelRes, muted.labelRes)
        assertNotEquals(live.descriptionRes, muted.descriptionRes)
    }
}
