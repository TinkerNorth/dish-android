// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import com.tinkernorth.dish.ui.common.GamepadConstants.MIC_MUTE_LAMP_ALPHA
import com.tinkernorth.dish.ui.common.GamepadConstants.MIC_MUTE_PULSE_MIN_ALPHA
import com.tinkernorth.dish.ui.common.GamepadConstants.MIC_MUTE_PULSE_PERIOD_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the on-screen pad renders the three lamp states MSG_MIC_LED carries.
 *
 * Off draws nothing, on is the solid accent, and pulse breathes it. The last one is the reason this
 * is a function and not a branch inside onDraw: a wave that jumped at the seam of its period, or
 * that never reached the accent the solid state uses, would make the two lit states read as two
 * different colours rather than one lamp in two modes.
 */
class MicMuteLampTest {
    private val off = GamepadTouchView.MIC_LED_STATE_OFF
    private val on = 1
    private val pulse = GamepadTouchView.MIC_LED_STATE_PULSE

    @Test
    fun `off paints nothing at all`() {
        assertEquals(0, micMuteLampAlpha(off, 0))
        assertEquals(0, micMuteLampAlpha(off, 1_234_567))
    }

    @Test
    fun `on is the solid accent every other feedback surface uses`() {
        assertEquals(MIC_MUTE_LAMP_ALPHA, micMuteLampAlpha(on, 0))
        assertEquals("a solid lamp does not move with the clock", MIC_MUTE_LAMP_ALPHA, micMuteLampAlpha(on, 987))
    }

    @Test
    fun `pulse sweeps the whole range between the trough and the solid accent`() {
        val half = MIC_MUTE_PULSE_PERIOD_MS / 2
        assertEquals("the period starts at the trough", MIC_MUTE_PULSE_MIN_ALPHA, micMuteLampAlpha(pulse, 0))
        assertEquals("and peaks at the same accent 'on' uses", MIC_MUTE_LAMP_ALPHA, micMuteLampAlpha(pulse, half))
        // Quarter and three-quarter phases sit between, and symmetrically.
        val rising = micMuteLampAlpha(pulse, MIC_MUTE_PULSE_PERIOD_MS / 4)
        val falling = micMuteLampAlpha(pulse, MIC_MUTE_PULSE_PERIOD_MS * 3 / 4)
        assertTrue("a quarter in is mid-breath, got $rising", rising in (MIC_MUTE_PULSE_MIN_ALPHA + 1) until MIC_MUTE_LAMP_ALPHA)
        assertTrue("the breath is symmetric, got $rising then $falling", kotlin.math.abs(rising - falling) <= 1)
    }

    @Test
    fun `pulse stays inside the accent's alpha range for a whole period`() {
        for (ms in 0 until MIC_MUTE_PULSE_PERIOD_MS step STEP_MS) {
            val alpha = micMuteLampAlpha(pulse, ms)
            assertTrue("alpha $alpha at $ms ms is outside the lamp's range", alpha in MIC_MUTE_PULSE_MIN_ALPHA..MIC_MUTE_LAMP_ALPHA)
        }
    }

    @Test
    fun `pulse is continuous across the seam of its period`() {
        // The wave is a raised cosine so the end of one period meets the start of the next; a
        // triangle or a sawtooth would step there, and a step reads as a blink.
        val end = micMuteLampAlpha(pulse, MIC_MUTE_PULSE_PERIOD_MS - 1)
        val start = micMuteLampAlpha(pulse, MIC_MUTE_PULSE_PERIOD_MS)
        assertTrue("$end then $start is a jump, not a breath", kotlin.math.abs(end - start) <= 2)
        assertEquals("and the period really repeats", start, micMuteLampAlpha(pulse, 0))
    }

    @Test
    fun `pulse is visibly different from on, which is what tells the two lit states apart`() {
        assertNotEquals(micMuteLampAlpha(on, 0), micMuteLampAlpha(pulse, 0))
    }

    @Test
    fun `an uptime that has been running for days still pulses`() {
        // SystemClock.uptimeMillis climbs without bound; the phase must come from a modulus, not
        // from an offset that overflows or saturates.
        val days = 5L * 24 * 60 * 60 * 1000
        assertEquals(micMuteLampAlpha(pulse, 0), micMuteLampAlpha(pulse, days))
        assertEquals(micMuteLampAlpha(pulse, MIC_MUTE_PULSE_PERIOD_MS / 2), micMuteLampAlpha(pulse, days + MIC_MUTE_PULSE_PERIOD_MS / 2))
    }

    @Test
    fun `a state this client does not know paints like on rather than not at all`() {
        // Native drops anything past pulse before it reaches the view, so this is belt and braces:
        // a lamp the host asked for is better shown lit than shown off.
        assertEquals(MIC_MUTE_LAMP_ALPHA, micMuteLampAlpha(3, 0))
    }

    private companion object {
        const val STEP_MS = 25L
    }
}
