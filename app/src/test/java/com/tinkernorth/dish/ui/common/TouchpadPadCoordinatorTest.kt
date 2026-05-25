// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadPadCoordinatorTest {
    private val padA = "click-pad"
    private val padB = "move-pad"

    @Test
    fun `initial state has no owner and both pads may write`() {
        // Final lift's onTouchActivityChanged(false) fires before its state-changed callback;
        // both-write at idle lets that final emit reach the receiver.
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
        val c = TouchpadPadCoordinator<String>()
        c.onTouchStart(padA)
        assertFalse("padB cannot claim while padA owns", c.onTouchStart(padB))
        assertSame("padA still owns", padA, c.active())
        assertFalse("padB still locked", c.mayWrite(padB))
    }

    @Test
    fun `re-entrant touchStart from current owner is a no-op`() {
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
        val c = TouchpadPadCoordinator<String>()
        c.onTouchStart(padA)
        assertFalse("non-owner touchEnd is rejected", c.onTouchEnd(padB))
        assertSame("padA still owns", padA, c.active())
        assertFalse("padB still locked", c.mayWrite(padB))
    }

    @Test
    fun `released lock can be re-claimed by the other pad`() {
        val c = TouchpadPadCoordinator<String>()
        c.onTouchStart(padA)
        c.onTouchEnd(padA)
        assertTrue("padB claims after padA released", c.onTouchStart(padB))
        assertSame(padB, c.active())
        assertFalse("padA now locked", c.mayWrite(padA))
    }

    @Test
    fun `touchEnd before any touchStart is a harmless no-op`() {
        val c = TouchpadPadCoordinator<String>()
        assertFalse(c.onTouchEnd(padA))
        assertNull(c.active())
        assertTrue(c.mayWrite(padA))
        assertTrue(c.mayWrite(padB))
    }
}
