// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.connection

import com.tinkernorth.dish.core.net.ControllerDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test

class LateSlotConvergeTest {
    private fun desc(
        idx: Int,
        type: Int = 0,
        caps: Int = ControllerDescriptor.CAP_RUMBLE,
        mode: String = ControllerDescriptor.TOUCHPAD_MODE_OFF,
    ) = ControllerDescriptor(ctrlIdx = idx, type = type, caps = caps, touchpadMode = mode)

    @Test
    fun `identical sent and desired need no converge`() {
        val set = listOf(desc(0), desc(1, type = 1))

        assertEquals(LateSlotConverge(emptyList(), emptyList()), lateSlotConverge(set, set))
    }

    @Test
    fun `empty sent and desired need no converge`() {
        assertEquals(LateSlotConverge(emptyList(), emptyList()), lateSlotConverge(emptyList(), emptyList()))
    }

    @Test
    fun `a slot the PUT never carried is resynced`() {
        val converge = lateSlotConverge(sent = emptyList(), desired = listOf(desc(0, type = 1)))

        assertEquals(listOf(0), converge.resyncs)
        assertEquals(emptyList<Int>(), converge.deletes)
    }

    @Test
    fun `a type changed since the snapshot is resynced`() {
        val converge =
            lateSlotConverge(
                sent = listOf(desc(0, type = 0)),
                desired = listOf(desc(0, type = 1)),
            )

        assertEquals(listOf(0), converge.resyncs)
    }

    @Test
    fun `a touchpad mode changed since the snapshot is resynced`() {
        val converge =
            lateSlotConverge(
                sent = listOf(desc(0, mode = ControllerDescriptor.TOUCHPAD_MODE_OFF)),
                desired = listOf(desc(0, mode = ControllerDescriptor.TOUCHPAD_MODE_DS4)),
            )

        assertEquals(listOf(0), converge.resyncs)
    }

    @Test
    fun `caps changed since the snapshot are resynced`() {
        val converge =
            lateSlotConverge(
                sent = listOf(desc(0, caps = ControllerDescriptor.CAP_RUMBLE)),
                desired = listOf(desc(0, caps = ControllerDescriptor.CAP_RUMBLE or ControllerDescriptor.CAP_MOTION)),
            )

        assertEquals(listOf(0), converge.resyncs)
    }

    @Test
    fun `a slot removed since the snapshot is deleted`() {
        val converge = lateSlotConverge(sent = listOf(desc(0)), desired = emptyList())

        assertEquals(emptyList<Int>(), converge.resyncs)
        assertEquals(listOf(0), converge.deletes)
    }

    @Test
    fun `an index re-used with an identical descriptor is left alone`() {
        val converge = lateSlotConverge(sent = listOf(desc(0, type = 1)), desired = listOf(desc(0, type = 1)))

        assertEquals(LateSlotConverge(emptyList(), emptyList()), converge)
    }

    @Test
    fun `an index re-used with a different descriptor is resynced not deleted`() {
        val converge = lateSlotConverge(sent = listOf(desc(0, type = 0)), desired = listOf(desc(0, type = 1)))

        assertEquals(listOf(0), converge.resyncs)
        assertEquals(emptyList<Int>(), converge.deletes)
    }

    @Test
    fun `adds changes and removes resolve independently`() {
        val converge =
            lateSlotConverge(
                sent = listOf(desc(0, type = 0), desc(1, type = 1), desc(2, type = 0)),
                desired = listOf(desc(0, type = 0), desc(1, type = 0), desc(3, type = 1)),
            )

        assertEquals(listOf(1, 3), converge.resyncs)
        assertEquals(listOf(2), converge.deletes)
    }
}
