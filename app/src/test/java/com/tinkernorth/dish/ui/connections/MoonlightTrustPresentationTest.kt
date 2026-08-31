// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.connections

import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.connection.moonlight.MoonlightTrustState
import com.tinkernorth.dish.ui.main.holdsPairing
import org.junit.Assert.assertEquals
import org.junit.Test

class MoonlightTrustPresentationTest {
    @Test
    fun `a host holding a pairing offers Re-pair, the same word as a stale satellite`() {
        assertEquals(R.string.action_repair_short, moonlightPairActionRes(MoonlightTrustState.PAIRED))
        assertEquals(R.string.action_repair_short, moonlightPairActionRes(MoonlightTrustState.REMEMBERED))
        assertEquals(R.string.action_repair_short, moonlightPairActionRes(MoonlightTrustState.CHECKING))
        assertEquals(R.string.action_repair_short, moonlightPairActionRes(MoonlightTrustState.UNREACHABLE))
    }

    @Test
    fun `a host with no usable pairing offers Pair now`() {
        assertEquals(R.string.ml_action_pair, moonlightPairActionRes(MoonlightTrustState.NOT_PAIRED))
        assertEquals(R.string.ml_action_pair, moonlightPairActionRes(MoonlightTrustState.TRUST_LOST))
        assertEquals(R.string.ml_action_pair, moonlightPairActionRes(MoonlightTrustState.REPLACED))
    }

    @Test
    fun `paired status renders in the success color, unpaired stays muted`() {
        for (state in MoonlightTrustState.entries) {
            val expected = if (state.holdsPairing()) R.color.colorSuccess else R.color.colorMuted
            assertEquals(state.name, expected, moonlightTrustColorRes(state))
        }
    }

    @Test
    fun `action and color always agree on whether a pairing is held`() {
        for (state in MoonlightTrustState.entries) {
            val repair = moonlightPairActionRes(state) == R.string.action_repair_short
            val success = moonlightTrustColorRes(state) == R.color.colorSuccess
            assertEquals(state.name, repair, success)
        }
    }
}
