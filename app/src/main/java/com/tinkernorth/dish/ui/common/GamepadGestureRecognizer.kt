// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import android.view.MotionEvent
import com.tinkernorth.dish.ui.common.GamepadConstants.ABXY_BTN_SPACING_FACTOR
import com.tinkernorth.dish.ui.common.GamepadConstants.ABXY_CENTER_ZONE_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.CENTER_BTN_PICKUP_FACTOR
import com.tinkernorth.dish.ui.common.GamepadConstants.DPAD_DIAGONAL_THRESHOLD
import com.tinkernorth.dish.ui.common.GamepadConstants.HOME_VERTICAL_OFFSET_FACTOR
import com.tinkernorth.dish.ui.common.GamepadConstants.PICKUP_RADIUS_FACTOR
import com.tinkernorth.dish.ui.common.GamepadConstants.TRIGGER_MAX
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Touch handling for [GamepadTouchView]. Owns the per-pointer-id tracking and
 * the [GamepadTouchView.GamepadState] mutation, but knows nothing about
 * drawing — it works against a [GamepadLayout] snapshot supplied by the
 * caller on each event.
 *
 * Pointer-id tracking is essential: a finger that lands on a button and then
 * drags off must still release on UP, otherwise the button bit stays held.
 * For sticks the displacement is also exposed via [leftStickDx]/[leftStickDy]
 * etc. so the View can render the thumb cap at the finger's position.
 *
 * Threading: assumes single-threaded use from the View's touch dispatch path.
 */
internal class GamepadGestureRecognizer {
    var state: GamepadTouchView.GamepadState = GamepadTouchView.GamepadState()
        private set

    var leftStickDx = 0f
        private set
    var leftStickDy = 0f
        private set
    var rightStickDx = 0f
        private set
    var rightStickDy = 0f
        private set
    var l3StickDx = 0f
        private set
    var l3StickDy = 0f
        private set
    var r3StickDx = 0f
        private set
    var r3StickDy = 0f
        private set

    private var leftStickPointerId = INVALID_POINTER
    private var rightStickPointerId = INVALID_POINTER
    private var l3StickPointerId = INVALID_POINTER
    private var r3StickPointerId = INVALID_POINTER
    private var ltPointerId = INVALID_POINTER
    private var rtPointerId = INVALID_POINTER
    private var dpadPointerId = INVALID_POINTER
    private var lbPointerId = INVALID_POINTER
    private var rbPointerId = INVALID_POINTER

    // ABXY is live-tracked, not additive: each pointer's current zone is
    // recomputed on every MOVE so sliding from B into A drops B and picks up
    // A (mirrors the d-pad's "hat follows the finger" contract). For multi-
    // touch the state bits are the OR of every pointer's current zone.
    private val abxyPointerBits = mutableMapOf<Int, Int>()
    private val abxyMask =
        GamepadTouchView.BTN_A or GamepadTouchView.BTN_B or
            GamepadTouchView.BTN_X or GamepadTouchView.BTN_Y

