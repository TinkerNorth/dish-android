// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry

internal fun routedTwinIdsHiddenBySynthetics(devices: Collection<PhysicalGamepadRegistry.Device>): Set<Int> {
    val syntheticModelCounts =
        devices
            .filter { it.isUsbSynthetic && it.vendorId != 0 && it.productId != 0 }
            .groupingBy { it.vendorId to it.productId }
            .eachCount()
    if (syntheticModelCounts.isEmpty()) return emptySet()
    return devices
        .filter { !it.isUsbSynthetic && (it.vendorId to it.productId) in syntheticModelCounts }
        .groupBy { it.vendorId to it.productId }
        .flatMap { (model, routed) ->
            routed
                .sortedByDescending { it.isDisconnecting }
                .take(syntheticModelCounts[model] ?: 0)
                .map { it.id }
        }.toSet()
}
