// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.core.controllerdb.ActiveControllerDb
import com.tinkernorth.dish.core.controllerdb.ControllerRecord
import com.tinkernorth.dish.core.controllerdb.FoundEndpoints
import com.tinkernorth.dish.core.controllerdb.TRANSPORT_BLUETOOTH
import com.tinkernorth.dish.core.controllerdb.TRANSPORT_USB
import com.tinkernorth.dish.core.controllerdb.expectMismatches
import com.tinkernorth.dish.core.controllerdb.issuesFor
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.usb.UsbHardwareFacts

// The transport a pad's issues are matched on, by the database's names.
internal fun dbTransportOf(transport: Transport?): String = if (transport == Transport.Bluetooth) TRANSPORT_BLUETOOTH else TRANSPORT_USB

// A pad over Bluetooth is looked up by the ids it shows there first, then as its USB model, which
// is how most pads identify themselves over both.
internal fun recordFor(
    active: ActiveControllerDb?,
    vendorId: Int,
    productId: Int,
    transport: Transport?,
): ControllerRecord? {
    active ?: return null
    val index = active.index
    val isBluetooth = transport == Transport.Bluetooth
    val overBluetooth = if (isBluetooth) index.recordForBluetooth(vendorId, productId) else null
    return overBluetooth ?: index.recordForUsb(vendorId, productId)
}

internal fun recordFactsFor(
    device: PhysicalGamepadRegistry.Device,
    db: DbWorld,
): RecordFacts? {
    val active = db.active ?: return null
    val record = recordFor(active, device.vendorId, device.productId, device.transport) ?: return null
    val strings = active.index.strings
    val issues =
        issuesFor(record, dbTransportOf(device.transport), db.deviceModel, db.androidMajor).map {
            IssueFacts(it.id, strings.issueTitle(it.id, db.stringsLocale), strings.issueBody(it.id, db.stringsLocale))
        }
    return RecordFacts(
        id = record.id,
        name = strings.controllerName(record.id, db.stringsLocale),
        verification = record.support.verification.state,
        issues = issues,
    )
}

// Section 4.2: a Direct pad's enumeration against its record's expect, once per attach; a pad on
// any other path, or not yet enumerated, has nothing to compare.
internal fun expectMismatchesFor(
    device: PhysicalGamepadRegistry.Device,
    active: ActiveControllerDb?,
    hardware: UsbHardwareFacts?,
): List<String> {
    if (!device.isUsbSynthetic) return emptyList()
    hardware ?: return emptyList()
    val record = recordFor(active, device.vendorId, device.productId, device.transport)
    val found = FoundEndpoints(hasIn = hardware.endpointIn != null, hasOut = hardware.endpointOut != null)
    return expectMismatches(record, found)
}
