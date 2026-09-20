// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
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
        transport: Transport = Transport.Usb,
    ) = PhysicalGamepadRegistry.Device(
        id = id,
        name = "DualSense",
        isUsbSynthetic = direct,
        vendorId = DS5_VID,
        productId = DS5_PID,
        transport = transport,
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
    fun `a framework pad on USB gets its own endpoints too`() {
        // Same vendor:product, and the OS owns the pad's HID; its audio function is the OS's
        // on either path, so the route is the same one a claim would get.
        devices.value = mapOf(FRAMEWORK_ID to device(FRAMEWORK_ID, direct = false))
        publishDs5Route()
        val route = routing.forSlot(FRAMEWORK_ID.toString())
        assertEquals(12, route.captureDeviceId)
        assertEquals(11, route.playbackDeviceId)
    }

    @Test
    fun `a Bluetooth pad never routes, even with a USB twin's route published`() {
        // No audio function over Bluetooth, and the same vendor:product as a USB DualSense:
        // keying it would hand it that twin's endpoint.
        devices.value = mapOf(FRAMEWORK_ID to device(FRAMEWORK_ID, direct = false, transport = Transport.Bluetooth))
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
