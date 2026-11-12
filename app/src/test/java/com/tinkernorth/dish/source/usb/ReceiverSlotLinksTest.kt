// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverSlotLinksTest {
    private val pollRateHz = 250

    // A receiver's four slots as its claim attaches them, one synthetic id per interface.
    private val claimed =
        listOf(1, 2, 3, 4).map { ReceiverSlot(interfaceNumber = it, syntheticId = SYNTHETIC_BASE - it, pollRateHz = pollRateHz) }

    private fun synthetic(interfaceNumber: Int) = SYNTHETIC_BASE - interfaceNumber

    private fun linkedOn(vararg interfaceNumbers: Int): Set<Int> = interfaceNumbers.map(::synthetic).toSet()

    private fun listedOf(slots: List<ReceiverSlot>): List<Int> = slots.filter { it.listed }.map { it.interfaceNumber }

    private fun followed(
        slots: List<ReceiverSlot>,
        linked: Set<Int>,
        bindings: Map<Int, HeldBinding> = emptyMap(),
    ): SlotFollow = followSlotLinks(slots, linked, bindings)

    @Test
    fun `each slot whose controller is connected goes on the list as a pad of its own`() {
        val follow = followed(claimed, linkedOn(1, 2, 4))

        assertEquals(listOf(1, 2, 4), listedOf(follow.slots))
        assertEquals(
            listOf(synthetic(1), synthetic(2), synthetic(4)),
            follow.effects.filterIsInstance<SlotEffect.ListPad>().map { it.slot.syntheticId },
        )
    }

    @Test
    fun `an empty slot never goes on the list`() {
        val first = followed(claimed, linkedOn(1))
        val second = followed(first.slots, linkedOn(1))

        assertFalse(second.slots.single { it.interfaceNumber == 3 }.listed)
        assertTrue(second.effects.isEmpty())
    }

    @Test
    fun `a slot whose controller stays connected changes nothing`() {
        val listed = followed(claimed, linkedOn(2)).slots

        val again = followed(listed, linkedOn(2))

        assertEquals(listed, again.slots)
        assertTrue(again.effects.isEmpty())
    }

    @Test
    fun `a controller that disconnects comes off the list`() {
        val listed = followed(claimed, linkedOn(1, 2)).slots

        val follow = followed(listed, linkedOn(1))

        assertEquals(listOf(1), listedOf(follow.slots))
        assertEquals(listOf(synthetic(2)), follow.effects.filterIsInstance<SlotEffect.UnlistPad>().map { it.slot.syntheticId })
        assertEquals(1, follow.effects.size)
    }

    @Test
    fun `a controller that disconnects holds the binding its pad had`() {
        val listed = followed(claimed, linkedOn(2)).slots
        val binding = HeldBinding(connectionId = "sat-1", type = 3)

        val follow = followed(listed, emptySet(), mapOf(synthetic(2) to binding))

        assertEquals(binding, follow.slots.single { it.interfaceNumber == 2 }.heldBinding)
    }

    @Test
    fun `a controller that reconnects comes back as the same pad with the binding it left with`() {
        val binding = HeldBinding(connectionId = "sat-1", type = 3)
        val listed = followed(claimed, linkedOn(2)).slots
        val left = followed(listed, emptySet(), mapOf(synthetic(2) to binding)).slots

        val back = followed(left, linkedOn(2))

        val listPad = back.effects.single() as SlotEffect.ListPad
        assertEquals(synthetic(2), listPad.slot.syntheticId)
        assertEquals(binding, listPad.slot.heldBinding)
    }

    @Test
    fun `a pad back on the list no longer holds the binding it came back with`() {
        val listed = followed(claimed, linkedOn(2)).slots
        val left = followed(listed, emptySet(), mapOf(synthetic(2) to HeldBinding("sat-1", 3))).slots

        val back = followed(left, linkedOn(2))

        assertNull(back.slots.single { it.interfaceNumber == 2 }.heldBinding)
    }

    @Test
    fun `an unbound pad that leaves holds no binding`() {
        val listed = followed(claimed, linkedOn(2)).slots

        val left = followed(listed, emptySet())

        assertNull(left.slots.single { it.interfaceNumber == 2 }.heldBinding)
    }

    @Test
    fun `a slot keeps its pad when another slot's controller leaves`() {
        val listed = followed(claimed, linkedOn(1, 3)).slots

        val follow = followed(listed, linkedOn(3))

        val third = follow.slots.single { it.interfaceNumber == 3 }
        assertTrue(third.listed)
        assertEquals(synthetic(3), third.syntheticId)
        assertTrue(follow.effects.none { it.slot.interfaceNumber == 3 })
    }

    @Test
    fun `slots keep their interface order`() {
        val follow = followed(claimed, linkedOn(4, 1))

        assertEquals(listOf(1, 2, 3, 4), follow.slots.map { it.interfaceNumber })
        assertEquals(listOf(1, 4), follow.effects.map { it.slot.interfaceNumber })
    }

    private companion object {
        const val SYNTHETIC_BASE = -1000
    }
}
