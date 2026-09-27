// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerRouteTest {
    private fun summary(
        kind: ConnectionKind,
        live: LinkState = LinkState.Connected,
    ) = ConnectionSummary(id = "host", kind = kind, label = "PC", detail = "", live = live, boundSlotIds = emptyList())

    @Test
    fun `a dead link routes nowhere`() {
        assertEquals(PointerRoute.NONE, pointerRouteFor(summary(ConnectionKind.SATELLITE, LinkState.Connecting)))
        assertEquals(PointerRoute.NONE, pointerRouteFor(summary(ConnectionKind.MOONLIGHT, LinkState.Saved)))
        assertEquals(PointerRoute.NONE, pointerRouteFor(null))
    }

    @Test
    fun `a live satellite takes the wire frame and a live moonlight host the control packets`() {
        assertEquals(PointerRoute.SATELLITE, pointerRouteFor(summary(ConnectionKind.SATELLITE)))
        assertEquals(PointerRoute.SATELLITE, pointerRouteFor(summary(ConnectionKind.SATELLITE, LinkState.Unstable)))
        assertEquals(PointerRoute.MOONLIGHT, pointerRouteFor(summary(ConnectionKind.MOONLIGHT)))
    }

    @Test
    fun `a bluetooth host carries no pointer`() {
        assertEquals(PointerRoute.NONE, pointerRouteFor(summary(ConnectionKind.BLUETOOTH)))
    }

    @Test
    fun `only satellite frames are resent`() {
        assertTrue(pointerResendAllowed(summary(ConnectionKind.SATELLITE)))
        assertFalse(pointerResendAllowed(summary(ConnectionKind.SATELLITE, LinkState.Connecting)))
    }

    @Test
    fun `moonlight pointer frames are never resent`() {
        assertFalse(pointerResendAllowed(summary(ConnectionKind.MOONLIGHT)))
    }
}
