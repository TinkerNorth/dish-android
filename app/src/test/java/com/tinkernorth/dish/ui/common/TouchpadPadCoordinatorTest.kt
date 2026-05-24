// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for [TouchpadPadCoordinator] — the small lock that
 * pins ownership of the touchpad wire to whichever of the two pads got
 * a finger down first, and releases it on lift.
 *
 * Pads are modelled here as plain string tokens; in the real overlay
 * they are [TouchpadSurfaceView] instances compared with `===`. Strings
 * compare with `===` too as long as they're the same interned literal.
 *
 * Headline invariants pinned here:
 *
 *  - **Idle is unowned** — both pads can write before any touch has
 *    landed. This is what makes the final all-zero "lift" emission from
 *    the previously-active pad still flow through to the receiver after
 *    the lock is released.
 *  - **First touch claims** — whichever pad calls onTouchStart first
 *    becomes the owner; the second is denied. No race window.
 *  - **Only the owner can release** — a stray onTouchEnd from the
 *    locked pad does NOT yank the lock away from the actual owner.
 *  - **Released lock returns to idle** — after the owner's lift, both
 *    pads are again eligible to claim ownership on the next touch.
 */
class TouchpadPadCoordinatorTest {
    private val padA = "click-pad"
    private val padB = "move-pad"

    @Test
    fun `initial state has no owner and both pads may write`() {
        // The initial-idle "both may write" property matters specifically
        // for the lift tick: the active pad's onTouchActivityChanged
        // (false) call fires BEFORE its final state-changed callback,
        // which means by the time the state-changed callback checks
        // mayWrite, the lock has already been released. We still want
        // that final (all-zero) emit to reach the receiver as the clean
        // lift signal — hence the unowned == both-write rule.
        val c = TouchpadPadCoordinator<String>()
        assertNull(c.active())
        assertTrue(c.mayWrite(padA))
        assertTrue(c.mayWrite(padB))
    }

    @Test
    fun `first touch claims ownership and locks the other pad out`() {
        val c = TouchpadPadCoordinator<String>()
        assertTrue("padA claims when idle", c.onTouchStart(padA))
        assertSame(padA, c.active())
        assertTrue("padA may write while it owns the lock", c.mayWrite(padA))
        assertFalse("padB locked out while padA owns", c.mayWrite(padB))
    }

    @Test
    fun `second pad's touchStart while another owns is denied`() {
        // Two pads landing ACTION_DOWN on the same input frame is
        // possible in theory; the Android input dispatcher serialises
        // them, so one will arrive first. The first wins; the second
        // gets locked. Pin this so a future refactor can't reorder the
        // lock check and accidentally let both pads write.
        val c = TouchpadPadCoordinator<String>()
        c.onTouchStart(padA)
        assertFalse("padB cannot claim while padA owns", c.onTouchStart(padB))
        assertSame("padA still owns", padA, c.active())
        assertFalse("padB still locked", c.mayWrite(padB))
    }

    @Test
    fun `re-entrant touchStart from current owner is a no-op`() {
        // The activity calls onTouchStart on every active=true
        // callback. If the user touches with two fingers on the same
        // pad (slot 0 then slot 1), only the first touch fires
        // active=true (the second leaves the pad already-active).
        // Defensive: pin the re-entrant case so a future refactor that
        // dispatches per-finger doesn't double-trigger the lock flip.
        val c = TouchpadPadCoordinator<String>()
        c.onTouchStart(padA)
        assertTrue("padA re-claims is true (it already owns)", c.onTouchStart(padA))
        assertSame(padA, c.active())
    }

    @Test
    fun `touchEnd from the owner releases the lock`() {
        val c = TouchpadPadCoordinator<String>()
        c.onTouchStart(padA)
        assertTrue("owner releases", c.onTouchEnd(padA))
        assertNull("no owner after release", c.active())
        assertTrue("padA may write again", c.mayWrite(padA))
        assertTrue("padB may write again", c.mayWrite(padB))
    }

    @Test
    fun `touchEnd from non-owner does not release the lock`() {
        // If padB somehow fires a lift callback while padA owns the
        // lock (e.g. padB had a queued event mid-yank), the coordinator
        // must NOT release padA's ownership. Test this explicitly so a
        // refactor that drops the owner-identity check would fail.
        val c = TouchpadPadCoordinator<String>()
        c.onTouchStart(padA)
        assertFalse("non-owner touchEnd is rejected", c.onTouchEnd(padB))
        assertSame("padA still owns", padA, c.active())
        assertFalse("padB still locked", c.mayWrite(padB))
    }

    @Test
    fun `released lock can be re-claimed by the other pad`() {
        // After the user lifts off padA, they should be able to touch
        // padB and have padB claim the wire cleanly. Pin the round-trip.
        val c = TouchpadPadCoordinator<String>()
        c.onTouchStart(padA)
        c.onTouchEnd(padA)
        assertTrue("padB claims after padA released", c.onTouchStart(padB))
        assertSame(padB, c.active())
        assertFalse("padA now locked", c.mayWrite(padA))
    }

    @Test
    fun `touchEnd before any touchStart is a harmless no-op`() {
        // Defensive: shouldn't crash or flip any state if the very
        // first event is somehow an end. Returning false signals "no
        // unlock work required."
        val c = TouchpadPadCoordinator<String>()
        assertFalse(c.onTouchEnd(padA))
        assertNull(c.active())
        assertTrue(c.mayWrite(padA))
        assertTrue(c.mayWrite(padB))
    }
}