    /**
     * Process a [MotionEvent] against the supplied [layout]. Mutates [state]
     * and the per-stick displacement fields in place. The caller is
     * responsible for invalidating the View and notifying its listener.
     */
    fun onTouchEvent(
        event: MotionEvent,
        layout: GamepadLayout,
    ) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                handlePointerDown(event, event.actionIndex, layout)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    handlePointerMove(event, i, layout)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                handlePointerUp(event, event.actionIndex)
            }
            MotionEvent.ACTION_CANCEL -> reset()
        }
    }

    /** Drop all touch state and zero the gamepad state. */
    fun reset() {
        state = GamepadTouchView.GamepadState()
        leftStickDx = 0f
        leftStickDy = 0f
        rightStickDx = 0f
        rightStickDy = 0f
        l3StickDx = 0f
        l3StickDy = 0f
        r3StickDx = 0f
        r3StickDy = 0f
        leftStickPointerId = INVALID_POINTER
        rightStickPointerId = INVALID_POINTER
        l3StickPointerId = INVALID_POINTER
        r3StickPointerId = INVALID_POINTER
        ltPointerId = INVALID_POINTER
        rtPointerId = INVALID_POINTER
        dpadPointerId = INVALID_POINTER
        lbPointerId = INVALID_POINTER
        rbPointerId = INVALID_POINTER
        abxyPointerBits.clear()
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod")
    private fun handlePointerDown(
        event: MotionEvent,
        idx: Int,
        l: GamepadLayout,
    ) {
        val x = event.getX(idx)
        val y = event.getY(idx)
        val pid = event.getPointerId(idx)
        val pickup = PICKUP_RADIUS_FACTOR
        val centerPickup = CENTER_BTN_PICKUP_FACTOR

        // Sticks
        if (hypot(x - l.leftStickCx, y - l.leftStickCy) < l.stickRadius * pickup) {
            leftStickPointerId = pid
            return
        }
        if (hypot(x - l.rightStickCx, y - l.rightStickCy) < l.stickRadius * pickup) {
            rightStickPointerId = pid
            return
        }
        // L3/R3 sticks (touch = button pressed + axis control)
        if (hypot(x - l.l3StickCx, y - l.l3StickCy) < l.l3StickRadius * pickup) {
            l3StickPointerId = pid
            state.buttons = state.buttons or GamepadTouchView.BTN_LS
            return
        }
        if (hypot(x - l.r3StickCx, y - l.r3StickCy) < l.l3StickRadius * pickup) {
            r3StickPointerId = pid
            state.buttons = state.buttons or GamepadTouchView.BTN_RS
            return
        }
        // Triggers — binary press, tracked by pointer id so drag-off still
        // releases cleanly on UP.
        if (l.ltRect.contains(x, y)) {
            ltPointerId = pid
            state.leftTrigger = TRIGGER_MAX
            return
        }
        if (l.rtRect.contains(x, y)) {
            rtPointerId = pid
            state.rightTrigger = TRIGGER_MAX
            return
        }
        // Shoulders — pointer-id tracked so release works regardless of
        // where the finger ends up on UP.
        if (l.lbRect.contains(x, y)) {
            lbPointerId = pid
            state.buttons = state.buttons or GamepadTouchView.BTN_LB
            return
        }
        if (l.rbRect.contains(x, y)) {
            rbPointerId = pid
            state.buttons = state.buttons or GamepadTouchView.BTN_RB
            return
        }
        if (l.dpadRect.contains(x, y)) {
            dpadPointerId = pid
            updateDpad(x, y, l)
            return
        }
        if (l.abxyRect.contains(x, y)) {
            abxyPointerBits[pid] = computeAbxyZone(x, y, l)
            refreshAbxyButtons()
            return
        }
        // Centre buttons — not pointer-tracked; cleared in handlePointerUp's
        // fallthrough on any unmatched UP.
        if (hypot(x - l.selectCx, y - l.centerBtnCy) < l.smallBtnRadius * centerPickup) {
            state.buttons = state.buttons or GamepadTouchView.BTN_SELECT
            return
        }
        if (hypot(x - l.startCx, y - l.centerBtnCy) < l.smallBtnRadius * centerPickup) {
            state.buttons = state.buttons or GamepadTouchView.BTN_START
            return
        }
        val homeCy = l.centerBtnCy + l.smallBtnRadius * HOME_VERTICAL_OFFSET_FACTOR
        if (hypot(x - l.homeCx, y - homeCy) < l.smallBtnRadius * centerPickup) {
            state.buttons = state.buttons or GamepadTouchView.BTN_HOME
            return
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun handlePointerMove(
        event: MotionEvent,
        idx: Int,
        l: GamepadLayout,
    ) {
        val x = event.getX(idx)
        val y = event.getY(idx)
        val pid = event.getPointerId(idx)

        if (pid == leftStickPointerId) {
            val r = computeStickAxes((x - l.leftStickCx) / l.stickRadius, (y - l.leftStickCy) / l.stickRadius)
            leftStickDx = r.dx
            leftStickDy = r.dy
            state.leftX = r.axisX
            state.leftY = r.axisY
        }
        if (pid == rightStickPointerId) {
            val r = computeStickAxes((x - l.rightStickCx) / l.stickRadius, (y - l.rightStickCy) / l.stickRadius)
            rightStickDx = r.dx
            rightStickDy = r.dy
            state.rightX = r.axisX
            state.rightY = r.axisY
        }
        // L3 stick — same axes as L stick, plus L3 button held
        if (pid == l3StickPointerId) {
            val r = computeStickAxes((x - l.l3StickCx) / l.l3StickRadius, (y - l.l3StickCy) / l.l3StickRadius)
            l3StickDx = r.dx
            l3StickDy = r.dy
            state.leftX = r.axisX
            state.leftY = r.axisY
        }
        // R3 stick — same axes as R stick, plus R3 button held
        if (pid == r3StickPointerId) {
            val r = computeStickAxes((x - l.r3StickCx) / l.l3StickRadius, (y - l.r3StickCy) / l.l3StickRadius)
            r3StickDx = r.dx
            r3StickDy = r.dy
            state.rightX = r.axisX
            state.rightY = r.axisY
        }
        // Triggers are binary — no move-modulation. The DOWN/UP handlers
        // set the value; MOVE is a no-op for trigger pointers.

        // Live D-Pad tracking — once a pointer claims the d-pad, the angle
        // from the d-pad center drives the hat for the rest of the gesture,
        // even when the finger has dragged outside the d-pad's visible rect.
        // Mirrors the analog sticks: drag past the well and direction stays
        // registered (and re-resolves to whichever octant the finger now
        // sits in). Cleared on UP/CANCEL.
        if (pid == dpadPointerId) {
            updateDpad(x, y, l)
        }
        // ABXY MOVE is live: the held bits track the finger's current zone,
        // so sliding from one button into another replaces (rather than
        // accumulates) — same contract as the d-pad's hat-switch follow.
        // Zones extend infinitely past the visible cluster, so a drag in
        // the same direction past the cluster boundary keeps the same bits.
        val currentBits = abxyPointerBits[pid]
        if (currentBits != null) {
            val newBits = computeAbxyZone(x, y, l)
            if (newBits != currentBits) {
                abxyPointerBits[pid] = newBits
                refreshAbxyButtons()
            }
        }
    }

    @Suppress("ReturnCount", "LongMethod")
    private fun handlePointerUp(
        event: MotionEvent,
        idx: Int,
    ) {
        val pid = event.getPointerId(idx)

        if (pid == leftStickPointerId) {
            leftStickPointerId = INVALID_POINTER
            leftStickDx = 0f
            leftStickDy = 0f
            state.leftX = 0
            state.leftY = 0
            return
        }
        if (pid == rightStickPointerId) {
            rightStickPointerId = INVALID_POINTER
            rightStickDx = 0f
            rightStickDy = 0f
            state.rightX = 0
            state.rightY = 0
            return
        }
        if (pid == l3StickPointerId) {
            l3StickPointerId = INVALID_POINTER
            l3StickDx = 0f
            l3StickDy = 0f
            state.leftX = 0
            state.leftY = 0
            state.buttons = state.buttons and GamepadTouchView.BTN_LS.inv()
            return
        }
        if (pid == r3StickPointerId) {
            r3StickPointerId = INVALID_POINTER
            r3StickDx = 0f
            r3StickDy = 0f
            state.rightX = 0
            state.rightY = 0
            state.buttons = state.buttons and GamepadTouchView.BTN_RS.inv()
            return
        }
        if (pid == ltPointerId) {
            ltPointerId = INVALID_POINTER
            state.leftTrigger = 0
            return
        }
        if (pid == rtPointerId) {
            rtPointerId = INVALID_POINTER
            state.rightTrigger = 0
            return
        }
        // All button-region pointers clear by pointer ID, never by position —
        // dragging the finger off the rect before release would otherwise
        // leave the bit stuck.
        if (pid == dpadPointerId) {
            dpadPointerId = INVALID_POINTER
            state.hatSwitch = GamepadTouchView.HAT_NONE
            return
        }
        if (abxyPointerBits.containsKey(pid)) {
            abxyPointerBits.remove(pid)
            // Other pointers may still be holding ABXY bits — re-OR what
            // remains so the release only clears bits no one else is on.
            refreshAbxyButtons()
            return
        }
        if (pid == lbPointerId) {
            lbPointerId = INVALID_POINTER
            state.buttons = state.buttons and GamepadTouchView.BTN_LB.inv()
            return
        }
        if (pid == rbPointerId) {
            rbPointerId = INVALID_POINTER
            state.buttons = state.buttons and GamepadTouchView.BTN_RB.inv()
            return
        }
        // Centre / stick-click buttons aren't tracked by id — drop all of
        // them on any unmatched up.
        state.buttons =
            state.buttons and
            (
                GamepadTouchView.BTN_SELECT or GamepadTouchView.BTN_START or
                    GamepadTouchView.BTN_HOME or GamepadTouchView.BTN_LS or
                    GamepadTouchView.BTN_RS
            ).inv()
    }

    private fun updateDpad(
        x: Float,
        y: Float,
        l: GamepadLayout,
    ) {
        val dx = x - l.dpadRect.centerX()
        val dy = y - l.dpadRect.centerY()
        if (dx == 0f && dy == 0f) {
            state.hatSwitch = GamepadTouchView.HAT_NONE
            return
        }
        val absDx = abs(dx)
        val absDy = abs(dy)
        val major = max(absDx, absDy)
        val minor = min(absDx, absDy)
        // Diagonal when the minor axis is within DPAD_DIAGONAL_THRESHOLD of
        // the major. The two-axis HAT encoding can't represent left+right or
        // up+down simultaneously, so picking a diagonal is a one-way choice
        // — east-vs-west and north-vs-south fall out of the sign of dx/dy.
        val isDiagonal = (minor / major) >= DPAD_DIAGONAL_THRESHOLD
        val east = dx > 0
        val south = dy > 0
        state.hatSwitch =
            when {
                isDiagonal && east && south -> GamepadTouchView.HAT_SE
                isDiagonal && east && !south -> GamepadTouchView.HAT_NE
                isDiagonal && !east && south -> GamepadTouchView.HAT_SW
                isDiagonal && !east && !south -> GamepadTouchView.HAT_NW
                absDx >= absDy -> if (east) GamepadTouchView.HAT_E else GamepadTouchView.HAT_W
                else -> if (south) GamepadTouchView.HAT_S else GamepadTouchView.HAT_N
            }
    }

    /**
     * Resolve a touch position to the set of ABXY bits its zone represents.
     *
     * The cluster is divided into nine zones around its centre:
     *  - a centre disc (radius = [ABXY_CENTER_ZONE_FRACTION] of the centre →
     *    button-centre distance) → all four buttons,
     *  - four cardinal sectors → a single button (N=Y, S=A, E=B, W=X),
     *  - four diagonal sectors → the two adjacent buttons (NE=Y+B, SE=A+B,
     *    SW=A+X, NW=Y+X).
     *
     * Cardinal-vs-diagonal selection uses the same `min(|dx|,|dy|) /
     * max(|dx|,|dy|) ≥ DPAD_DIAGONAL_THRESHOLD` test as the d-pad. Zones
     * extend infinitely past the visible cluster — dragging far in one
     * direction keeps the same bits held until the finger crosses into
     * another zone.
     */
    private fun computeAbxyZone(
        x: Float,
        y: Float,
        l: GamepadLayout,
    ): Int {
        val dx = x - l.abxyRect.centerX()
        val dy = y - l.abxyRect.centerY()
        val sp = l.btnRadius * ABXY_BTN_SPACING_FACTOR
        val centerR = sp * ABXY_CENTER_ZONE_FRACTION
        if ((dx * dx + dy * dy) < centerR * centerR) {
            return abxyMask
        }
        val absDx = abs(dx)
        val absDy = abs(dy)
        val major = max(absDx, absDy)
        val minor = min(absDx, absDy)
        val isDiagonal = (minor / major) >= DPAD_DIAGONAL_THRESHOLD
        val east = dx > 0
        val south = dy > 0
        return when {
            isDiagonal && east && south -> GamepadTouchView.BTN_A or GamepadTouchView.BTN_B
            isDiagonal && east && !south -> GamepadTouchView.BTN_Y or GamepadTouchView.BTN_B
            isDiagonal && !east && south -> GamepadTouchView.BTN_A or GamepadTouchView.BTN_X
            isDiagonal && !east && !south -> GamepadTouchView.BTN_Y or GamepadTouchView.BTN_X
            absDx >= absDy -> if (east) GamepadTouchView.BTN_B else GamepadTouchView.BTN_X
            else -> if (south) GamepadTouchView.BTN_A else GamepadTouchView.BTN_Y
        }
    }

    private fun refreshAbxyButtons() {
        var bits = 0
        for (zoneBits in abxyPointerBits.values) bits = bits or zoneBits
        state.buttons = (state.buttons and abxyMask.inv()) or bits
    }

    companion object {
        private const val INVALID_POINTER = -1
    }
}
