// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/**
 * The router's one job: land feedback on a Direct-claimed pad and nowhere
 * else. The framework has no LED/trigger API and the phone has no such
 * hardware, so every non-Direct target must swallow the event silently.
 */
class FeedbackRouterTest {
    private val native: PhysicalInputNative = mockk(relaxed = true)

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

    @Test
    fun `lightbar reaches a Direct-claimed pad through the session resolve`() {
        val router = FeedbackRouter(managerWith(handle = 7, slotId = "-1000"), native)
        router.dispatchLightbar(sessionHandle = 7, controllerIndex = 0, r = 1, g = 2, b = 3)
        verify(exactly = 1) { native.sendUsbLightbar(-1000, 1, 2, 3) }
    }

    @Test
    fun `trigger effects reach a Direct-claimed pad as the raw block bytes`() {
        val router = FeedbackRouter(managerWith(handle = 7, slotId = "-1000"), native)
        val blocks = ByteArray(22) { it.toByte() }
        router.dispatchTriggerEffects(sessionHandle = 7, controllerIndex = 0, blocks = blocks)
        verify(exactly = 1) { native.sendUsbTriggerEffects(-1000, blocks) }
    }

    @Test
    fun `player LEDs reach a Direct-claimed pad`() {
        val router = FeedbackRouter(managerWith(handle = 7, slotId = "-1000"), native)
        router.dispatchPlayerLeds(sessionHandle = 7, controllerIndex = 0, ledMask = 0x1F)
        verify(exactly = 1) { native.sendUsbPlayerLeds(-1000, 0x1F) }
    }

    @Test
    fun `framework and virtual targets swallow feedback silently`() {
        // Framework pad: positive device id.
        FeedbackRouter(managerWith(handle = 7, slotId = "9"), native)
            .dispatchLightbar(7, 0, 1, 2, 3)
        // Virtual pad.
        FeedbackRouter(managerWith(handle = 7, slotId = com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID), native)
            .dispatchPlayerLeds(7, 0, 0x01)
        verify(exactly = 0) { native.sendUsbLightbar(any(), any(), any(), any()) }
        verify(exactly = 0) { native.sendUsbPlayerLeds(any(), any()) }
    }

    @Test
    fun `an unknown session or controller index resolves to nothing`() {
        val router = FeedbackRouter(managerWith(handle = 7, slotId = "-1000"), native)
        router.dispatchLightbar(sessionHandle = 8, controllerIndex = 0, r = 1, g = 2, b = 3)
        router.dispatchLightbar(sessionHandle = 7, controllerIndex = 3, r = 1, g = 2, b = 3)
        verify(exactly = 0) { native.sendUsbLightbar(any(), any(), any(), any()) }
    }

    @Test
    fun `moonlight slot-addressed dispatch actuates Direct pads only`() {
        val router = FeedbackRouter(mockk(relaxed = true), native)
        router.dispatchTriggerRumbleToSlot("-1000", 100, 200)
        router.dispatchTriggerRumbleToSlot("9", 300, 400)
        router.dispatchLightbarToSlot("-1000", 5, 6, 7)
        router.dispatchLightbarToSlot(com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID, 1, 1, 1)
        verify(exactly = 1) { native.sendUsbTriggerRumble(-1000, 100, 200) }
        verify(exactly = 1) { native.sendUsbLightbar(-1000, 5, 6, 7) }
        verify(exactly = 0) { native.sendUsbTriggerRumble(9, any(), any()) }
    }
}
