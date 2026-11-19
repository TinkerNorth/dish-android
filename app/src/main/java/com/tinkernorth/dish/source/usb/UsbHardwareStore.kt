// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.usb

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import com.tinkernorth.dish.core.input.vidPidKey
import javax.inject.Inject
import javax.inject.Singleton

// One USB interface as the device describes it.
data class UsbInterfaceSummary(
    val number: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
)

// What USB enumeration says about a pad, read once when it is seen: the identity, the strings the
// manufacturer gave it, its interfaces, and the endpoints of the interface a claim would read.
// The serial number is never read.
data class UsbHardwareFacts(
    val vendorId: Int,
    val productId: Int,
    val bcdDevice: String?,
    val product: String?,
    val manufacturer: String?,
    val interfaces: List<UsbInterfaceSummary>,
    val endpointIn: Int?,
    val endpointOut: Int?,
    // SHA-256 of the raw descriptors, read when a Direct claim opens the device.
    val descriptorSha256: String? = null,
)

@Singleton
class UsbHardwareStore
    @Inject
    constructor() : AbstractStateSource<Map<Int, UsbHardwareFacts>>(emptyMap()) {
        fun note(facts: UsbHardwareFacts) {
            setState { it + (vidPidKey(facts.vendorId, facts.productId) to facts) }
        }

        fun noteDescriptorSha256(
            vendorId: Int,
            productId: Int,
            sha256: String,
        ) {
            val key = vidPidKey(vendorId, productId)
            setState { held -> held[key]?.let { held + (key to it.copy(descriptorSha256 = sha256)) } ?: held }
        }
    }
