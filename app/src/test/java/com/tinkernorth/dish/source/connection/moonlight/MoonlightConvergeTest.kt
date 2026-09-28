// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import com.tinkernorth.dish.core.net.moonlight.NINTENDO
import com.tinkernorth.dish.core.net.moonlight.PLAYSTATION
import com.tinkernorth.dish.core.net.moonlight.XBOX
import org.junit.Assert.assertEquals
import org.junit.Test

// The one rule the reference count reduces to.
class MoonlightConvergeTest {
    @Test
    fun `the first pad on an idle host opens the stream`() {
        assertEquals(MoonlightConverge.OPEN, moonlightConverge(MoonlightSessionState.Idle, wantedPads = 1))
    }

    @Test
    fun `a dropped or host-ended session opens a new one rather than joining a dead one`() {
        assertEquals(MoonlightConverge.OPEN, moonlightConverge(MoonlightSessionState.Dropped, wantedPads = 1))
        assertEquals(MoonlightConverge.OPEN, moonlightConverge(MoonlightSessionState.Ended, wantedPads = 1))
    }

    @Test
    fun `later pads on a live host only announce themselves`() {
        (1..4).forEach { wanted ->
            assertEquals(MoonlightConverge.ANNOUNCE, moonlightConverge(MoonlightSessionState.Live, wanted))
        }
    }

    @Test
    fun `a launch already in flight is left alone rather than started twice`() {
        assertEquals(MoonlightConverge.WAIT, moonlightConverge(MoonlightSessionState.Launching, wantedPads = 1))
        assertEquals(MoonlightConverge.WAIT, moonlightConverge(MoonlightSessionState.Launching, wantedPads = 4))
    }

    @Test
    fun `losing the last pad on a live host closes the app it started`() {
        assertEquals(MoonlightConverge.CANCEL, moonlightConverge(MoonlightSessionState.Live, wantedPads = 0))
    }

    @Test
    fun `losing the last pad with no session up has nothing to close`() {
        listOf(
            MoonlightSessionState.Idle,
            MoonlightSessionState.Launching,
            MoonlightSessionState.Dropped,
            MoonlightSessionState.Ended,
        ).forEach { state ->
            assertEquals(state.name, MoonlightConverge.RELEASE, moonlightConverge(state, wantedPads = 0))
        }
    }

    private fun request(type: Int) = MoonlightPadRequest(slotId = "a", emulatedType = type, capabilities = CAPS, supportedButtons = BUTTONS)

    private fun held(type: Int) =
        MoonlightPad(slotId = "a", number = 0, emulatedType = type, capabilities = CAPS, supportedButtons = BUTTONS)

    @Test
    fun `a slot that holds no pad acquires one`() {
        assertEquals(PadPlacement.ACQUIRE, padPlacement(held = null, wanted = request(XBOX)))
    }

    @Test
    fun `a held pad asked for as the type it was announced as is kept`() {
        assertEquals(PadPlacement.KEEP, padPlacement(held(XBOX), request(XBOX)))
    }

    // The bits alone do not earn a replug: it unplugs the pad in the game, and the host builds
    // the same pad for the same type.
    @Test
    fun `a held pad asked for with other bits but the same type is kept`() {
        val otherBits = request(XBOX).copy(capabilities = OTHER_CAPS, supportedButtons = OTHER_BUTTONS)
        assertEquals(PadPlacement.KEEP, padPlacement(held(XBOX), otherBits))
    }

    @Test
    fun `a held pad asked for as another type is re-announced`() {
        assertEquals(PadPlacement.REANNOUNCE, padPlacement(held(XBOX), request(PLAYSTATION)))
        assertEquals(PadPlacement.REANNOUNCE, padPlacement(held(PLAYSTATION), request(NINTENDO)))
    }

    @Test
    fun `every session state is answered for every pad count`() {
        MoonlightSessionState.entries.forEach { state ->
            (0..4).forEach { wanted -> moonlightConverge(state, wanted) }
        }
    }

    private companion object {
        const val CAPS = 0x03
        const val BUTTONS = 0xFFFF
        const val OTHER_CAPS = 0x3F
        const val OTHER_BUTTONS = 0x10FFFF
    }
}
