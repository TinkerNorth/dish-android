// SPDX-License-Identifier: LGPL-3.0-or-later

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

    // ABXY is live-tracked (not additive): each pointer's current zone is recomputed every MOVE so sliding from B into A drops B and picks up A.
    private val abxyPointerBits = mutableMapOf<Int, Int>()
    private val abxyMask =
        GamepadTouchView.BTN_A or GamepadTouchView.BTN_B or
            GamepadTouchView.BTN_X or GamepadTouchView.BTN_Y

    fun onTouchEvent(
        event: MotionEvent,
        layout: GamepadLayout,
        onSampleApplied: (() -> Unit)? = null,
    ) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                handlePointerDown(event, event.actionIndex, layout)
                onSampleApplied?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                // Drain historical samples (oldest to newest) so vsync-coalesced intermediate finger positions each become a wire report.
                val historySize = event.historySize
                for (h in 0 until historySize) {
                    for (i in 0 until event.pointerCount) {
                        applyPointerMove(
                            event.getHistoricalX(i, h),
                            event.getHistoricalY(i, h),
                            event.getPointerId(i),
                            layout,
                        )
                    }
                    onSampleApplied?.invoke()
                }
                for (i in 0 until event.pointerCount) {
                    applyPointerMove(
                        event.getX(i),
                        event.getY(i),
                        event.getPointerId(i),
                        layout,
                    )
                }
                onSampleApplied?.invoke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                handlePointerUp(event, event.actionIndex)
                onSampleApplied?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> {
                reset()
                onSampleApplied?.invoke()
            }
        }
    }

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

        if (hypot(x - l.leftStickCx, y - l.leftStickCy) < l.stickRadius * pickup) {
            leftStickPointerId = pid
            return
        }
        if (hypot(x - l.rightStickCx, y - l.rightStickCy) < l.stickRadius * pickup) {
            rightStickPointerId = pid
            return
        }
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
    private fun applyPointerMove(
        x: Float,
        y: Float,
        pid: Int,
        l: GamepadLayout,
    ) {
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
        if (pid == l3StickPointerId) {
            val r = computeStickAxes((x - l.l3StickCx) / l.l3StickRadius, (y - l.l3StickCy) / l.l3StickRadius)
            l3StickDx = r.dx
            l3StickDy = r.dy
            state.leftX = r.axisX
            state.leftY = r.axisY
        }
        if (pid == r3StickPointerId) {
            val r = computeStickAxes((x - l.r3StickCx) / l.l3StickRadius, (y - l.r3StickCy) / l.l3StickRadius)
            r3StickDx = r.dx
            r3StickDy = r.dy
            state.rightX = r.axisX
            state.rightY = r.axisY
        }
        // Once a pointer claims the d-pad, the angle from its centre drives the hat for the rest of the gesture, even when dragged outside the rect.
        if (pid == dpadPointerId) {
            updateDpad(x, y, l)
        }
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
        // Button regions clear by pointer ID (never by position) so a drag-off before release doesn't leave the bit stuck.
        if (pid == dpadPointerId) {
            dpadPointerId = INVALID_POINTER
            state.hatSwitch = GamepadTouchView.HAT_NONE
            return
        }
        if (abxyPointerBits.containsKey(pid)) {
            abxyPointerBits.remove(pid)
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
        // Centre / stick-click buttons aren't pointer-tracked — drop all on any unmatched up.
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
