// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The route table, asked the question the audio engines actually have: given a SLOT, which endpoint
 * should its recorder and its track prefer?
 */
interface SlotAudioRoutes {
    /**
     * The table itself, for engines that have to REGROUP when it moves rather than just read it. A
     * pad's endpoint appearing or vanishing changes which recorder a slot belongs to and which
     * output its track wants, and neither of those is visible in the plan that carries the slot.
     */
    val changes: StateFlow<Map<Int, PadAudioRoute>>

    fun forSlot(slotId: String): PadAudioRoute

    companion object {
        /** A device with no pad endpoints at all: everything plays out of, and into, the phone. */
        val NONE: SlotAudioRoutes =
            object : SlotAudioRoutes {
                override val changes = MutableStateFlow(emptyMap<Int, PadAudioRoute>())

                override fun forSlot(slotId: String) = PadAudioRoute.NONE
            }
    }
}

/**
 * The real answer, from the published table and the device registry.
 *
 * Only a Direct-claimed pad ever names an endpoint. The virtual pad IS the phone's own microphone
 * and speaker, and a framework pad is one we never claimed, so its audio function (if it even has
 * one) is not ours to point at either. Both fall through to [PadAudioRoute.NONE], which the engines
 * read as "let the platform route", and which is the right answer for a phone standing in for the
 * emulated pad's endpoints.
 *
 * Kept apart from [PadAudioRoutes] so the table stays a plain published map: this is the only place
 * that knows a slot id is a device id, and it is the same lookup the capability composer does when
 * it decides whether to advertise the caps at all.
 */
@Singleton
class PadAudioRouting
    @Inject
    constructor(
        private val registry: PhysicalGamepadRegistry,
        private val routes: PadAudioRoutes,
    ) : SlotAudioRoutes {
        override val changes: StateFlow<Map<Int, PadAudioRoute>> get() = routes.state

        override fun forSlot(slotId: String): PadAudioRoute {
            val deviceId = slotId.toIntOrNull() ?: return PadAudioRoute.NONE
            val device = registry.devices.value[deviceId] ?: return PadAudioRoute.NONE
            if (!device.isUsbSynthetic) return PadAudioRoute.NONE
            return routes.routeFor(device.vendorId, device.productId)
        }
    }
