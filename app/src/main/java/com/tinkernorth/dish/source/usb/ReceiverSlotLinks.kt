// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.usb

// How often a receiver's slots are read for their controllers, so a controller switched on
// reaches its card within half a second.
internal const val RECEIVER_LINK_POLL_MS = 500L

// The binding a receiver slot's pad held when its controller left.
internal data class HeldBinding(
    val connectionId: String,
    val type: Int?,
)

// One slot of a wireless receiver that the model's Direct claim holds: its HID interface, the
// synthetic id it was attached under, and whether it is on the slot list as a pad.
internal data class ReceiverSlot(
    val interfaceNumber: Int,
    val syntheticId: Int,
    val pollRateHz: Int,
    val listed: Boolean = false,
    val heldBinding: HeldBinding? = null,
)

internal sealed interface SlotEffect {
    val slot: ReceiverSlot

    // The slot's controller connected: its pad goes on the list, bound as it left or as a new claim.
    data class ListPad(
        override val slot: ReceiverSlot,
    ) : SlotEffect

    // The slot's controller left: its pad comes off the list.
    data class UnlistPad(
        override val slot: ReceiverSlot,
    ) : SlotEffect
}

internal data class SlotFollow(
    val slots: List<ReceiverSlot>,
    val effects: List<SlotEffect>,
)

// What one read of a slot's link does to it.
private enum class SlotChange { ARRIVES, LEAVES, STAYS }

// One slot after a read of its link, and what that read asks for.
private data class SlotStep(
    val slot: ReceiverSlot,
    val effect: SlotEffect?,
)

// The slots after a read of their links: [linked] holds the synthetic ids whose controller is
// connected, [bindings] what each listed pad is bound to now.
internal fun followSlotLinks(
    slots: List<ReceiverSlot>,
    linked: Set<Int>,
    bindings: Map<Int, HeldBinding>,
): SlotFollow {
    val steps = slots.map { slot -> stepOf(slot, slot.syntheticId in linked, bindings[slot.syntheticId]) }
    return SlotFollow(steps.map { it.slot }, steps.mapNotNull { it.effect })
}

private fun slotChangeOf(
    linked: Boolean,
    listed: Boolean,
): SlotChange {
    val unchanged = linked == listed
    if (unchanged) return SlotChange.STAYS
    return if (linked) SlotChange.ARRIVES else SlotChange.LEAVES
}

private fun stepOf(
    slot: ReceiverSlot,
    linked: Boolean,
    binding: HeldBinding?,
): SlotStep =
    when (slotChangeOf(linked, slot.listed)) {
        SlotChange.ARRIVES -> arrive(slot)
        SlotChange.LEAVES -> leave(slot, binding)
        SlotChange.STAYS -> SlotStep(slot, null)
    }

// The pad is listed with the binding the slot held; from then on the hub holds it.
private fun arrive(slot: ReceiverSlot): SlotStep {
    val listed = slot.copy(listed = true)
    return SlotStep(listed.copy(heldBinding = null), SlotEffect.ListPad(listed))
}

private fun leave(
    slot: ReceiverSlot,
    binding: HeldBinding?,
): SlotStep {
    val away = slot.copy(listed = false, heldBinding = binding)
    return SlotStep(away, SlotEffect.UnlistPad(away))
}
