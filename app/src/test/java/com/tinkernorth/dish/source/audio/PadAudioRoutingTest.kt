// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Slot to endpoint. The interesting half is which slots get NO route: the virtual pad is the
 * phone's own microphone and speaker, and a framework pad is one we never claimed, so pointing
 * either at a USB endpoint would be a claim neither of them can honour.
 */
class PadAudioRoutingTest {
    private val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
    private val registry: PhysicalGamepadRegistry =
        mockk {
            every { this@mockk.devices } returns this@PadAudioRoutingTest.devices
        }
    private val routes = PadAudioRoutes()
    private val routing = PadAudioRouting(registry, routes)

    private fun device(
        id: Int,
        direct: Boolean,
    ) = PhysicalGamepadRegistry.Device(
        id = id,
        name = "DualSense",
        isUsbSynthetic = direct,
        vendorId = DS5_VID,
        productId = DS5_PID,
    )

    private fun publishDs5Route() {
        routes.publishRoutes(
            mapOf(
                PadAudioRoutes.key(DS5_VID, DS5_PID) to
                    PadAudioRoute(
                        microphone = true,
                        speaker = true,
                        captureDeviceId = 12,
                        playbackDeviceId = 11,
                    ),
            ),
        )
    }

    @Test
    fun `a Direct-claimed pad gets its own endpoints`() {
        devices.value = mapOf(DIRECT_ID to device(DIRECT_ID, direct = true))
        publishDs5Route()
        val route = routing.forSlot(DIRECT_ID.toString())
        assertEquals(12, route.captureDeviceId)
        assertEquals(11, route.playbackDeviceId)
    }

    @Test
    fun `the virtual pad never routes to a USB endpoint`() {
        devices.value = mapOf(DIRECT_ID to device(DIRECT_ID, direct = true))
        publishDs5Route()
        assertEquals(PadAudioRoute.NONE, routing.forSlot(VIRTUAL_SLOT_ID))
    }

    @Test
    fun `a framework pad has no route, whatever its model publishes`() {
        // Same vendor:product, but the OS owns the pad: we never claimed its HID interface, so its
        // audio function is not ours to point at either.
        devices.value = mapOf(FRAMEWORK_ID to device(FRAMEWORK_ID, direct = false))
        publishDs5Route()
        assertEquals(PadAudioRoute.NONE, routing.forSlot(FRAMEWORK_ID.toString()))
    }

    @Test
    fun `a slot the registry no longer knows has no route`() {
        publishDs5Route()
        assertEquals(PadAudioRoute.NONE, routing.forSlot(DIRECT_ID.toString()))
    }

    @Test
    fun `a claimed pad with no published endpoints has no route`() {
        devices.value = mapOf(DIRECT_ID to device(DIRECT_ID, direct = true))
        assertEquals(PadAudioRoute.NONE, routing.forSlot(DIRECT_ID.toString()))
    }

    @Test
    fun `the table is exposed as the flow the engines regroup on`() {
        assertEquals(routes.state, routing.changes)
    }

    @Test
    fun `the no-routes implementation answers nothing for every slot`() {
        assertEquals(PadAudioRoute.NONE, SlotAudioRoutes.NONE.forSlot(VIRTUAL_SLOT_ID))
        assertEquals(PadAudioRoute.NONE, SlotAudioRoutes.NONE.forSlot("-1000"))
        assertEquals(emptyMap<Int, PadAudioRoute>(), SlotAudioRoutes.NONE.changes.value)
    }

    private companion object {
        const val DS5_VID = 0x054C
        const val DS5_PID = 0x0CE6

        // Direct-claimed slots are the negative synthetic ids; framework ones are positive.
        const val DIRECT_ID = -1000
        const val FRAMEWORK_ID = 9
    }
}
