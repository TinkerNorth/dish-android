// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class PathReasonClassifierTest {
    @Test
    fun `zero vendor id is UnknownModel even if everything else looks claimable`() {
        assertEquals(
            PathReason.UnknownModel,
            classifyDirectPathReason(
                vendorId = 0,
                productId = 0x028E,
                isKnownFastLaneModel = true,
                isUsbPresent = true,
                priorFailure = null,
            ),
        )
    }

    @Test
    fun `zero product id is UnknownModel`() {
        assertEquals(
            PathReason.UnknownModel,
            classifyDirectPathReason(
                vendorId = 0x045E,
                productId = 0,
                isKnownFastLaneModel = true,
                isUsbPresent = true,
                priorFailure = null,
            ),
        )
    }

    @Test
    fun `model not in the parser table is UnknownModel`() {
        assertEquals(
            PathReason.UnknownModel,
            classifyDirectPathReason(
                vendorId = 0x1234,
                productId = 0x5678,
                isKnownFastLaneModel = false,
                isUsbPresent = true,
                priorFailure = null,
            ),
        )
    }

    @Test
    fun `known model not present on the USB bus is Bluetooth`() {
        assertEquals(
            PathReason.Bluetooth,
            classifyDirectPathReason(
                vendorId = 0x057E,
                productId = 0x2009,
                isKnownFastLaneModel = true,
                isUsbPresent = false,
                priorFailure = null,
            ),
        )
    }

    @Test
    fun `known model on the USB bus with no prior failure is Eligible`() {
        assertEquals(
            PathReason.Eligible,
            classifyDirectPathReason(
                vendorId = 0x045E,
                productId = 0x028E,
                isKnownFastLaneModel = true,
                isUsbPresent = true,
                priorFailure = null,
            ),
        )
    }

    @Test
    fun `a prior failure on the USB bus locks the card to that reason`() {
        assertEquals(
            PathReason.Busy,
            classifyDirectPathReason(
                vendorId = 0x045E,
                productId = 0x028E,
                isKnownFastLaneModel = true,
                isUsbPresent = true,
                priorFailure = PathReason.Busy,
            ),
        )
    }

    @Test
    fun `init-failed is carried through as the prior failure reason`() {
        assertEquals(
            PathReason.InitFailed,
            classifyDirectPathReason(
                vendorId = 0x045E,
                productId = 0x028E,
                isKnownFastLaneModel = true,
                isUsbPresent = true,
                priorFailure = PathReason.InitFailed,
            ),
        )
    }

    @Test
    fun `missing vendor id takes precedence over a known model and a prior failure`() {
        assertEquals(
            PathReason.UnknownModel,
            classifyDirectPathReason(
                vendorId = 0,
                productId = 0x028E,
                isKnownFastLaneModel = true,
                isUsbPresent = true,
                priorFailure = PathReason.Busy,
            ),
        )
    }

    @Test
    fun `not present takes precedence over a prior failure for a known model`() {
        assertEquals(
            PathReason.Bluetooth,
            classifyDirectPathReason(
                vendorId = 0x045E,
                productId = 0x028E,
                isKnownFastLaneModel = true,
                isUsbPresent = false,
                priorFailure = PathReason.Busy,
            ),
        )
    }
}
