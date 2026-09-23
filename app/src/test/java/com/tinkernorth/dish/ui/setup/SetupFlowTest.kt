// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupFlowTest {
    @Test
    fun `raw values round-trip through inputTypeOf`() {
        SetupInputType.entries.forEach { type ->
            assertEquals(type, inputTypeOf(rawOf(type)))
        }
    }

    @Test
    fun `known raw strings map to their input type`() {
        assertEquals(SetupInputType.USB, inputTypeOf(INPUT_USB))
        assertEquals(SetupInputType.BLUETOOTH_CONTROLLER, inputTypeOf(INPUT_BLUETOOTH))
        assertEquals(SetupInputType.ONSCREEN, inputTypeOf(INPUT_ONSCREEN))
    }

    @Test
    fun `unknown or null raw falls back to on-screen`() {
        assertEquals(SetupInputType.ONSCREEN, inputTypeOf(null))
        assertEquals(SetupInputType.ONSCREEN, inputTypeOf("something-else"))
    }
}
