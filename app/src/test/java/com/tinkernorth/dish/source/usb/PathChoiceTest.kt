// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PathChoiceTest {
    @Test
    fun `every choice round trips through its storage value`() {
        for (choice in PathChoice.values()) {
            assertEquals(choice, pathChoiceFromStorage(choice.toStorageValue()))
        }
    }

    @Test
    fun `an absent or unrecognised storage value resolves to null (Auto)`() {
        assertNull(pathChoiceFromStorage(null))
        assertNull(pathChoiceFromStorage("legacy-unknown-value"))
    }
}
