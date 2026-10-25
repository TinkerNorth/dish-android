// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.donate

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildTipOfferTest {
    @Test
    fun `the amazon build asks for no tips, so every donation surface stays hidden`() {
        assertEquals(TipOffer.None, TIP_OFFER)
    }
}
