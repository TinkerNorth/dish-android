// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import com.tinkernorth.dish.core.net.moonlight.MOTION_TYPE_GYRO
import com.tinkernorth.dish.core.net.moonlight.MoonlightControlSession
import com.tinkernorth.dish.core.net.moonlight.MoonlightEvent
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.core.net.moonlight.NINTENDO
import com.tinkernorth.dish.core.net.moonlight.PLAYSTATION
import com.tinkernorth.dish.core.net.moonlight.XBOX
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

// A held pad re-announced as another type: the host unplugs its number and plugs the new type in
// under the same one, so the user's re-pick takes effect without an unbind.
class MoonlightConnectionReplugTest {
    private val dispatcher = StandardTestDispatcher()
    private val session: MoonlightControlSession =
        mockk(relaxed = true) { every { state } returns MoonlightControlSession.State.CONNECTED }

    private fun connection() =
        MoonlightConnection(
            id = "moonlight:uid:abc",
            host = MoonlightHost(name = "PC", address = "10.0.0.5", uniqueId = "abc"),
            scope = TestScope(dispatcher),
            ioDispatcher = dispatcher,
        )

    private fun MoonlightConnection.take(slotId: String) = acquirePad(slotId, XBOX, XBOX_CAPS, XBOX_BUTTONS)

    private fun MoonlightConnection.repick(slotId: String) = reannouncePad(slotId, PLAYSTATION, PS_CAPS, PS_BUTTONS)

    @Test
    fun `a held pad re-announced live is unplugged and plugged in as the new type under its number`() {
        val conn = connection()
        conn.take("a")
        conn.take("b")
        conn.markLive(session, appId = null, appName = null)
        clearMocks(session, answers = false)

        val pad = conn.repick("b")

        assertEquals(MoonlightPad("b", 1, PLAYSTATION, PS_CAPS, PS_BUTTONS), pad)
        assertEquals(pad, conn.padFor("b"))
        verifyOrder {
            session.sendControllerReplug(1, 0b0001, PLAYSTATION, PS_CAPS, PS_BUTTONS)
            session.sendControllerState(1, 0b0011, 0, 0, 0, 0, 0, 0, 0)
        }
        verify(exactly = 0) { session.sendControllerArrival(any(), any(), any(), any()) }
    }

    @Test
    fun `a pad re-announced before the session is live only changes what markLive will announce`() {
        val conn = connection()
        conn.take("a")

        conn.repick("a")
        verify(exactly = 0) { session.sendControllerReplug(any(), any(), any(), any(), any()) }
        conn.markLive(session, appId = null, appName = null)

        verify(exactly = 1) { session.sendControllerArrival(0, PLAYSTATION, PS_CAPS, PS_BUTTONS) }
        verify(exactly = 0) { session.sendControllerReplug(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `re-announcing a slot that holds no pad sends nothing and takes no number`() {
        val conn = connection()
        conn.markLive(session, appId = null, appName = null)
        clearMocks(session, answers = false)

        assertNull(conn.repick("nobody"))

        assertEquals(0, conn.padCount)
        verify(exactly = 0) { session.sendControllerReplug(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { session.sendControllerState(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // The host's new pad has asked for nothing yet: motion it requested of the old one must not
    // stream to the new one until the new one asks.
    @Test
    fun `a re-announced pad forgets the motion the host asked of the pad it replaced`() {
        val conn = connection()
        conn.acquirePad("a", PLAYSTATION, PS_CAPS, PS_BUTTONS)
        conn.markLive(session, appId = null, appName = null)
        conn.dispatchFeedback(MoonlightEvent.MotionRequest(controllerNumber = 0, reportRateHz = 100, motionType = MOTION_TYPE_GYRO))

        conn.reannouncePad("a", NINTENDO, XBOX_CAPS, XBOX_BUTTONS)

        assertFalse(conn.motionWanted("a"))
    }

    private companion object {
        const val XBOX_CAPS = 0x03
        const val XBOX_BUTTONS = 0xFFFF
        const val PS_CAPS = 0x3F
        const val PS_BUTTONS = 0x10FFFF
    }
}
