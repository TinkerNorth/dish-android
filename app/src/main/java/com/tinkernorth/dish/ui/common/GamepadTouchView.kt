// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
import com.tinkernorth.dish.ui.common.GamepadConstants.LIGHTBAR_BG_BLEND_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.LIGHTBAR_STROKE_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.PILL_CORNER_RADIUS_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.PILL_ICON_SIZE_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.PLAYER_LED_COUNT
import com.tinkernorth.dish.ui.common.GamepadConstants.PLAYER_LED_GAP_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.PLAYER_LED_PITCH_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.PLAYER_LED_RADIUS_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.STICK_DIR_LINE_WIDTH_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.STICK_LABEL_BASELINE_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.STICK_LABEL_SIZE_MULTI
import com.tinkernorth.dish.ui.common.GamepadConstants.STICK_LABEL_SIZE_SINGLE
import com.tinkernorth.dish.ui.common.GamepadConstants.STICK_RING_STROKE_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.STICK_THUMB_RADIUS_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.STICK_THUMB_RING_STROKE_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.STICK_THUMB_TRAVEL_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.TRACKPAD_CLICK_PULSE_MS
import com.tinkernorth.dish.ui.common.GamepadConstants.TRACKPAD_CORNER_RADIUS_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.TRACKPAD_FINGER_DOT_RADIUS_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.TRACKPAD_OUTLINE_STROKE_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.TRACKPAD_TAP_SLOP_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.TRIGGER_EFFECT_STROKE_DP
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

            fun onTrackpadStateChanged(state: TouchpadSurfaceView.TouchpadState) = Unit
        }

        // What the trackpad zone does for the bound destination: NONE hides it, TOUCH
        // streams DS4-shaped finger frames (satellite), CLICK is a plain pad-click
        // button for a wire with no touch channel (Moonlight).
        enum class TrackpadMode {
            NONE,
            TOUCH,
            CLICK,
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

            // Local-only bit: hidToXusb ignores it, so it never leaks into an XUSB wire
            // report. The satellite carries the click inside the touch frame instead, and
            // the Moonlight sender maps it onto that wire's own touchpad button flag.
            const val BTN_TOUCHPAD_CLICK = 1 shl 11

            // Local-only too, and for a second reason on top of the first: this bit is a
            // momentary PRESS, while the wire's WBUTTON_MIC_MUTE carries the mute STATE the
            // press toggles. The overlay owns that state (it is what gates capture), so the
            // press stops here and only [withMicMute] ever puts anything on the wire.
            const val BTN_MIC_MUTE = 1 shl 12

            // MSG_MIC_LED's "off"; the wire's other states (on, pulse) both accent the pill.
            private const val MIC_LED_STATE_OFF = 0

            private const val HALF_INT16 = 32768
            private const val NORM_INT16_SPAN = 65535f

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

        var skin = GamepadSkin.Xbox
            set(value) {
                field = value
                loadDrawables()
                relayout()
                invalidate()
            }

        var trackpadMode = TrackpadMode.NONE
            set(value) {
                if (field == value) return
                field = value
                recognizer.trackpadMode = value
                relayout()
                invalidate()
            }

        // Emulated types without analog triggers (Switch Pro) keep the plain
        // full-press rails; everything else gets the slide-to-pull rail.
        var analogTriggers: Boolean = true
            set(value) {
                if (field == value) return
                field = value
                recognizer.analogTriggers = value
                invalidate()
            }

        // Host-driven feedback rendered on the skin (VirtualPadFeedbackStore): the
        // phone has no lightbar, player-LED or adaptive-trigger hardware, so the
        // on-screen pad shows them the way the real controller would.
        var lightbarColor: Int? = null
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        var playerLedMask: Int = 0
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        var leftTriggerEffect: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        var rightTriggerEffect: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        // The mic-mute lamp in the wire's own states (0 off, 1 on, 2 pulse). Written by the local
        // mute and overridden by a host MSG_MIC_LED, last writer wins, exactly as on the hardware;
        // anything non-off accents the mute pill. Pulse currently paints like on.
        var micMuteLedState: Int = 0
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        private val surfaceColor = ContextCompat.getColor(context, R.color.colorSurface)

        private val paintBg =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = surfaceColor
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

        // Per-channel min compositor for diagonal d-pad rendering (see [drawDpad]).
        private val dpadDarkenPaint =
            Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN) }

        // Colour set per draw from lightbarColor; stroke like the trackpad outline
        // but wider, so the bar reads as the light around the touchpad it is on the
        // real DS4 v2 / DualSense.
        private val paintLightbar =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        private val paintLedOn =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorPrimary)
            }
        private val paintLedOff =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color =
                    ColorUtils.setAlphaComponent(
                        ContextCompat.getColor(context, R.color.colorOnSurface),
                        0x30,
                    )
            }
        private val paintTriggerEffect =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = ContextCompat.getColor(context, R.color.colorPrimary)
            }

        // Rail fill under the finger; same translucent primary the pressed
        // overlays use, clipped to the pill shape.
        private val paintTriggerFill =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color =
                    ColorUtils.setAlphaComponent(
                        ContextCompat.getColor(context, R.color.colorPrimary),
                        0x40,
                    )
            }

        // Full-zone divider, drawn in every state so the tap boundary reads
        // before, during and after a pull.
        private val paintTriggerDivider =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeCap = Paint.Cap.ROUND
                color =
                    ColorUtils.setAlphaComponent(
                        ContextCompat.getColor(context, R.color.colorOnSurfaceVariant),
                        0xB0,
                    )
            }

        private val triggerClipPath = Path()

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
        private var icMicMute: Drawable? = null

        private var density = 1f
        private var layout: GamepadLayout? = null
        private val recognizer = GamepadGestureRecognizer()
        private var trackpadClickFlash = false

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

        private fun loadDrawables() {
            val c = context
            val tint = ContextCompat.getColor(c, R.color.colorOnSurface)
            // Only the DualSense carries one, so only that skin loads it.
            icMicMute = if (skin.hasMicMute) loadTinted(c, R.drawable.ic_gp_ps5_mic, tint) else null
            when (skin) {
                GamepadSkin.PlayStation -> loadPlayStationSet(c, tint, R.drawable.ic_gp_ps_share, R.drawable.ic_gp_ps_options)
                GamepadSkin.DualSense -> loadPlayStationSet(c, tint, R.drawable.ic_gp_ps5_create, R.drawable.ic_gp_ps5_options)
                GamepadSkin.Switch -> loadSwitchSet(c, tint)
                GamepadSkin.Xbox -> loadXboxSet(c, tint, R.drawable.ic_gp_xbox_view, R.drawable.ic_gp_xbox_menu)
                GamepadSkin.Xbox360 -> loadXboxSet(c, tint, R.drawable.ic_gp_xbox_back, R.drawable.ic_gp_xbox_start)
            }
        }

        // The DualShock and DualSense share every glyph except the pair beside the touchpad:
        // Share/Options on the DS4, Create/Options on the DualSense.
        private fun loadPlayStationSet(
            c: Context,
            tint: Int,
            selectRes: Int,
            startRes: Int,
        ) {
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
            icSelect = loadTinted(c, selectRes, tint)
            icStart = loadTinted(c, startRes, tint)
            icHome = loadTinted(c, R.drawable.ic_gp_ps_logo, tint)
        }

        // Nintendo swaps A/B and X/Y positions vs Xbox: south=B, east=A, west=Y, north=X.
        private fun loadSwitchSet(
            c: Context,
            tint: Int,
        ) {
            icBtnA = ContextCompat.getDrawable(c, R.drawable.ic_gp_switch_b)
            icBtnB = ContextCompat.getDrawable(c, R.drawable.ic_gp_switch_a)
            icBtnX = ContextCompat.getDrawable(c, R.drawable.ic_gp_switch_y)
            icBtnY = ContextCompat.getDrawable(c, R.drawable.ic_gp_switch_x)
            icDpad = loadTinted(c, R.drawable.ic_gp_switch_dpad, tint)
            icDpadUp = ContextCompat.getDrawable(c, R.drawable.ic_gp_switch_dpad_up)
            icDpadDown = ContextCompat.getDrawable(c, R.drawable.ic_gp_switch_dpad_down)
            icDpadLeft = ContextCompat.getDrawable(c, R.drawable.ic_gp_switch_dpad_left)
            icDpadRight = ContextCompat.getDrawable(c, R.drawable.ic_gp_switch_dpad_right)
            icLB = loadTinted(c, R.drawable.ic_gp_switch_l, tint)
            icRB = loadTinted(c, R.drawable.ic_gp_switch_r, tint)
            icLT = loadTinted(c, R.drawable.ic_gp_switch_zl, tint)
            icRT = loadTinted(c, R.drawable.ic_gp_switch_zr, tint)
            icStickL = ContextCompat.getDrawable(c, R.drawable.ic_gp_switch_stick_l)
            icStickR = ContextCompat.getDrawable(c, R.drawable.ic_gp_switch_stick_r)
            icSelect = loadTinted(c, R.drawable.ic_gp_switch_minus, tint)
            icStart = loadTinted(c, R.drawable.ic_gp_switch_plus, tint)
            icHome = loadTinted(c, R.drawable.ic_gp_switch_home, tint)
        }

        // The 360 pad carries Back/Start where the One/Series pads carry View/Menu;
        // everything else is shared.
        private fun loadXboxSet(
            c: Context,
            tint: Int,
            selectRes: Int,
            startRes: Int,
        ) {
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
            icSelect = loadTinted(c, selectRes, tint)
            icStart = loadTinted(c, startRes, tint)
            icHome = loadTinted(c, R.drawable.ic_gp_xbox_guide, tint)
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
            recognizer.trackpadTapSlopPx = TRACKPAD_TAP_SLOP_DP * density
            layout = computeGamepadLayout(width, height, density, safeInsets, skin, trackpadMode != TrackpadMode.NONE)
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
            paintBg.color =
                lightbarColor
                    ?.takeIf { skin.hasLightbar }
                    ?.let { ColorUtils.blendARGB(surfaceColor, it, LIGHTBAR_BG_BLEND_FRACTION) }
                    ?: surfaceColor
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)
            val l = layout ?: return
            val s = recognizer.state
            drawDpad(canvas, l, s)
            drawAbxy(canvas, l, s)
            drawStick(canvas, l.leftStickCx, l.leftStickCy, recognizer.leftStickDx, recognizer.leftStickDy, l.stickRadius, "L")
            drawStick(canvas, l.rightStickCx, l.rightStickCy, recognizer.rightStickDx, recognizer.rightStickDy, l.stickRadius, "R")
            drawStick(canvas, l.l3StickCx, l.l3StickCy, recognizer.l3StickDx, recognizer.l3StickDy, l.l3StickRadius, "L3")
            drawStick(canvas, l.r3StickCx, l.r3StickCy, recognizer.r3StickDx, recognizer.r3StickDy, l.l3StickRadius, "R3")
            drawTrackpad(canvas, l, s)
            drawCenterButtons(canvas, l, s)
            drawShoulders(canvas, l, s)
            drawTriggers(canvas, l, s)
            drawPlayerLeds(canvas, l)
            drawMicMute(canvas, l, s)
        }

        // Pressed paints like any other pill; muted adds the accent ring the adaptive-trigger
        // effect already uses, so the lamp reads as a state the host set rather than as a finger.
        private fun drawMicMute(
            c: Canvas,
            l: GamepadLayout,
            s: GamepadState,
        ) {
            val rect = l.micMuteRect ?: return
            drawPillButton(c, rect, icMicMute, s.buttons and BTN_MIC_MUTE != 0)
            if (micMuteLedState == MIC_LED_STATE_OFF) return
            val r = min(rect.width(), rect.height()) * PILL_CORNER_RADIUS_FRACTION
            paintTriggerEffect.strokeWidth = TRIGGER_EFFECT_STROKE_DP * density
            c.drawRoundRect(rect, r, r, paintTriggerEffect)
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
            // a diagonal survive. Otherwise the second icon's white overwrites the first's.
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
            drawIcon(c, icHome, l.homeCx, l.homeCy, sz * HOME_DRAW_SIZE_FACTOR, s.buttons and BTN_HOME != 0)
        }

        private fun drawTrackpad(
            c: Canvas,
            l: GamepadLayout,
            s: GamepadState,
        ) {
            if (trackpadMode == TrackpadMode.NONE) return
            val tp = l.trackpadRect ?: return
            val corner = tp.height() * TRACKPAD_CORNER_RADIUS_FRACTION
            c.drawRoundRect(tp, corner, corner, paintStickBg)
            paintStickRing.strokeWidth = TRACKPAD_OUTLINE_STROKE_DP * density
            c.drawRoundRect(tp, corner, corner, paintStickRing)
            lightbarColor?.let { color ->
                paintLightbar.color = color
                paintLightbar.strokeWidth = LIGHTBAR_STROKE_DP * density
                c.drawRoundRect(tp, corner, corner, paintLightbar)
            }
            if (trackpadClickFlash || s.buttons and BTN_TOUCHPAD_CLICK != 0) {
                c.drawRoundRect(tp, corner, corner, paintPressed)
            }
            if (trackpadMode != TrackpadMode.TOUCH) return
            val touch = recognizer.trackpadState
            if (touch.finger0Active) drawTrackpadFinger(c, tp, touch.finger0X, touch.finger0Y)
            if (touch.finger1Active) drawTrackpadFinger(c, tp, touch.finger1X, touch.finger1Y)
        }

        private fun drawTrackpadFinger(
            c: Canvas,
            tp: RectF,
            x: Short,
            y: Short,
        ) {
            val px = tp.left + ((x.toInt() + HALF_INT16).toFloat() / NORM_INT16_SPAN) * tp.width()
            val py = tp.top + ((y.toInt() + HALF_INT16).toFloat() / NORM_INT16_SPAN) * tp.height()
            c.drawCircle(px, py, TRACKPAD_FINGER_DOT_RADIUS_DP * density, paintStickThumbActive)
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
            drawTriggerRail(c, l.ltRect, icLT, s.leftTrigger)
            drawTriggerRail(c, l.rtRect, icRT, s.rightTrigger)
            if (leftTriggerEffect) drawTriggerEffectRing(c, l.ltRect)
            if (rightTriggerEffect) drawTriggerEffectRing(c, l.rtRect)
        }

        // Analog rail: a partial pull fills the rail from the bottom up to the
        // finger; a full pull (or a tap in the full zone) lights the whole pill
        // like any pressed button. The divider marks where full starts, in both
        // the pressed and idle states. Digital-trigger types keep the old
        // binary pill.
        private fun drawTriggerRail(
            c: Canvas,
            rect: RectF,
            d: Drawable?,
            value: Int,
        ) {
            if (!analogTriggers) {
                drawPillButton(c, rect, d, value > 0)
                return
            }
            val full = value >= GamepadConstants.TRIGGER_MAX
            drawPillButton(c, rect, d, full)
            val boundaryY = rect.top + rect.height() * GamepadConstants.TRIGGER_FULL_ZONE_FRACTION
            if (!full && value > 0) {
                val r = min(rect.width(), rect.height()) * PILL_CORNER_RADIUS_FRACTION
                val fillTop = rect.bottom - (rect.bottom - boundaryY) * (value / GamepadConstants.TRIGGER_MAX.toFloat())
                triggerClipPath.reset()
                triggerClipPath.addRoundRect(rect, r, r, Path.Direction.CW)
                c.save()
                c.clipPath(triggerClipPath)
                c.drawRect(rect.left, fillTop, rect.right, rect.bottom, paintTriggerFill)
                c.restore()
            }
            val inset = GamepadConstants.TRIGGER_ZONE_DIVIDER_INSET_DP * density
            paintTriggerDivider.strokeWidth = GamepadConstants.TRIGGER_ZONE_DIVIDER_STROKE_DP * density
            c.drawLine(rect.left + inset, boundaryY, rect.right - inset, boundaryY, paintTriggerDivider)
        }

        // An adaptive-trigger effect has no on-screen force to render, so the pill
        // gets an accent ring while the game holds a non-neutral effect.
        private fun drawTriggerEffectRing(
            c: Canvas,
            rect: RectF,
        ) {
            val r = min(rect.width(), rect.height()) * PILL_CORNER_RADIUS_FRACTION
            paintTriggerEffect.strokeWidth = TRIGGER_EFFECT_STROKE_DP * density
            c.drawRoundRect(rect, r, r, paintTriggerEffect)
        }

        // The DualSense's five microLEDs sit under the touchpad; skins without a
        // touch zone (Switch) anchor the row under the home button instead. Off
        // LEDs draw faintly so a partial mask reads as a position, not noise.
        private fun drawPlayerLeds(
            c: Canvas,
            l: GamepadLayout,
        ) {
            if (playerLedMask == 0) return
            val tp = l.trackpadRect
            val cy =
                if (tp != null) {
                    tp.bottom + PLAYER_LED_GAP_DP * density
                } else {
                    l.homeCy + l.smallBtnRadius + PLAYER_LED_GAP_DP * density
                }
            val cx = tp?.centerX() ?: l.homeCx
            val radius = PLAYER_LED_RADIUS_DP * density
            val pitch = radius * 2f + PLAYER_LED_PITCH_DP * density
            val count = PLAYER_LED_COUNT
            val startX = cx - pitch * (count - 1) / 2f
            for (i in 0 until count) {
                val on = playerLedMask and (1 shl i) != 0
                c.drawCircle(startX + pitch * i, cy, radius, if (on) paintLedOn else paintLedOff)
            }
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
            if (recognizer.consumeTrackpadDirty()) {
                listener?.onTrackpadStateChanged(recognizer.trackpadState)
            }
            recognizer.takePendingTrackpadTap()?.let(::pulseTrackpadClick)
            invalidate()
            return true
        }

        // A quick, still tap replays as a short press-and-release: the tap's own frames
        // carried touch-without-click, so the click is synthesised after the lift, held
        // one pulse so a report cadence as slow as the resend tick still sees it.
        private fun pulseTrackpadClick(tap: GamepadGestureRecognizer.TrackpadTap) {
            trackpadClickFlash = true
            listener?.onTrackpadStateChanged(
                TouchpadSurfaceView.TouchpadState(
                    finger0Active = true,
                    buttonPressed = true,
                    finger0TrackingId = tap.trackingId,
                    finger0X = tap.x,
                    finger0Y = tap.y,
                    eventTimeMs = tap.eventTimeMs,
                ),
            )
            postDelayed({
                trackpadClickFlash = false
                if (!recognizer.trackpadState.anyFingerDown()) {
                    listener?.onTrackpadStateChanged(
                        TouchpadSurfaceView.TouchpadState(eventTimeMs = tap.eventTimeMs + TRACKPAD_CLICK_PULSE_MS),
                    )
                }
                invalidate()
            }, TRACKPAD_CLICK_PULSE_MS)
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
