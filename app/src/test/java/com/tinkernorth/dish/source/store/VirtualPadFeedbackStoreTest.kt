// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The virtual pad's rendered feedback. The mic-mute lamp is the interesting field: it has TWO
 * writers, the local mute and the host's MSG_MIC_LED, and last writer wins the way it does on the
 * hardware. It is the lamp, not the mute: what gates capture is [MicMuteStore].
 */
class VirtualPadFeedbackStoreTest {
    private val store = VirtualPadFeedbackStore()

    @Test
    fun `the lamp starts off`() {
        assertEquals(MIC_LED_OFF, store.state.value.micLedState)
    }

    @Test
    fun `muting locally lights the lamp immediately`() {
        // Without this the button would look dead for as long as a host that may never send
        // MSG_MIC_LED took to decide, which on most hosts is forever.
        store.setLocalMicMute(true)
        assertEquals(MIC_LED_ON, store.state.value.micLedState)
        store.setLocalMicMute(false)
        assertEquals(MIC_LED_OFF, store.state.value.micLedState)
    }

    @Test
    fun `a host lamp overrides the local paint, and a later local mute overrides it back`() {
        store.setLocalMicMute(true)
        store.setMicLed(MIC_LED_OFF)
        assertEquals("the game owns the lamp once it drives it", MIC_LED_OFF, store.state.value.micLedState)

        store.setMicLed(MIC_LED_PULSE)
        assertEquals(MIC_LED_PULSE, store.state.value.micLedState)

        store.setLocalMicMute(false)
        assertEquals(MIC_LED_OFF, store.state.value.micLedState)
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
        store.setLocalMicMute(true)

        val state = store.state.value
        assertEquals(0xFF112233.toInt(), state.lightbarColor)
        assertEquals(0x05, state.playerLedMask)
        assertEquals(true, state.leftTriggerEffect)
        assertEquals(false, state.rightTriggerEffect)
        assertEquals(MIC_LED_ON, state.micLedState)
    }
}
