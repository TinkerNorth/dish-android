package com.tinkernorth.dish.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.tinkernorth.dish.R
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Custom View rendering an on-screen gamepad with touch input.
 *
 * Layout (landscape):
 *   Left side:  D-Pad (top) + Left Stick (bottom)
 *   Center:     Select / Start buttons
 *   Right side: ABXY buttons (top) + Right Stick (bottom)
 *   Shoulders:  LB/RB at top edges, LT/RT as vertical strips on far edges
 *
 * All rendering is code-drawn (no bitmaps). Reports state via [listener].
 */
@Suppress("TooManyFunctions")
class GamepadTouchView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        // ── Listener ─────────────────────────────────────────────────────────────

        interface Listener {
            fun onGamepadStateChanged(state: GamepadState)
        }

        var listener: Listener? = null

        // ── Gamepad State ────────────────────────────────────────────────────────

        data class GamepadState(
            var buttons: Int = 0,
            var hatSwitch: Int = HAT_NONE,
            var leftX: Short = 0,
            var leftY: Short = 0,
            var rightX: Short = 0,
            var rightY: Short = 0,
            var leftTrigger: Int = 0,
            var rightTrigger: Int = 0,
        )

        private var state = GamepadState()

        // ── Button bit constants ─────────────────────────────────────────────────
        companion object {
            const val BTN_A = 1 shl 0
            const val BTN_B = 1 shl 1
            const val BTN_X = 1 shl 2
            const val BTN_Y = 1 shl 3
            const val BTN_LB = 1 shl 4
            const val BTN_RB = 1 shl 5
            const val BTN_SELECT = 1 shl 6
            const val BTN_START = 1 shl 7
            const val BTN_LS = 1 shl 8
            const val BTN_RS = 1 shl 9
            const val BTN_HOME = 1 shl 10

            const val HAT_NONE = 0
            const val HAT_N = 1
            const val HAT_NE = 2
            const val HAT_E = 3
            const val HAT_SE = 4
            const val HAT_S = 5
            const val HAT_SW = 6
            const val HAT_W = 7
            const val HAT_NW = 8

            private const val AXIS_MAX = 32767f
        }

        // ── Controller profile ──────────────────────────────────────────────────

        var usePlayStation = false
            set(value) {
                field = value
                loadDrawables()
                invalidate()
            }

        // ── Paints ───────────────────────────────────────────────────────────────

        private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1F25.toInt() }
        private val paintStickBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E293B.toInt() }
        private val paintPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x403B82F6.toInt() }

        // ── Drawables ────────────────────────────────────────────────────────────

        private var icBtnA: Drawable? = null
        private var icBtnB: Drawable? = null
        private var icBtnX: Drawable? = null
        private var icBtnY: Drawable? = null
        private var icDpad: Drawable? = null
        private var icDpadUp: Drawable? = null
        private var icDpadDown: Drawable? = null
        private var icDpadLeft: Drawable? = null
        private var icDpadRight: Drawable? = null
        private var icLB: Drawable? = null
        private var icRB: Drawable? = null
        private var icLT: Drawable? = null
        private var icRT: Drawable? = null
        private var icStickL: Drawable? = null
        private var icStickR: Drawable? = null
        private var icSelect: Drawable? = null
        private var icStart: Drawable? = null
        private var icHome: Drawable? = null

        init {
            loadDrawables()
        }

        @Suppress("CyclomaticComplexMethod")
        private fun loadDrawables() {
            val c = context
            if (usePlayStation) {
                icBtnA = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_cross)
                icBtnB = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_circle)
                icBtnX = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_square)
                icBtnY = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_triangle)
                icDpad = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_dpad)
                icDpadUp = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_dpad_up)
                icDpadDown = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_dpad_down)
                icDpadLeft = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_dpad_left)
                icDpadRight = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_dpad_right)
                icLB = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_l1)
                icRB = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_r1)
                icLT = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_l2)
                icRT = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_r2)
                icStickL = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_stick_l)
                icStickR = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_stick_r)
                icSelect = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_analog)
                icStart = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_analog)
                icHome = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_analog)
            } else {
                icBtnA = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_a)
                icBtnB = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_b)
                icBtnX = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_x)
                icBtnY = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_y)
                icDpad = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_dpad)
                icDpadUp = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_dpad_up)
                icDpadDown = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_dpad_down)
                icDpadLeft = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_dpad_left)
                icDpadRight = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_dpad_right)
                icLB = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_lb)
                icRB = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_rb)
                icLT = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_lt)
                icRT = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_rt)
                icStickL = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_stick_l)
                icStickR = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_stick_r)
                icSelect = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_view)
                icStart = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_menu)
                icHome = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_guide)
            }
        }

        // ── Layout regions (computed in onSizeChanged) ───────────────────────────

        private var dp = 1f
        private val dpadRect = RectF()
        private val abxyRect = RectF()
        private var leftStickCx = 0f
        private var leftStickCy = 0f
        private var stickRadius = 0f
        private var rightStickCx = 0f
        private var rightStickCy = 0f
        private var l3StickCx = 0f
        private var l3StickCy = 0f
        private var r3StickCx = 0f
        private var r3StickCy = 0f
        private var l3StickRadius = 0f
        private var selectCx = 0f
        private var startCx = 0f
        private var centerBtnCy = 0f
        private var homeCx = 0f
        private var btnRadius = 0f
        private var smallBtnRadius = 0f
        private val lbRect = RectF()
        private val rbRect = RectF()
        private val ltRect = RectF()
        private val rtRect = RectF()

        // ── Touch tracking ───────────────────────────────────────────────────────

        private var leftStickPointerId = -1
        private var rightStickPointerId = -1
        private var leftStickDx = 0f
        private var leftStickDy = 0f
        private var rightStickDx = 0f
        private var rightStickDy = 0f
        private var l3StickPointerId = -1
        private var r3StickPointerId = -1
        private var l3StickDx = 0f
        private var l3StickDy = 0f
        private var r3StickDx = 0f
        private var r3StickDy = 0f
        private var ltPointerId = -1
        private var rtPointerId = -1
        private var ltStartY = 0f
        private var rtStartY = 0f
        private var dpadPointerId = -1
        private var abxyPointerId = -1

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldW: Int,
            oldH: Int,
        ) {
            super.onSizeChanged(w, h, oldW, oldH)
            dp = resources.displayMetrics.density
            val pad = 24 * dp
            val qw = w / 4f

            // Xbox: stick top-left, d-pad bottom-left
            // PlayStation: d-pad top-left, stick bottom-left
            val topLeftCy = h * 0.32f
            val bottomLeftCy = h * 0.75f
            val dpadSize = min(qw - pad * 2, h * 0.4f)
            val dpadCx = qw * 0.5f
            val dpadCy = if (usePlayStation) topLeftCy else bottomLeftCy
            dpadRect.set(dpadCx - dpadSize / 2, dpadCy - dpadSize / 2, dpadCx + dpadSize / 2, dpadCy + dpadSize / 2)

            stickRadius = min(qw * 0.28f, h * 0.18f)
            l3StickRadius = stickRadius * 0.7f
            leftStickCx = qw * 0.4f
            leftStickCy = if (usePlayStation) bottomLeftCy else topLeftCy

            // L3 stick: to the right of L stick, same vertical
            l3StickCx = leftStickCx + stickRadius + l3StickRadius + 12 * dp
            l3StickCy = leftStickCy

            // ABXY: top-right quadrant
            val abxyCx = w - qw * 0.4f
            val abxyCy = h * 0.32f
            val abxySize = dpadSize
            abxyRect.set(abxyCx - abxySize / 2, abxyCy - abxySize / 2, abxyCx + abxySize / 2, abxyCy + abxySize / 2)
            btnRadius = abxySize * 0.18f

            // Right stick: bottom-right
            rightStickCx = w - qw * 0.4f
            rightStickCy = h * 0.75f

            // R3 stick: to the left of R stick, same vertical
            r3StickCx = rightStickCx - stickRadius - l3StickRadius - 12 * dp
            r3StickCy = rightStickCy

            // Center buttons
            centerBtnCy = h * 0.25f
            selectCx = w / 2f - 40 * dp
            startCx = w / 2f + 40 * dp
            homeCx = w / 2f
            smallBtnRadius = 14 * dp

            // Shoulder buttons (LB/RB)
            val sbH = 28 * dp
            lbRect.set(qw * 0.15f, 0f, qw * 0.85f, sbH)
            rbRect.set(w - qw * 0.85f, 0f, w - qw * 0.15f, sbH)

            // Trigger strips (LT/RT)
            val tW = 28 * dp
            ltRect.set(0f, sbH, tW, h.toFloat())
            rtRect.set(w - tW, sbH, w.toFloat(), h.toFloat())
        }

        // ── Drawing ──────────────────────────────────────────────────────────────

        private fun drawDrawable(
            c: Canvas,
            d: Drawable?,
            cx: Float,
            cy: Float,
            size: Float,
        ) {
            d ?: return
            val half = (size / 2).toInt()
            d.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
            d.draw(c)
        }

        private fun drawDrawablePressed(
            c: Canvas,
            d: Drawable?,
            cx: Float,
            cy: Float,
            size: Float,
        ) {
            d ?: return
            val half = (size / 2).toInt()
            c.drawCircle(cx, cy, size / 2, paintPressed)
            d.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
            d.draw(c)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)

            drawDpad(canvas)
            drawAbxy(canvas)
            drawStick(canvas, leftStickCx, leftStickCy, leftStickDx, leftStickDy, stickRadius, icStickL)
            drawStick(canvas, rightStickCx, rightStickCy, rightStickDx, rightStickDy, stickRadius, icStickR)
            drawStick(canvas, l3StickCx, l3StickCy, l3StickDx, l3StickDy, l3StickRadius, icStickL)
            drawStick(canvas, r3StickCx, r3StickCy, r3StickDx, r3StickDy, l3StickRadius, icStickR)
            drawCenterButtons(canvas)
            drawShoulders(canvas)
            drawTriggers(canvas)
        }

        private fun drawDpad(c: Canvas) {
            val cx = dpadRect.centerX()
            val cy = dpadRect.centerY()
            val size = dpadRect.width()
            val hat = state.hatSwitch
            val active =
                when (hat) {
                    HAT_N, HAT_NE, HAT_NW -> icDpadUp
                    HAT_S, HAT_SE, HAT_SW -> icDpadDown
                    HAT_W -> icDpadLeft
                    HAT_E -> icDpadRight
                    else -> null
                }
            drawDrawable(c, icDpad, cx, cy, size)
            if (active != null) drawDrawable(c, active, cx, cy, size)
        }

        private fun drawAbxy(c: Canvas) {
            val cx = abxyRect.centerX()
            val cy = abxyRect.centerY()
            val sp = btnRadius * 1.5f
            val sz = btnRadius * 2.2f
            drawIcon(c, icBtnY, cx, cy - sp, sz, state.buttons and BTN_Y != 0)
            drawIcon(c, icBtnA, cx, cy + sp, sz, state.buttons and BTN_A != 0)
            drawIcon(c, icBtnX, cx - sp, cy, sz, state.buttons and BTN_X != 0)
            drawIcon(c, icBtnB, cx + sp, cy, sz, state.buttons and BTN_B != 0)
        }

        private fun drawIcon(
            c: Canvas,
            d: Drawable?,
            cx: Float,
            cy: Float,
            size: Float,
            pressed: Boolean,
        ) {
            if (pressed) {
                drawDrawablePressed(c, d, cx, cy, size)
            } else {
                drawDrawable(c, d, cx, cy, size)
            }
        }

        private fun drawStick(
            c: Canvas,
            cx: Float,
            cy: Float,
            dx: Float,
            dy: Float,
            radius: Float,
            icon: Drawable?,
        ) {
            c.drawCircle(cx, cy, radius, paintStickBg)
            val thumbCx = cx + dx * radius
            val thumbCy = cy + dy * radius
            val thumbSize = radius * 1.1f
            drawDrawable(c, icon, thumbCx, thumbCy, thumbSize)
        }

        private fun drawCenterButtons(c: Canvas) {
            val sz = smallBtnRadius * 2.2f
            drawIcon(c, icSelect, selectCx, centerBtnCy, sz, state.buttons and BTN_SELECT != 0)
            drawIcon(c, icStart, startCx, centerBtnCy, sz, state.buttons and BTN_START != 0)
            drawIcon(c, icHome, homeCx, centerBtnCy + smallBtnRadius * 2.5f, sz * 0.8f, state.buttons and BTN_HOME != 0)
        }

        private fun drawShoulders(c: Canvas) {
            val lbSz = lbRect.height() * 2f
            drawIcon(c, icLB, lbRect.centerX(), lbRect.centerY(), lbSz, state.buttons and BTN_LB != 0)
            drawIcon(c, icRB, rbRect.centerX(), rbRect.centerY(), lbSz, state.buttons and BTN_RB != 0)
        }

        private fun drawTriggers(c: Canvas) {
            val ltSz = ltRect.width() * 2f
            drawIcon(c, icLT, ltRect.centerX(), ltRect.centerY(), ltSz, state.leftTrigger > 0)
            drawIcon(c, icRT, rtRect.centerX(), rtRect.centerY(), ltSz, state.rightTrigger > 0)
        }

        // ── Touch handling ───────────────────────────────────────────────────────

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val idx = event.actionIndex
                    handlePointerDown(event, idx)
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        handlePointerMove(event, i)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val idx = event.actionIndex
                    handlePointerUp(event, idx)
                }
                MotionEvent.ACTION_CANCEL -> {
                    resetAll()
                }
            }
            invalidate()
            listener?.onGamepadStateChanged(state)
            return true
        }

        @Suppress("CyclomaticComplexity", "MagicNumber", "ReturnCount")
        private fun handlePointerDown(
            event: MotionEvent,
            idx: Int,
        ) {
            val x = event.getX(idx)
            val y = event.getY(idx)
            val pid = event.getPointerId(idx)

            // Sticks
            if (hypot(x - leftStickCx, y - leftStickCy) < stickRadius * 1.3f) {
                leftStickPointerId = pid
                return
            }
            if (hypot(x - rightStickCx, y - rightStickCy) < stickRadius * 1.3f) {
                rightStickPointerId = pid
                return
            }

            // L3/R3 sticks (touch = button pressed + axis control)
            if (hypot(x - l3StickCx, y - l3StickCy) < l3StickRadius * 1.3f) {
                l3StickPointerId = pid
                state.buttons = state.buttons or BTN_LS
                return
            }
            if (hypot(x - r3StickCx, y - r3StickCy) < l3StickRadius * 1.3f) {
                r3StickPointerId = pid
                state.buttons = state.buttons or BTN_RS
                return
            }

            // Triggers
            if (ltRect.contains(x, y)) {
                ltPointerId = pid
                ltStartY = y
                return
            }
            if (rtRect.contains(x, y)) {
                rtPointerId = pid
                rtStartY = y
                return
            }

            // Shoulders
            if (lbRect.contains(x, y)) {
                state.buttons = state.buttons or BTN_LB
                return
            }
            if (rbRect.contains(x, y)) {
                state.buttons = state.buttons or BTN_RB
                return
            }

            // D-Pad
            if (dpadRect.contains(x, y)) {
                dpadPointerId = pid
                updateDpad(x, y)
                return
            }

            // ABXY
            if (abxyRect.contains(x, y)) {
                abxyPointerId = pid
                updateAbxy(x, y)
                return
            }

            // Center buttons
            if (hypot(x - selectCx, y - centerBtnCy) < smallBtnRadius * 1.5f) {
                state.buttons = state.buttons or BTN_SELECT
                return
            }
            if (hypot(x - startCx, y - centerBtnCy) < smallBtnRadius * 1.5f) {
                state.buttons = state.buttons or BTN_START
                return
            }
            if (hypot(x - homeCx, y - (centerBtnCy + smallBtnRadius * 2.5f)) < smallBtnRadius * 1.5f) {
                state.buttons = state.buttons or BTN_HOME
                return
            }
        }

        @Suppress("MagicNumber")
        private fun handlePointerMove(
            event: MotionEvent,
            idx: Int,
        ) {
            val x = event.getX(idx)
            val y = event.getY(idx)
            val pid = event.getPointerId(idx)

            if (pid == leftStickPointerId) {
                val dx = (x - leftStickCx) / stickRadius
                val dy = (y - leftStickCy) / stickRadius
                val d = hypot(dx, dy).coerceAtMost(1f)
                val angle = atan2(dy, dx)
                leftStickDx = d * kotlin.math.cos(angle)
                leftStickDy = d * kotlin.math.sin(angle)
                state.leftX = (leftStickDx * AXIS_MAX).toInt().toShort()
                state.leftY = (leftStickDy * AXIS_MAX).toInt().toShort()
            }
            if (pid == rightStickPointerId) {
                val dx = (x - rightStickCx) / stickRadius
                val dy = (y - rightStickCy) / stickRadius
                val d = hypot(dx, dy).coerceAtMost(1f)
                val angle = atan2(dy, dx)
                rightStickDx = d * kotlin.math.cos(angle)
                rightStickDy = d * kotlin.math.sin(angle)
                state.rightX = (rightStickDx * AXIS_MAX).toInt().toShort()
                state.rightY = (rightStickDy * AXIS_MAX).toInt().toShort()
            }
            // L3 stick — same axes as L stick, plus L3 button held
            if (pid == l3StickPointerId) {
                val dx = (x - l3StickCx) / l3StickRadius
                val dy = (y - l3StickCy) / l3StickRadius
                val d = hypot(dx, dy).coerceAtMost(1f)
                val angle = atan2(dy, dx)
                l3StickDx = d * kotlin.math.cos(angle)
                l3StickDy = d * kotlin.math.sin(angle)
                state.leftX = (l3StickDx * AXIS_MAX).toInt().toShort()
                state.leftY = (l3StickDy * AXIS_MAX).toInt().toShort()
            }
            // R3 stick — same axes as R stick, plus R3 button held
            if (pid == r3StickPointerId) {
                val dx = (x - r3StickCx) / l3StickRadius
                val dy = (y - r3StickCy) / l3StickRadius
                val d = hypot(dx, dy).coerceAtMost(1f)
                val angle = atan2(dy, dx)
                r3StickDx = d * kotlin.math.cos(angle)
                r3StickDy = d * kotlin.math.sin(angle)
                state.rightX = (r3StickDx * AXIS_MAX).toInt().toShort()
                state.rightY = (r3StickDy * AXIS_MAX).toInt().toShort()
            }
            if (pid == ltPointerId) {
                val drag = ((y - ltStartY) / (ltRect.height() * 0.4f)).coerceIn(0f, 1f)
                state.leftTrigger = (drag * 255).toInt()
            }
            if (pid == rtPointerId) {
                val drag = ((y - rtStartY) / (rtRect.height() * 0.4f)).coerceIn(0f, 1f)
                state.rightTrigger = (drag * 255).toInt()
            }

            // Live D-Pad tracking — only for the pointer that started on the d-pad
            if (pid == dpadPointerId) {
                if (dpadRect.contains(x, y)) {
                    updateDpad(x, y)
                } else {
                    state.hatSwitch = HAT_NONE
                }
            }
        }

        @Suppress("MagicNumber", "ReturnCount")
        private fun handlePointerUp(
            event: MotionEvent,
            idx: Int,
        ) {
            val pid = event.getPointerId(idx)
            val x = event.getX(idx)
            val y = event.getY(idx)

            if (pid == leftStickPointerId) {
                leftStickPointerId = -1
                leftStickDx = 0f
                leftStickDy = 0f
                state.leftX = 0
                state.leftY = 0
                return
            }
            if (pid == rightStickPointerId) {
                rightStickPointerId = -1
                rightStickDx = 0f
                rightStickDy = 0f
                state.rightX = 0
                state.rightY = 0
                return
            }
            if (pid == l3StickPointerId) {
                l3StickPointerId = -1
                l3StickDx = 0f
                l3StickDy = 0f
                state.leftX = 0
                state.leftY = 0
                state.buttons = state.buttons and BTN_LS.inv()
                return
            }
            if (pid == r3StickPointerId) {
                r3StickPointerId = -1
                r3StickDx = 0f
                r3StickDy = 0f
                state.rightX = 0
                state.rightY = 0
                state.buttons = state.buttons and BTN_RS.inv()
                return
            }
            if (pid == ltPointerId) {
                ltPointerId = -1
                state.leftTrigger = 0
                return
            }
            if (pid == rtPointerId) {
                rtPointerId = -1
                state.rightTrigger = 0
                return
            }

            // D-pad and ABXY — clear by pointer ID, not position
            if (pid == dpadPointerId) {
                dpadPointerId = -1
                state.hatSwitch = HAT_NONE
                return
            }
            if (pid == abxyPointerId) {
                abxyPointerId = -1
                state.buttons = state.buttons and (BTN_A or BTN_B or BTN_X or BTN_Y).inv()
                return
            }

            // Shoulders and discrete buttons — clear by position
            if (lbRect.contains(x, y)) {
                state.buttons = state.buttons and BTN_LB.inv()
                return
            }
            if (rbRect.contains(x, y)) {
                state.buttons = state.buttons and BTN_RB.inv()
                return
            }
            // Clear all discrete buttons for simplicity on up
            state.buttons = state.buttons and (BTN_SELECT or BTN_START or BTN_HOME or BTN_LS or BTN_RS).inv()
        }

        private fun resetAll() {
            state = GamepadState()
            leftStickPointerId = -1
            rightStickPointerId = -1
            leftStickDx = 0f
            leftStickDy = 0f
            rightStickDx = 0f
            rightStickDy = 0f
            ltPointerId = -1
            rtPointerId = -1
            l3StickPointerId = -1
            r3StickPointerId = -1
            l3StickDx = 0f
            l3StickDy = 0f
            r3StickDx = 0f
            r3StickDy = 0f
            dpadPointerId = -1
            abxyPointerId = -1
        }

        @Suppress("MagicNumber")
        private fun updateDpad(
            x: Float,
            y: Float,
        ) {
            val cx = dpadRect.centerX()
            val cy = dpadRect.centerY()
            val dx = x - cx
            val dy = y - cy
            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).let { if (it < 0) it + 360 else it }
            state.hatSwitch =
                when {
                    angle < 22.5 || angle >= 337.5 -> HAT_E
                    angle < 67.5 -> HAT_SE
                    angle < 112.5 -> HAT_S
                    angle < 157.5 -> HAT_SW
                    angle < 202.5 -> HAT_W
                    angle < 247.5 -> HAT_NW
                    angle < 292.5 -> HAT_N
                    else -> HAT_NE
                }
        }

        @Suppress("MagicNumber")
        private fun updateAbxy(
            x: Float,
            y: Float,
        ) {
            val cx = abxyRect.centerX()
            val cy = abxyRect.centerY()
            val sp = btnRadius * 1.5f
            if (hypot(x - cx, y - (cy - sp)) < btnRadius * 1.3f) state.buttons = state.buttons or BTN_Y
            if (hypot(x - cx, y - (cy + sp)) < btnRadius * 1.3f) state.buttons = state.buttons or BTN_A
            if (hypot(x - (cx - sp), y - cy) < btnRadius * 1.3f) state.buttons = state.buttons or BTN_X
            if (hypot(x - (cx + sp), y - cy) < btnRadius * 1.3f) state.buttons = state.buttons or BTN_B
        }
    }
