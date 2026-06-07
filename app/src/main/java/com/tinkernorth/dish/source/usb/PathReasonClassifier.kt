// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

internal fun classifyDirectPathReason(
    vendorId: Int,
    productId: Int,
    isKnownFastLaneModel: Boolean,
    isUsbPresent: Boolean,
    priorFailure: PathReason?,
): PathReason =
    when {
        vendorId == 0 || productId == 0 -> PathReason.UnknownModel
        !isKnownFastLaneModel -> PathReason.UnknownModel
        !isUsbPresent -> PathReason.Bluetooth
        else -> priorFailure ?: PathReason.Eligible
    }
