// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

// The active index's usb rows as the parallel arrays ModelTableNative.setServedModels takes; an
// inert record and a malformed identity are left out.
class ServedRows(
    val vendorIds: IntArray,
    val productIds: IntArray,
    val parsers: IntArray,
    val inits: IntArray,
    val orders: IntArray,
    val verified: BooleanArray,
) {
    val size: Int get() = vendorIds.size
}

private data class ServedRow(
    val vendorId: Int,
    val productId: Int,
    val parser: Int,
    val init: Int,
    val order: Int,
    val verified: Boolean,
)

fun servedRowsOf(index: ControllerDbIndex): ServedRows {
    val rows =
        index.db.records
            .filter(::isAUsbRecordToHandDown)
            .flatMap(::rowsOf)
    return ServedRows(
        vendorIds = rows.map { it.vendorId }.toIntArray(),
        productIds = rows.map { it.productId }.toIntArray(),
        parsers = rows.map { it.parser }.toIntArray(),
        inits = rows.map { it.init }.toIntArray(),
        orders = rows.map { it.order }.toIntArray(),
        verified = rows.map { it.verified }.toBooleanArray(),
    )
}

private fun isAUsbRecordToHandDown(record: ControllerRecord): Boolean {
    val isUsb = record.support.verification.transport == TRANSPORT_USB
    return isUsb && !isInert(record)
}

private fun rowsOf(record: ControllerRecord): List<ServedRow> {
    val parser = NATIVE_PARSERS.getValue(record.input.protocol.ref)
    val init = NATIVE_INIT_KINDS.getValue(record.output.init.kind)
    val isHid = record.input.protocol.ref == FAMILY_HID
    val order = if (isHid) NATIVE_BUTTON_ORDERS.getValue(mappingName(record)) else NATIVE_BUTTON_ORDERS.getValue(MAPPING_WESTERN)
    // Native's flag is the auto rule: Direct by default, and a claim even when the pad is silent at rest.
    val verified = directRuleOf(record) == DIRECT_RULE_AUTO
    return record.identity.usb
        .mapNotNull(::parseIdentity)
        .map { (vid, pid) -> ServedRow(vid, pid, parser, init, order, verified) }
}
