// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
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
            setState { it + (key(vendorId, productId) to facts) }
        }

        fun factsFor(
            vendorId: Int,
            productId: Int,
        ): UsbEndpointFacts? = state.value[key(vendorId, productId)]

        companion object {
            private const val VENDOR_SHIFT = 16
            private const val PRODUCT_MASK = 0xFFFF

            fun key(
                vendorId: Int,
                productId: Int,
            ): Int = (vendorId shl VENDOR_SHIFT) or (productId and PRODUCT_MASK)
        }
    }
