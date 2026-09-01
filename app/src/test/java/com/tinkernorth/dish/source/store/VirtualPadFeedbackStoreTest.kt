// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The virtual pad's rendered feedback. The mic-mute lamp is host-only state: MSG_MIC_LED is its
 * one writer, so it always means "the host said this". The local mute never passes through here;
 * the mute pill draws that on its own face straight from [MicMuteStore], which is what keeps a
 * host-driven lamp from ever repainting a muted microphone as live.
 */
class VirtualPadFeedbackStoreTest {
    private val store = VirtualPadFeedbackStore()

    @Test
    fun `the lamp starts off`() {
        assertEquals(MIC_LED_OFF, store.state.value.micLedState)
    }

    @Test
    fun `the lamp carries exactly what the host sent, in all three states`() {
        for (state in listOf(MIC_LED_ON, MIC_LED_PULSE, MIC_LED_OFF)) {
            store.setMicLed(state)
            assertEquals(state, store.state.value.micLedState)
        }
    }

    @Test
    fun `a lamp state this client does not know is clamped rather than rendered`() {
        // Native already drops these, and the store does not rely on that.
        store.setMicLed(7)
        assertEquals(MIC_LED_PULSE, store.state.value.micLedState)
        store.setMicLed(-1)
        assertEquals(MIC_LED_OFF, store.state.value.micLedState)
    }

    @Test
    fun `the lamp does not disturb the other feedback surfaces`() {
        store.setLightbar(0x11, 0x22, 0x33)
        store.setPlayerLeds(0x05)
        store.setTriggerEffects(leftActive = true, rightActive = false)
        store.setMicLed(MIC_LED_ON)

        val state = store.state.value
        assertEquals(0xFF112233.toInt(), state.lightbarColor)
        assertEquals(0x05, state.playerLedMask)
        assertEquals(true, state.leftTriggerEffect)
        assertEquals(false, state.rightTriggerEffect)
        assertEquals(MIC_LED_ON, state.micLedState)
    }
}
