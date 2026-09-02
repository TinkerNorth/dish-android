// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.source.store.MIC_LED_OFF
import com.tinkernorth.dish.source.store.MIC_LED_ON
import com.tinkernorth.dish.source.store.MIC_LED_PULSE
import com.tinkernorth.dish.source.store.VirtualPadFeedbackStore
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The router's one job: land feedback on what the slot can actuate — a
 * Direct-claimed pad's OUT endpoint, or the virtual pad's skin/vibrator.
 * Framework pads have no reachable LED or trigger motors and must swallow
 * everything silently.
 */
class FeedbackRouterTest {
    private val native: PhysicalInputNative = mockk(relaxed = true)
    private val store = VirtualPadFeedbackStore()
    private val rumble: RumbleRouter = mockk(relaxed = true)

    private fun managerWith(
        handle: Int,
        slotId: String,
        controllerIndex: Int = 0,
    ): SatelliteConnectionManager {
        val conn = mockk<SatelliteConnection>()
        every { conn.handle } returns handle
        every { conn.state } returns MutableStateFlow(SatelliteSessionState.Live)
        every { conn.slots } returns
            MutableStateFlow(
                mapOf(slotId to SatelliteConnection.SlotBinding(controllerIndex = controllerIndex, controllerType = 2, registered = true)),
            )
        val manager = mockk<SatelliteConnectionManager>()
        every { manager.connections } returns MutableStateFlow(mapOf("c" to conn))
        return manager
    }

    private fun router(manager: SatelliteConnectionManager = mockk(relaxed = true)) = FeedbackRouter(manager, native, store, rumble)

    @Test
    fun `lightbar reaches a Direct-claimed pad through the session resolve`() {
        router(managerWith(handle = 7, slotId = "-1000"))
            .dispatchLightbar(sessionHandle = 7, controllerIndex = 0, r = 1, g = 2, b = 3)
        verify(exactly = 1) { native.sendUsbLightbar(-1000, 1, 2, 3) }
    }

    @Test
    fun `trigger effects reach a Direct-claimed pad as the raw block bytes`() {
        val blocks = ByteArray(22) { it.toByte() }
        router(managerWith(handle = 7, slotId = "-1000"))
            .dispatchTriggerEffects(sessionHandle = 7, controllerIndex = 0, blocks = blocks)
        verify(exactly = 1) { native.sendUsbTriggerEffects(-1000, blocks) }
    }

    @Test
    fun `player LEDs reach a Direct-claimed pad`() {
        router(managerWith(handle = 7, slotId = "-1000"))
            .dispatchPlayerLeds(sessionHandle = 7, controllerIndex = 0, ledMask = 0x1F)
        verify(exactly = 1) { native.sendUsbPlayerLeds(-1000, 0x1F) }
    }

    @Test
    fun `the virtual pad renders lightbar and LEDs through the feedback store`() {
        val r = router(managerWith(handle = 7, slotId = VIRTUAL_SLOT_ID))
        r.dispatchLightbar(sessionHandle = 7, controllerIndex = 0, r = 0x11, g = 0x22, b = 0x33)
        r.dispatchPlayerLeds(sessionHandle = 7, controllerIndex = 0, ledMask = 0x05)
        assertEquals(0xFF112233.toInt(), store.state.value.lightbarColor)
        assertEquals(0x05, store.state.value.playerLedMask)
        verify(exactly = 0) { native.sendUsbLightbar(any(), any(), any(), any()) }
        verify(exactly = 0) { native.sendUsbPlayerLeds(any(), any()) }
    }

    @Test
    fun `the virtual pad shows which triggers hold a non-neutral effect`() {
        val r = router(managerWith(handle = 7, slotId = VIRTUAL_SLOT_ID))
        // Wire order left (0..10) then right (11..21); byte 0 of each is the mode.
        val leftOnly = ByteArray(22).also { it[0] = 0x21 }
        r.dispatchTriggerEffects(sessionHandle = 7, controllerIndex = 0, blocks = leftOnly)
        assertTrue(store.state.value.leftTriggerEffect)
        assertFalse(store.state.value.rightTriggerEffect)
        val bothOff = ByteArray(22)
        r.dispatchTriggerEffects(sessionHandle = 7, controllerIndex = 0, blocks = bothOff)
        assertFalse(store.state.value.leftTriggerEffect)
        assertFalse(store.state.value.rightTriggerEffect)
        verify(exactly = 0) { native.sendUsbTriggerEffects(any(), any()) }
    }

