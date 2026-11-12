// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.usb

import com.tinkernorth.dish.core.input.vidPidKey

private const val VALVE_VENDOR_ID = 0x28DE
private const val STEAM_CONTROLLER_DONGLE_PRODUCT_ID = 0x1142
private const val TRITON_PROTEUS_DONGLE_PRODUCT_ID = 0x1304
private const val TRITON_NEREID_DONGLE_PRODUCT_ID = 0x1305
private val STEAM_CONTROLLER_DONGLE_SLOTS = 1..4
private val TRITON_DONGLE_SLOTS = 2..5

// The HID interfaces a wireless receiver serves its paired controllers on, one each, and the only
// ones it is read from: hid-steam's steam_is_valve_interface for the 2015 receiver (interface 0 is
// its keyboard), SDL's HIDAPI_DriverSteamTriton_IsSupportedDevice for the 2026 ones.
private val RECEIVER_SLOTS =
    mapOf(
        vidPidKey(VALVE_VENDOR_ID, STEAM_CONTROLLER_DONGLE_PRODUCT_ID) to STEAM_CONTROLLER_DONGLE_SLOTS,
        vidPidKey(VALVE_VENDOR_ID, TRITON_PROTEUS_DONGLE_PRODUCT_ID) to TRITON_DONGLE_SLOTS,
        vidPidKey(VALVE_VENDOR_ID, TRITON_NEREID_DONGLE_PRODUCT_ID) to TRITON_DONGLE_SLOTS,
    )

// A wireless receiver's slot interfaces; null for a model that is one pad.
internal fun receiverSlotsOf(
    vendorId: Int,
    productId: Int,
): IntRange? = RECEIVER_SLOTS[vidPidKey(vendorId, productId)]

// The candidates a Direct claim of the model opens, as indices: every slot a receiver lists, or the
// one pickGameInterface picks for any other model.
internal fun interfacesToClaim(
    vendorId: Int,
    productId: Int,
    candidates: List<UsbInterfaceCandidate>,
): List<Int> {
    val slots = receiverSlotsOf(vendorId, productId)
    return if (slots == null) ownPadInterface(vendorId, productId, candidates) else slotCandidates(slots, candidates)
}

private fun ownPadInterface(
    vendorId: Int,
    productId: Int,
    candidates: List<UsbInterfaceCandidate>,
): List<Int> = listOfNotNull(pickGameInterface(vendorId, productId, candidates))

// In interface order, each interface once: an alternate setting repeats its interface's number.
private fun slotCandidates(
    slots: IntRange,
    candidates: List<UsbInterfaceCandidate>,
): List<Int> =
    candidates
        .withIndex()
        .filter { it.value.number in slots }
        .distinctBy { it.value.number }
        .sortedBy { it.value.number }
        .map { it.index }

// The pad a Direct claim hands forward: a receiver's first connected slot, any other model's own.
fun UsbController.firstPadId(): Int? = if (receiver) slotPads.firstOrNull() else syntheticId
