// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.LinkState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A faltering (Unstable) link is still streaming, so it must count as "live" everywhere on the
// dashboard (slot status, status dot, online count) the same way the connections screen treats it.
class IsLiveLinkTest {
    @Test
    fun `connected and unstable are live`() {
        assertTrue(LinkState.Connected.isLiveLink())
        assertTrue(LinkState.Unstable.isLiveLink())
    }

    @Test
    fun `every other state is not live`() {
        for (s in listOf(LinkState.Connecting, LinkState.Ready, LinkState.Found, LinkState.Saved, LinkState.Stale)) {
            assertFalse("$s should not be live", s.isLiveLink())
        }
    }
}
