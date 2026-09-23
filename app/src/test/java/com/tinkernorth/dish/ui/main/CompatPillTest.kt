// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.net.DishProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompatPillTest {
    @Test
    fun `a still-working older satellite gets the soft amber chip`() {
        assertEquals(
            R.string.chip_satellite_update_available to PillTone.WARN,
            compatPillParts(DishProtocol.DishProtocolCompat.SATELLITE_UPDATE_AVAILABLE),
        )
    }

    @Test
    fun `hard blocks get the error chip naming the side that must update`() {
        assertEquals(
            R.string.chip_satellite_update_required to PillTone.ERROR,
            compatPillParts(DishProtocol.DishProtocolCompat.SATELLITE_UPDATE_REQUIRED),
        )
        assertEquals(
            R.string.chip_app_update_required to PillTone.ERROR,
            compatPillParts(DishProtocol.DishProtocolCompat.APP_UPDATE_REQUIRED),
        )
    }

    @Test
    fun `current and unknown render nothing`() {
        assertNull(compatPillParts(DishProtocol.DishProtocolCompat.CURRENT))
        assertNull(compatPillParts(DishProtocol.DishProtocolCompat.UNKNOWN))
    }
}
