// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

// Pins the wire-to-screen orientation: XUSB sticks are up-positive, screen pixels grow
// downward. A regression here shows every stick mirrored vertically in the inspector.
class StickPlotMappingTest {
    @Test
    fun `stick pushed up draws at the top of the plot`() {
        assertEquals(0f, stickPlotFractionY(1f), 1e-6f)
    }

    @Test
    fun `stick pushed down draws at the bottom of the plot`() {
        assertEquals(1f, stickPlotFractionY(-1f), 1e-6f)
    }

    @Test
    fun `stick pushed right draws at the right edge`() {
        assertEquals(1f, stickPlotFractionX(1f), 1e-6f)
        assertEquals(0f, stickPlotFractionX(-1f), 1e-6f)
    }

    @Test
    fun `a centered stick draws at the crosshair`() {
        assertEquals(0.5f, stickPlotFractionX(0f), 1e-6f)
        assertEquals(0.5f, stickPlotFractionY(0f), 1e-6f)
    }
}
