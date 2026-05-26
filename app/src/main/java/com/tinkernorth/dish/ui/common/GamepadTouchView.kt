// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tinkernorth.dish.R
import com.tinkernorth.dish.ui.common.GamepadConstants.ABXY_BTN_DRAW_SIZE_FACTOR
import com.tinkernorth.dish.ui.common.GamepadConstants.ABXY_BTN_SPACING_FACTOR
import com.tinkernorth.dish.ui.common.GamepadConstants.CENTER_BTN_DRAW_SIZE_FACTOR
import com.tinkernorth.dish.ui.common.GamepadConstants.HOME_DRAW_SIZE_FACTOR
import com.tinkernorth.dish.ui.common.GamepadConstants.HOME_VERTICAL_OFFSET_FACTOR
import com.tinkernorth.dish.ui.common.GamepadConstants.PILL_CORNER_RADIUS_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.PILL_ICON_SIZE_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.STICK_DIR_LINE_WIDTH_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.STICK_LABEL_BASELINE_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.STICK_LABEL_SIZE_MULTI
import com.tinkernorth.dish.ui.common.GamepadConstants.STICK_LABEL_SIZE_SINGLE
import com.tinkernorth.dish.ui.common.GamepadConstants.STICK_RING_STROKE_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.STICK_THUMB_RADIUS_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.STICK_THUMB_RING_STROKE_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.STICK_THUMB_TRAVEL_FRACTION
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class GamepadTouchView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        interface Listener {
            fun onGamepadStateChanged(state: GamepadState)
        }

        var listener: Listener? = null

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
        }

        var usePlayStation = false
            set(value) {
                field = value
                loadDrawables()
                relayout()
                invalidate()
            }

        private val paintBg =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorSurface)
            }
        private val paintStickBg =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorSurfaceDim)
            }
        private val paintPressed =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color =
                    ColorUtils.setAlphaComponent(
                        ContextCompat.getColor(context, R.color.colorPrimary),
                        0x40,
                    )
            }
        private val paintStickRing =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = ContextCompat.getColor(context, R.color.colorPrimaryDark)
            }
        private val paintStickDir =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color =
                    ColorUtils.setAlphaComponent(
                        ContextCompat.getColor(context, R.color.colorPrimary),
                        0x80,
                    )
                strokeCap = Paint.Cap.ROUND
            }
        private val paintStickThumb =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorOnSurface)
            }
        private val paintStickThumbActive =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorPrimary)
            }
        private val paintStickLabel =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorBackground)
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
        private val paintPillBg =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorSurfaceDim)
            }
        private val paintPillPressed =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorPrimary)
            }

        // Per-channel min compositor for diagonal d-pad rendering — see [drawDpad].
        private val dpadDarkenPaint =
            Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN) }

        private val safeInsets = Rect()

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

        private var density = 1f
        private var layout: GamepadLayout? = null
        private val recognizer = GamepadGestureRecognizer()

        init {
            loadDrawables()
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, wi ->
                val ins =
                    wi.getInsets(
                        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                    )
                if (ins.left != safeInsets.left ||
                    ins.top != safeInsets.top ||
                    ins.right != safeInsets.right ||
                    ins.bottom != safeInsets.bottom
                ) {
                    safeInsets.set(ins.left, ins.top, ins.right, ins.bottom)
                    if (v.width > 0 && v.height > 0) {
                        relayout()
                        invalidate()
                    }
                }
                wi
            }
        }

        @Suppress("CyclomaticComplexMethod")
        private fun loadDrawables() {
            val c = context
            val tint = ContextCompat.getColor(c, R.color.colorOnSurface)
            if (usePlayStation) {
                icBtnA = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_cross)
                icBtnB = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_circle)
                icBtnX = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_square)
                icBtnY = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_triangle)
                icDpad = loadTinted(c, R.drawable.ic_gp_ps_dpad, tint)
                icDpadUp = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_dpad_up)
                icDpadDown = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_dpad_down)
                icDpadLeft = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_dpad_left)
                icDpadRight = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_dpad_right)
                icLB = loadTinted(c, R.drawable.ic_gp_ps_l1, tint)
                icRB = loadTinted(c, R.drawable.ic_gp_ps_r1, tint)
                icLT = loadTinted(c, R.drawable.ic_gp_ps_l2, tint)
                icRT = loadTinted(c, R.drawable.ic_gp_ps_r2, tint)
                icStickL = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_stick_l)
                icStickR = ContextCompat.getDrawable(c, R.drawable.ic_gp_ps_stick_r)
                icSelect = loadTinted(c, R.drawable.ic_gp_ps_share, tint)
                icStart = loadTinted(c, R.drawable.ic_gp_ps_options, tint)
                icHome = loadTinted(c, R.drawable.ic_gp_ps_logo, tint)
            } else {
                icBtnA = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_a)
                icBtnB = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_b)
                icBtnX = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_x)
                icBtnY = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_y)
                icDpad = loadTinted(c, R.drawable.ic_gp_xbox_dpad, tint)
                icDpadUp = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_dpad_up)
                icDpadDown = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_dpad_down)
                icDpadLeft = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_dpad_left)
                icDpadRight = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_dpad_right)
                icLB = loadTinted(c, R.drawable.ic_gp_xbox_lb, tint)
                icRB = loadTinted(c, R.drawable.ic_gp_xbox_rb, tint)
                icLT = loadTinted(c, R.drawable.ic_gp_xbox_lt, tint)
                icRT = loadTinted(c, R.drawable.ic_gp_xbox_rt, tint)
                icStickL = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_stick_l)
                icStickR = ContextCompat.getDrawable(c, R.drawable.ic_gp_xbox_stick_r)
                icSelect = loadTinted(c, R.drawable.ic_gp_xbox_view, tint)
                icStart = loadTinted(c, R.drawable.ic_gp_xbox_menu, tint)
                icHome = loadTinted(c, R.drawable.ic_gp_xbox_guide, tint)
            }
        }

        private fun loadTinted(
            c: Context,
            id: Int,
            tint: Int,
        ): Drawable? = ContextCompat.getDrawable(c, id)?.mutate()?.apply { setTint(tint) }

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldW: Int,
            oldH: Int,
        ) {
            super.onSizeChanged(w, h, oldW, oldH)
            density = resources.displayMetrics.density
            relayout()
        }

        private fun relayout() {
            if (width <= 0 || height <= 0) return
            density = resources.displayMetrics.density
            layout = computeGamepadLayout(width, height, density, safeInsets, usePlayStation)
        }

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
            val l = layout ?: return
            val s = recognizer.state
            drawDpad(canvas, l, s)
            drawAbxy(canvas, l, s)
            drawStick(canvas, l.leftStickCx, l.leftStickCy, recognizer.leftStickDx, recognizer.leftStickDy, l.stickRadius, "L")
            drawStick(canvas, l.rightStickCx, l.rightStickCy, recognizer.rightStickDx, recognizer.rightStickDy, l.stickRadius, "R")
            drawStick(canvas, l.l3StickCx, l.l3StickCy, recognizer.l3StickDx, recognizer.l3StickDy, l.l3StickRadius, "L3")
            drawStick(canvas, l.r3StickCx, l.r3StickCy, recognizer.r3StickDx, recognizer.r3StickDy, l.l3StickRadius, "R3")
            drawCenterButtons(canvas, l, s)
            drawShoulders(canvas, l, s)
            drawTriggers(canvas, l, s)
        }

        private fun drawDpad(
            c: Canvas,
            l: GamepadLayout,
            s: GamepadState,
        ) {
            val cx = l.dpadRect.centerX()
            val cy = l.dpadRect.centerY()
            val size = l.dpadRect.width()
            drawDrawable(c, icDpad, cx, cy, size)

            val first: Drawable?
            val second: Drawable?
            when (s.hatSwitch) {
                HAT_N -> {
                    first = icDpadUp
                    second = null
                }
                HAT_NE -> {
                    first = icDpadUp
                    second = icDpadRight
                }
                HAT_E -> {
                    first = icDpadRight
                    second = null
                }
                HAT_SE -> {
                    first = icDpadDown
                    second = icDpadRight
                }
                HAT_S -> {
                    first = icDpadDown
                    second = null
                }
                HAT_SW -> {
                    first = icDpadDown
                    second = icDpadLeft
                }
                HAT_W -> {
                    first = icDpadLeft
                    second = null
                }
                HAT_NW -> {
                    first = icDpadUp
                    second = icDpadLeft
                }
                else -> {
                    first = null
                    second = null
                }
            }

            if (first == null) return
            drawDrawable(c, first, cx, cy, size)
            if (second == null) return

            // Composite the second arrow with DARKEN (per-channel min) so both accent arms of
            // a diagonal survive — otherwise the second icon's white overwrites the first's.
            val saveCount =
                c.saveLayer(
                    l.dpadRect.left,
                    l.dpadRect.top,
                    l.dpadRect.right,
                    l.dpadRect.bottom,
                    dpadDarkenPaint,
                )
            drawDrawable(c, second, cx, cy, size)
            c.restoreToCount(saveCount)
        }

        private fun drawAbxy(
            c: Canvas,
            l: GamepadLayout,
            s: GamepadState,
        ) {
            val cx = l.abxyRect.centerX()
            val cy = l.abxyRect.centerY()
            val sp = l.btnRadius * ABXY_BTN_SPACING_FACTOR
            val sz = l.btnRadius * ABXY_BTN_DRAW_SIZE_FACTOR
            drawIcon(c, icBtnY, cx, cy - sp, sz, s.buttons and BTN_Y != 0)
            drawIcon(c, icBtnA, cx, cy + sp, sz, s.buttons and BTN_A != 0)
            drawIcon(c, icBtnX, cx - sp, cy, sz, s.buttons and BTN_X != 0)
            drawIcon(c, icBtnB, cx + sp, cy, sz, s.buttons and BTN_B != 0)
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
            label: String,
        ) {
            c.drawCircle(cx, cy, radius, paintStickBg)
            paintStickRing.strokeWidth = STICK_RING_STROKE_DP * density
            c.drawCircle(cx, cy, radius, paintStickRing)

            val travel = radius * STICK_THUMB_TRAVEL_FRACTION
            val thumbCx = cx + dx * travel
            val thumbCy = cy + dy * travel
            val thumbR = radius * STICK_THUMB_RADIUS_FRACTION

            val active = (dx != 0f || dy != 0f)
            if (active) {
                paintStickDir.strokeWidth = thumbR * STICK_DIR_LINE_WIDTH_FRACTION
                c.drawLine(cx, cy, thumbCx, thumbCy, paintStickDir)
            }

            c.drawCircle(thumbCx, thumbCy, thumbR, if (active) paintStickThumbActive else paintStickThumb)
            paintStickRing.strokeWidth = STICK_THUMB_RING_STROKE_DP * density
            c.drawCircle(thumbCx, thumbCy, thumbR, paintStickRing)

            paintStickLabel.textSize =
                thumbR * (if (label.length > 1) STICK_LABEL_SIZE_MULTI else STICK_LABEL_SIZE_SINGLE)
            c.drawText(label, thumbCx, thumbCy + thumbR * STICK_LABEL_BASELINE_FRACTION, paintStickLabel)
        }

        private fun drawCenterButtons(
            c: Canvas,
            l: GamepadLayout,
            s: GamepadState,
        ) {
            val sz = l.smallBtnRadius * CENTER_BTN_DRAW_SIZE_FACTOR
            drawIcon(c, icSelect, l.selectCx, l.centerBtnCy, sz, s.buttons and BTN_SELECT != 0)
            drawIcon(c, icStart, l.startCx, l.centerBtnCy, sz, s.buttons and BTN_START != 0)
            val homeCy = l.centerBtnCy + l.smallBtnRadius * HOME_VERTICAL_OFFSET_FACTOR
            drawIcon(c, icHome, l.homeCx, homeCy, sz * HOME_DRAW_SIZE_FACTOR, s.buttons and BTN_HOME != 0)
        }

        private fun drawPillButton(
            c: Canvas,
            rect: RectF,
            d: Drawable?,
            pressed: Boolean,
        ) {
            val r = min(rect.width(), rect.height()) * PILL_CORNER_RADIUS_FRACTION
            c.drawRoundRect(rect, r, r, if (pressed) paintPillPressed else paintPillBg)
            if (d != null) {
                val iconSize = (min(rect.width(), rect.height()) * PILL_ICON_SIZE_FRACTION).toInt()
                val half = iconSize / 2
                val cx = rect.centerX().toInt()
                val cy = rect.centerY().toInt()
                d.setBounds(cx - half, cy - half, cx + half, cy + half)
                d.draw(c)
            }
        }

        private fun drawShoulders(
            c: Canvas,
            l: GamepadLayout,
            s: GamepadState,
        ) {
            drawPillButton(c, l.lbRect, icLB, s.buttons and BTN_LB != 0)
            drawPillButton(c, l.rbRect, icRB, s.buttons and BTN_RB != 0)
        }

        private fun drawTriggers(
            c: Canvas,
            l: GamepadLayout,
            s: GamepadState,
        ) {
            drawPillButton(c, l.ltRect, icLT, s.leftTrigger > 0)
            drawPillButton(c, l.rtRect, icRT, s.rightTrigger > 0)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val l = layout ?: return false
            // Opt out of vsync coalescing so each touch sensor sample is delivered as it
            // arrives instead of being batched to display refresh.
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
            ) {
                requestUnbufferedDispatch(event)
            }
            recognizer.onTouchEvent(event, l) {
                listener?.onGamepadStateChanged(recognizer.state)
            }
            invalidate()
            return true
        }
    }

internal data class StickAxes(
    val dx: Float,
    val dy: Float,
    val axisX: Short,
    val axisY: Short,
)

internal fun computeStickAxes(
    rawDx: Float,
    rawDy: Float,
): StickAxes {
    val d = hypot(rawDx, rawDy).coerceAtMost(1f)
    val angle = atan2(rawDy, rawDx)
    val dx = d * cos(angle)
    val dy = d * sin(angle)
    val max = Short.MAX_VALUE.toFloat()
    return StickAxes(
        dx = dx,
        dy = dy,
        axisX = (dx * max).toInt().toShort(),
        // Sign flip: Android view coords are y-down but XInput wire expects stick-up = +Y.
        axisY = (-dy * max).toInt().toShort(),
    )
}
