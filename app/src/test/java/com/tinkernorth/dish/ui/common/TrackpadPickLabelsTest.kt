// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import com.tinkernorth.dish.R
import com.tinkernorth.dish.repository.TrackpadPick
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackpadPickLabelsTest {
    @Test
    fun `every pick has its own label`() {
        assertEquals(R.string.trackpad_pick_stick, trackpadPickLabelRes(TrackpadPick.STICK))
        assertEquals(R.string.trackpad_pick_pad, trackpadPickLabelRes(TrackpadPick.PAD))
        assertEquals(R.string.trackpad_pick_mouse, trackpadPickLabelRes(TrackpadPick.MOUSE))
        assertEquals(R.string.trackpad_pick_off, trackpadPickLabelRes(TrackpadPick.OFF))
        val labels = TrackpadPick.entries.map { trackpadPickLabelRes(it) }
        assertEquals(TrackpadPick.entries.size, labels.toSet().size)
    }
}