    @Test
    fun `the mic-mute lamp paints the virtual pad's lamp and only the lamp`() {
        val r = router(managerWith(handle = 7, slotId = VIRTUAL_SLOT_ID))
        r.dispatchMicLed(sessionHandle = 7, controllerIndex = 0, state = MIC_LED_ON)
        // The lamp is the host's alone; what the user muted lives in MicMuteStore and paints
        // the pill's face directly, so nothing a host sends can reach it through here.
        assertEquals(MIC_LED_ON, store.state.value.micLedState)

        r.dispatchMicLed(sessionHandle = 7, controllerIndex = 0, state = MIC_LED_PULSE)
        assertEquals(MIC_LED_PULSE, store.state.value.micLedState)
    }

    @Test
    fun `a Direct-claimed pad's lamp reaches its own output report`() {
        val r = router(managerWith(handle = 7, slotId = "-1000"))
        for (state in listOf(MIC_LED_OFF, MIC_LED_ON, MIC_LED_PULSE)) {
            r.dispatchMicLed(sessionHandle = 7, controllerIndex = 0, state = state)
            verify(exactly = 1) { native.sendUsbMicMuteLed(-1000, state) }
        }
        // And never onto the phone's skin: that pad is not the phone.
        assertEquals(MIC_LED_OFF, store.state.value.micLedState)
    }

    @Test
    fun `the lamp routes per target exactly like the lightbar`() {
        // Direct pads write, the phone paints, framework pads drop. The three arms are the same
        // three the lightbar resolves to, which is the point: one resolve, one target set.
        router(managerWith(handle = 7, slotId = "-1000")).dispatchMicLed(7, 0, MIC_LED_ON)
        verify(exactly = 1) { native.sendUsbMicMuteLed(-1000, MIC_LED_ON) }

        router(managerWith(handle = 7, slotId = VIRTUAL_SLOT_ID)).dispatchMicLed(7, 0, MIC_LED_PULSE)
        assertEquals(MIC_LED_PULSE, store.state.value.micLedState)

        router(managerWith(handle = 7, slotId = "9")).dispatchMicLed(7, 0, MIC_LED_OFF)
        verify(exactly = 0) { native.sendUsbMicMuteLed(9, any()) }

        // An unknown session or controller index resolves to nothing at all.
        val r = router(managerWith(handle = 7, slotId = "-1000"))
        r.dispatchMicLed(sessionHandle = 8, controllerIndex = 0, state = MIC_LED_ON)
        r.dispatchMicLed(sessionHandle = 7, controllerIndex = 3, state = MIC_LED_ON)
        verify(exactly = 1) { native.sendUsbMicMuteLed(any(), any()) }
    }

    @Test
    fun `framework targets swallow feedback silently`() {
        router(managerWith(handle = 7, slotId = "9"))
            .dispatchLightbar(7, 0, 1, 2, 3)
        verify(exactly = 0) { native.sendUsbLightbar(any(), any(), any(), any()) }
        assertEquals(null, store.state.value.lightbarColor)
    }

    @Test
    fun `an unknown session or controller index resolves to nothing`() {
        val r = router(managerWith(handle = 7, slotId = "-1000"))
        r.dispatchLightbar(sessionHandle = 8, controllerIndex = 0, r = 1, g = 2, b = 3)
        r.dispatchLightbar(sessionHandle = 7, controllerIndex = 3, r = 1, g = 2, b = 3)
        verify(exactly = 0) { native.sendUsbLightbar(any(), any(), any(), any()) }
    }

    @Test
    fun `moonlight slot-addressed dispatch actuates Direct pads and the virtual sinks`() {
        val r = router()
        r.dispatchTriggerRumbleToSlot("-1000", 100, 200)
        r.dispatchLightbarToSlot("-1000", 5, 6, 7)
        verify(exactly = 1) { native.sendUsbTriggerRumble(-1000, 100, 200) }
        verify(exactly = 1) { native.sendUsbLightbar(-1000, 5, 6, 7) }

        // Virtual: trigger rumble folds through the rumble path (toggle + stop
        // rules apply there); the lightbar paints the skin.
        r.dispatchTriggerRumbleToSlot(VIRTUAL_SLOT_ID, 300, 400)
        verify(exactly = 1) { rumble.dispatchToSlot(VIRTUAL_SLOT_ID, 300, 400, any()) }
        r.dispatchLightbarToSlot(VIRTUAL_SLOT_ID, 9, 8, 7)
        assertEquals(0xFF090807.toInt(), store.state.value.lightbarColor)

        // Framework: nothing reachable.
        r.dispatchTriggerRumbleToSlot("9", 1, 2)
        verify(exactly = 0) { native.sendUsbTriggerRumble(9, any(), any()) }
        verify(exactly = 0) { rumble.dispatchToSlot("9", any(), any(), any()) }
    }
}
