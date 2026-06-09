// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

import org.junit.Assert.assertEquals
import org.junit.Test

// The path-resolution policy, lifted out of UsbGamepadManager so each branch is checkable directly.
class UsbPathResolutionTest {
    @Test
    fun `an explicit stored pick always wins`() {
        assertEquals(
            PathChoice.Direct,
            resolvePathChoice(stored = PathChoice.Direct, isFastLaneModel = false, priorFailure = null),
        )
        assertEquals(
            PathChoice.Standard,
            resolvePathChoice(stored = PathChoice.Standard, isFastLaneModel = true, priorFailure = null),
        )
    }

    @Test
    fun `with no stored pick a verified fast-lane model auto-selects Direct`() {
        assertEquals(
            PathChoice.Direct,
            resolvePathChoice(stored = null, isFastLaneModel = true, priorFailure = null),
        )
    }

    @Test
    fun `a fast-lane model that just failed to claim is not auto-Directed`() {
        assertEquals(
            PathChoice.Standard,
            resolvePathChoice(stored = null, isFastLaneModel = true, priorFailure = DirectClaimFailure.Busy),
        )
    }

    @Test
    fun `an unknown model with no stored pick defaults to Standard`() {
        assertEquals(
            PathChoice.Standard,
            resolvePathChoice(stored = null, isFastLaneModel = false, priorFailure = null),
        )
    }
}
