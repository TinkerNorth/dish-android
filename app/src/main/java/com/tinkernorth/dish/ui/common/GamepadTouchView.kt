package com.tinkernorth.dish.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tinkernorth.dish.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

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

            private const val TRIGGER_MAX = 255
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
        private val paintStickRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF475569.toInt()
        }
        private val paintStickDir = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x80CBD5E1.toInt()
            strokeCap = Paint.Cap.ROUND
        }
        private val paintStickThumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCBD5E1.toInt() }
        private val paintStickThumbActive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF60A5FA.toInt() }
        private val paintStickLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val paintPillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF293548.toInt() }
        private val paintPillPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3B82F6.toInt() }

        // ── Safe-area insets (system bars + display cutouts) ─────────────────────
        private val safeInsets = Rect()

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
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, wi ->
                val ins = wi.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
                if (ins.left != safeInsets.left || ins.top != safeInsets.top ||
                    ins.right != safeInsets.right || ins.bottom != safeInsets.bottom
                ) {
                    safeInsets.set(ins.left, ins.top, ins.right, ins.bottom)
                    if (v.width > 0 && v.height > 0) {
                        onSizeChanged(v.width, v.height, v.width, v.height)
                        invalidate()
                    }
                }
                wi
            }
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
        private var dpadPointerId = -1
        private var abxyPointerId = -1
        private var lbPointerId = -1
        private var rbPointerId = -1

        @Suppress("LongMethod", "MagicNumber")
        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldW: Int,
            oldH: Int,
        ) {
            super.onSizeChanged(w, h, oldW, oldH)
            dp = resources.displayMetrics.density

            // Safe-area envelope: system bars + display cutouts + small cushion so
            // nothing abuts rounded corners / gesture zones.
            val cushion = 6 * dp
            val safeTop = safeInsets.top + cushion
            val safeLeft = safeInsets.left + cushion
            val safeRight = w - safeInsets.right - cushion
            val safeBottom = h - safeInsets.bottom - cushion

            // Shoulder band along the top, trigger columns along the sides. The
            // inner content rect is what's left — all other controls are laid out
            // relative to it so they never float over shoulders/triggers.
            val sbH = 56 * dp
            val sbCornerInset = (safeRight - safeLeft) * 0.04f
            val tW = 52 * dp
            val gap = 6 * dp

            lbRect.set(safeLeft + sbCornerInset, safeTop, (safeLeft + safeRight) / 2f - 80 * dp, safeTop + sbH)
            rbRect.set((safeLeft + safeRight) / 2f + 80 * dp, safeTop, safeRight - sbCornerInset, safeTop + sbH)

            val contentTop = safeTop + sbH + gap
            val contentBottom = safeBottom
            ltRect.set(safeLeft, contentTop, safeLeft + tW, contentBottom)
            rtRect.set(safeRight - tW, contentTop, safeRight, contentBottom)

            val contentLeft = ltRect.right + gap
            val contentRight = rtRect.left - gap
            val contentW = contentRight - contentLeft
            val contentH = contentBottom - contentTop
            val qw = contentW / 4f

            // Top row centre y / bottom row centre y inside the content rect.
            val topRowCy = contentTop + contentH * 0.28f
            val bottomRowCy = contentTop + contentH * 0.75f

            val pad = 12 * dp
            val dpadSize = min(qw * 2f - pad * 2, contentH * 0.42f)
            val dpadCx = contentLeft + qw * 0.55f
            // Xbox: stick top-left, d-pad bottom-left. PlayStation: swap.
            val dpadCy = if (usePlayStation) topRowCy else bottomRowCy
            dpadRect.set(dpadCx - dpadSize / 2, dpadCy - dpadSize / 2, dpadCx + dpadSize / 2, dpadCy + dpadSize / 2)

            stickRadius = min(qw * 0.42f, contentH * 0.2f)
            l3StickRadius = stickRadius * 0.7f
            leftStickCx = contentLeft + qw * 0.5f
            leftStickCy = if (usePlayStation) bottomRowCy else topRowCy
            l3StickCx = leftStickCx + stickRadius + l3StickRadius + 12 * dp
            l3StickCy = leftStickCy

            // ABXY mirrors the d-pad on the right; right stick mirrors the left stick.
            val abxyCx = contentRight - qw * 0.55f
            val abxyCy = topRowCy
            val abxySize = dpadSize
            abxyRect.set(abxyCx - abxySize / 2, abxyCy - abxySize / 2, abxyCx + abxySize / 2, abxyCy + abxySize / 2)
            btnRadius = abxySize * 0.18f

            rightStickCx = contentRight - qw * 0.5f
            rightStickCy = bottomRowCy
            r3StickCx = rightStickCx - stickRadius - l3StickRadius - 12 * dp
            r3StickCy = rightStickCy

            // Centre cluster sits between the shoulder band and the top row so it
            // never overlaps the d-pad / ABXY groups.
            smallBtnRadius = 14 * dp
            centerBtnCy = contentTop + smallBtnRadius * 1.8f
            val centerCx = (contentLeft + contentRight) / 2f
            selectCx = centerCx - 40 * dp
            startCx = centerCx + 40 * dp
            homeCx = centerCx
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
            drawStick(canvas, leftStickCx, leftStickCy, leftStickDx, leftStickDy, stickRadius, "L")
            drawStick(canvas, rightStickCx, rightStickCy, rightStickDx, rightStickDy, stickRadius, "R")
            drawStick(canvas, l3StickCx, l3StickCy, l3StickDx, l3StickDy, l3StickRadius, "L3")
            drawStick(canvas, r3StickCx, r3StickCy, r3StickDx, r3StickDy, l3StickRadius, "R3")
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

        @Suppress("MagicNumber")
        private fun drawStick(
            c: Canvas,
            cx: Float,
            cy: Float,
            dx: Float,
            dy: Float,
            radius: Float,
            label: String,
        ) {
            // Top-down view: outer base ring, a direction line from center to the
            // displaced thumb cap, then the thumb cap with its label. No side-view
            // stem, so the stick no longer appears to always point down.
            c.drawCircle(cx, cy, radius, paintStickBg)
            paintStickRing.strokeWidth = 2f * dp
            c.drawCircle(cx, cy, radius, paintStickRing)

            val travel = radius * 0.55f
            val thumbCx = cx + dx * travel
            val thumbCy = cy + dy * travel
            val thumbR = radius * 0.55f

            if (dx != 0f || dy != 0f) {
                paintStickDir.strokeWidth = thumbR * 0.35f
                c.drawLine(cx, cy, thumbCx, thumbCy, paintStickDir)
            }

            val active = (dx != 0f || dy != 0f)
            c.drawCircle(thumbCx, thumbCy, thumbR, if (active) paintStickThumbActive else paintStickThumb)
            paintStickRing.strokeWidth = 1.5f * dp
            c.drawCircle(thumbCx, thumbCy, thumbR, paintStickRing)

            paintStickLabel.textSize = thumbR * (if (label.length > 1) 0.7f else 0.95f)
            c.drawText(label, thumbCx, thumbCy + thumbR * 0.33f, paintStickLabel)
        }

        private fun drawCenterButtons(c: Canvas) {
            val sz = smallBtnRadius * 2.2f
            drawIcon(c, icSelect, selectCx, centerBtnCy, sz, state.buttons and BTN_SELECT != 0)
            drawIcon(c, icStart, startCx, centerBtnCy, sz, state.buttons and BTN_START != 0)
            drawIcon(c, icHome, homeCx, centerBtnCy + smallBtnRadius * 2.5f, sz * 0.8f, state.buttons and BTN_HOME != 0)
        }

        private fun drawPillButton(
            c: Canvas,
            rect: RectF,
            d: Drawable?,
            pressed: Boolean,
        ) {
            val r = min(rect.width(), rect.height()) * 0.35f
            c.drawRoundRect(rect, r, r, if (pressed) paintPillPressed else paintPillBg)
            if (d != null) {
                val iconSize = (min(rect.width(), rect.height()) * 0.8f).toInt()
                val half = iconSize / 2
                val cx = rect.centerX().toInt()
                val cy = rect.centerY().toInt()
                d.setBounds(cx - half, cy - half, cx + half, cy + half)
                d.draw(c)
            }
        }

        private fun drawShoulders(c: Canvas) {
            drawPillButton(c, lbRect, icLB, state.buttons and BTN_LB != 0)
            drawPillButton(c, rbRect, icRB, state.buttons and BTN_RB != 0)
        }

        private fun drawTriggers(c: Canvas) {
            drawPillButton(c, ltRect, icLT, state.leftTrigger > 0)
            drawPillButton(c, rtRect, icRT, state.rightTrigger > 0)
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

            // Triggers — binary press, tracked by pointer id so drag-off still
            // releases cleanly on UP.
            if (ltRect.contains(x, y)) {
                ltPointerId = pid
                state.leftTrigger = TRIGGER_MAX
                return
            }
            if (rtRect.contains(x, y)) {
                rtPointerId = pid
                state.rightTrigger = TRIGGER_MAX
                return
            }

            // Shoulders — pointer-id tracked so release works regardless of
            // where the finger ends up on UP.
            if (lbRect.contains(x, y)) {
                lbPointerId = pid
                state.buttons = state.buttons or BTN_LB
                return
            }
            if (rbRect.contains(x, y)) {
                rbPointerId = pid
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
                val r = computeStickAxes((x - leftStickCx) / stickRadius, (y - leftStickCy) / stickRadius)
                leftStickDx = r.dx; leftStickDy = r.dy
                state.leftX = r.axisX; state.leftY = r.axisY
            }
            if (pid == rightStickPointerId) {
                val r = computeStickAxes((x - rightStickCx) / stickRadius, (y - rightStickCy) / stickRadius)
                rightStickDx = r.dx; rightStickDy = r.dy
                state.rightX = r.axisX; state.rightY = r.axisY
            }
            // L3 stick — same axes as L stick, plus L3 button held
            if (pid == l3StickPointerId) {
                val r = computeStickAxes((x - l3StickCx) / l3StickRadius, (y - l3StickCy) / l3StickRadius)
                l3StickDx = r.dx; l3StickDy = r.dy
                state.leftX = r.axisX; state.leftY = r.axisY
            }
            // R3 stick — same axes as R stick, plus R3 button held
            if (pid == r3StickPointerId) {
                val r = computeStickAxes((x - r3StickCx) / l3StickRadius, (y - r3StickCy) / l3StickRadius)
                r3StickDx = r.dx; r3StickDy = r.dy
                state.rightX = r.axisX; state.rightY = r.axisY
            }
            // Triggers are binary — no move-modulation. The DOWN/UP handlers
            // set the value; MOVE is a no-op for trigger pointers.

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

            // All button-region pointers clear by pointer ID, never by
            // position — otherwise dragging the finger off the rect before
            // release leaves the bit stuck.
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
            if (pid == lbPointerId) {
                lbPointerId = -1
                state.buttons = state.buttons and BTN_LB.inv()
                return
            }
            if (pid == rbPointerId) {
                rbPointerId = -1
                state.buttons = state.buttons and BTN_RB.inv()
                return
            }
            // Center / stick-click buttons: the pointer isn't tracked by id so
            // just drop all of them on any unmatched up — same as before.
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
            lbPointerId = -1
            rbPointerId = -1
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

/**
 * Result of mapping a virtual-stick finger offset to visual + wire values.
 *
 * @property dx     unit-circle-clamped finger x for rendering, range -1f..1f
 * @property dy     unit-circle-clamped finger y for rendering, range -1f..1f
 *                  (y-down: positive = below stick center, matching Android
 *                  view coordinates).
 * @property axisX  signed 16-bit wire value: +32767 = full right, -32767 = full left.
 * @property axisY  signed 16-bit wire value: +32767 = full up,    -32767 = full down.
 *                  Note the sign flip vs [dy] — see [computeStickAxes].
 */
internal data class StickAxes(
    val dx: Float,
    val dy: Float,
    val axisX: Short,
    val axisY: Short,
)

/**
 * Maps a raw finger offset (in units of the virtual stick's radius, relative
 * to the stick center) to the clamped visual position and the int16 axis
 * values carried over the wire.
 *
 * Contract:
 *  - Magnitude is clamped to 1 while preserving direction (so a finger
 *    dragged outside the stick well still yields axes saturated at
 *    ±Short.MAX_VALUE in that direction).
 *  - Android view coordinates are y-down, but the Xbox/XInput wire format
 *    expects "stick up = positive Y". This function returns
 *    `axisY = -dy * Short.MAX_VALUE`, matching the `-AXIS_MAX` scaling used
 *    in the physical path ([GamepadInputProcessor.processJoystickInput]).
 *  - A neutral input (0, 0) returns an all-zero result.
 *
 * This is the single source of truth for virtual-stick axis math; all four
 * on-screen sticks (L, R, L3, R3) funnel through it.
 */
internal fun computeStickAxes(rawDx: Float, rawDy: Float): StickAxes {
    val d = hypot(rawDx, rawDy).coerceAtMost(1f)
    val angle = atan2(rawDy, rawDx)
    val dx = d * cos(angle)
    val dy = d * sin(angle)
    val max = Short.MAX_VALUE.toFloat()
    return StickAxes(
        dx = dx,
        dy = dy,
        axisX = (dx * max).toInt().toShort(),
        axisY = (-dy * max).toInt().toShort(),
    )
}
