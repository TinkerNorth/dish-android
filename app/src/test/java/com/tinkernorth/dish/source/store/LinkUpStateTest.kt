// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.composer.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkUpStateTest {
    @Test
    fun `a connected link is up`() {
        assertTrue(isUp(LinkState.Connected))
    }

    @Test
    fun `an unstable link is still up, because packets are still flowing`() {
        assertTrue(isUp(LinkState.Unstable))
    }

    @Test
    fun `every other state is down`() {
        val up = LinkState.entries.filter { isUp(it) }
        assertEquals(listOf(LinkState.Connected, LinkState.Unstable), up.sortedBy { it.ordinal })
        LinkState.entries.filterNot { it in up }.forEach { assertFalse(isUp(it)) }
    }
}
