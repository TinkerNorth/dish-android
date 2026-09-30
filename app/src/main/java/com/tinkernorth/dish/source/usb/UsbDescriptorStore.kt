// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import com.tinkernorth.dish.core.input.vidPidKey
import javax.inject.Inject
import javax.inject.Singleton

data class UsbEndpointFacts(
    val intervalRaw: Int,
    val maxPacketSize: Int,
    val pollRateHz: Int,
    val highSpeed: Boolean,
    val interfaceClass: Int,
    val hasOutEndpoint: Boolean,
)

@Singleton
class UsbDescriptorStore
    @Inject
    constructor() : AbstractStateSource<Map<Int, UsbEndpointFacts>>(emptyMap()) {
        fun note(
            vendorId: Int,
            productId: Int,
            facts: UsbEndpointFacts,
        ) {
            setState { it + (vidPidKey(vendorId, productId) to facts) }
        }

        fun factsFor(
            vendorId: Int,
            productId: Int,
        ): UsbEndpointFacts? = state.value[vidPidKey(vendorId, productId)]
    }
