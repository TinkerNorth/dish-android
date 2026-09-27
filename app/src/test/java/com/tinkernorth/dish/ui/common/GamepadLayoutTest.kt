// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The geometry every hit-test and every draw call depends on: which row each control lands on
// per skin, what the centre cluster holds, and how the safe area moves it all.
class GamepadLayoutTest {
    private fun layoutFor(
        skin: GamepadSkin,
        trackpad: Boolean = false,
        height: Int = HEIGHT,
        insets: EdgeInsets = NO_INSETS,
    ): GamepadLayout = computeGamepadLayout(WIDTH, height, DENSITY, insets, skin, trackpad)

    @Test
    fun `the PlayStation skins put the d-pad on the top row and the left stick below`() {
        for (skin in listOf(GamepadSkin.PlayStation, GamepadSkin.DualSense)) {
            val l = layoutFor(skin)
            assertTrue("$skin", l.dpadRect.centerY < l.leftStickCy)
        }
    }

    @Test
    fun `the Xbox and Switch skins keep the left stick on the top row`() {
        for (skin in listOf(GamepadSkin.Xbox, GamepadSkin.Xbox360, GamepadSkin.Switch)) {
            val l = layoutFor(skin)
            assertTrue("$skin", l.leftStickCy < l.dpadRect.centerY)
        }
    }

    @Test
    fun `the right stick sits below the face buttons on every skin`() {
        for (skin in GamepadSkin.entries) {
            val l = layoutFor(skin)
            assertTrue("$skin", l.abxyRect.centerY < l.rightStickCy)
        }
    }

    @Test
    fun `only a PlayStation skin with a trackpad gets a trackpad rect`() {
        assertNotNull(layoutFor(GamepadSkin.PlayStation, trackpad = true).trackpadRect)
        assertNotNull(layoutFor(GamepadSkin.DualSense, trackpad = true).trackpadRect)
        assertNull(layoutFor(GamepadSkin.PlayStation, trackpad = false).trackpadRect)
        assertNull(layoutFor(GamepadSkin.Xbox, trackpad = true).trackpadRect)
    }

    @Test
    fun `the trackpad keeps the DualShock aspect at the top of the content band, centred`() {
        val l = layoutFor(GamepadSkin.PlayStation, trackpad = true)
        val tp = l.trackpadRect ?: error("no trackpad")
        assertEquals(TRACKPAD_ASPECT, tp.width / tp.height, EPSILON)
        assertEquals(l.ltRect.top, tp.top, EPSILON)
        assertEquals(l.homeCx, tp.centerX, EPSILON)
    }

    @Test
    fun `select and start flank the trackpad when it exists`() {
        val l = layoutFor(GamepadSkin.PlayStation, trackpad = true)
        val tp = l.trackpadRect ?: error("no trackpad")
        assertTrue(l.selectCx + l.smallBtnRadius < tp.left)
        assertTrue(tp.right < l.startCx - l.smallBtnRadius)
    }

    @Test
    fun `select and start straddle the centre when there is no trackpad`() {
        val l = layoutFor(GamepadSkin.Xbox)
        assertTrue(l.selectCx < l.homeCx)
        assertTrue(l.homeCx < l.startCx)
        assertEquals(l.homeCx - l.selectCx, l.startCx - l.homeCx, EPSILON)
    }

    @Test
    fun `the home button drops below the trackpad, else hangs under the centre pair`() {
        val withTrackpad = layoutFor(GamepadSkin.PlayStation, trackpad = true)
        val tp = withTrackpad.trackpadRect ?: error("no trackpad")
        assertTrue(tp.bottom < withTrackpad.homeCy - withTrackpad.smallBtnRadius)

        val without = layoutFor(GamepadSkin.Xbox)
        assertTrue(without.centerBtnCy < without.homeCy)
    }

    @Test
    fun `only the DualSense carries a mic-mute rect`() {
        assertNotNull(layoutFor(GamepadSkin.DualSense, trackpad = true).micMuteRect)
        assertNull(layoutFor(GamepadSkin.PlayStation, trackpad = true).micMuteRect)
        assertNull(layoutFor(GamepadSkin.Xbox).micMuteRect)
    }

    @Test
    fun `the mic-mute rect hangs under the home button when the screen has room`() {
        val l = layoutFor(GamepadSkin.DualSense, trackpad = true, height = TALL_HEIGHT)
        val rect = l.micMuteRect ?: error("no mic-mute rect")
        assertEquals(l.homeCy + l.smallBtnRadius + MIC_MUTE_GAP_DP * DENSITY, rect.top, EPSILON)
        assertEquals(l.homeCx, rect.centerX, EPSILON)
    }

    @Test
    fun `the mic-mute rect is clamped inside the content band on a short screen`() {
        val l = layoutFor(GamepadSkin.DualSense, trackpad = true, height = SHORT_HEIGHT)
        val rect = l.micMuteRect ?: error("no mic-mute rect")
        assertEquals(l.ltRect.bottom, rect.bottom, EPSILON)
        assertTrue(rect.top < l.homeCy + l.smallBtnRadius + MIC_MUTE_GAP_DP * DENSITY)
    }

    @Test
    fun `the safe insets push every edge control inward`() {
        val insets = EdgeInsets(left = 40, top = 30, right = 50, bottom = 20)
        val l = layoutFor(GamepadSkin.Xbox, insets = insets)
        val cushion = SAFE_AREA_CUSHION_DP * DENSITY
        assertEquals(insets.top + cushion, l.lbRect.top, EPSILON)
        assertEquals(insets.left + cushion, l.ltRect.left, EPSILON)
        assertEquals(WIDTH - insets.right - cushion, l.rtRect.right, EPSILON)
        assertEquals(HEIGHT - insets.bottom - cushion, l.ltRect.bottom, EPSILON)
    }

    @Test
    fun `the shoulders sit above the content band and clear of the centre`() {
        val l = layoutFor(GamepadSkin.Xbox)
        assertTrue(l.lbRect.bottom < l.ltRect.top)
        assertTrue(l.lbRect.right < l.homeCx)
        assertTrue(l.homeCx < l.rbRect.left)
        assertEquals(l.homeCx - l.lbRect.right, l.rbRect.left - l.homeCx, EPSILON)
    }

    @Test
    fun `the stick clicks sit beside their sticks, L3 outward and R3 inward`() {
        val l = layoutFor(GamepadSkin.Xbox)
        val reach = l.stickRadius + l.l3StickRadius + L3_STICK_GAP_DP * DENSITY
        assertEquals(l.leftStickCx + reach, l.l3StickCx, EPSILON)
        assertEquals(l.rightStickCx - reach, l.r3StickCx, EPSILON)
        assertEquals(l.leftStickCy, l.l3StickCy, EPSILON)
        assertEquals(l.rightStickCy, l.r3StickCy, EPSILON)
        assertEquals(l.stickRadius * L3_STICK_RADIUS_FRACTION, l.l3StickRadius, EPSILON)
    }

    private companion object {
        const val WIDTH = 2000
        const val HEIGHT = 1000
        const val TALL_HEIGHT = 1600
        const val SHORT_HEIGHT = 350
        const val DENSITY = 2f
        const val EPSILON = 1e-3f
    }
}
