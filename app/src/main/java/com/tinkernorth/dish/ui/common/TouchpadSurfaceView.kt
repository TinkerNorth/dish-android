// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.connection.TouchpadReport

class TouchpadSurfaceView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        data class TouchpadState(
            var finger0Active: Boolean = false,
            var finger1Active: Boolean = false,
            var buttonPressed: Boolean = false,
            var finger0TrackingId: Int = 0,
            var finger0X: Short = 0,
            var finger0Y: Short = 0,
            var finger1TrackingId: Int = 0,
            var finger1X: Short = 0,
            var finger1Y: Short = 0,
            // Sensor sample timestamp; resends reuse the last value so the receiver can
            // detect duplicates by equality and time-scale relative deltas.
            var eventTimeMs: Long = 0L,
        ) {
            fun anyFingerDown(): Boolean = finger0Active || finger1Active

            // The wire frame for this surface state. The click and the mouse-mode fields are the
            // caller's: a pad surface sends its own click, the mouse surface its buttons and wheel.
            fun toReport(
                buttonPressed: Boolean,
                rightPressed: Boolean = false,
                middlePressed: Boolean = false,
                scrollDelta: Short = 0,
            ): TouchpadReport =
                TouchpadReport(
                    finger0Active = finger0Active,
                    finger1Active = finger1Active,
                    buttonPressed = buttonPressed,
                    rightPressed = rightPressed,
                    middlePressed = middlePressed,
                    finger0TrackingId = finger0TrackingId,
                    finger0X = finger0X,
                    finger0Y = finger0Y,
                    finger1TrackingId = finger1TrackingId,
                    finger1X = finger1X,
                    finger1Y = finger1Y,
                    eventTimeMs = eventTimeMs,
                    scrollDelta = scrollDelta,
                )
        }

        interface Listener {
            fun onTouchpadStateChanged(state: TouchpadState)

            fun onTouchActivityChanged(active: Boolean) = Unit
        }

        var listener: Listener? = null

        private val fingers = TouchpadFingerTracker()

        var clickWhenTouched: Boolean
            get() = fingers.clickWhenTouched
            set(value) {
                fingers.clickWhenTouched = value
                if (fingers.state.anyFingerDown()) emit()
            }

        var accepting: Boolean = true
            set(value) {
                if (field == value) return
                field = value
                if (!value && fingers.state.anyFingerDown()) {
                    // Force a clean lift so the receiver doesn't get stuck with held bits when ownership is yanked mid-gesture.
                    fingers.liftAll()
                    listener?.onTouchActivityChanged(false)
                    emit()
                }
                alpha = if (value) ACCEPTING_ALPHA else DIM_ALPHA
                invalidate()
            }

        private val inset = context.resources.getDimension(R.dimen.touchpad_surface_inset)
        private val labelLift = context.resources.getDimension(R.dimen.touchpad_surface_label_lift)
        private val hintOffset = context.resources.getDimension(R.dimen.touchpad_surface_hint_offset)
        private val fingerRadius = context.resources.getDimension(R.dimen.touchpad_surface_finger_radius)

        private val bgPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorSurfaceDim)
                style = Paint.Style.FILL
            }
        private val outlinePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorTouchpadOutline)
                style = Paint.Style.STROKE
                strokeWidth = context.resources.getDimension(R.dimen.touchpad_surface_outline_stroke)
            }
        private val fingerPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorTouchpadFinger)
                style = Paint.Style.FILL
            }
        private val labelPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorOnSurface)
                textSize = context.resources.getDimension(R.dimen.touchpad_surface_label_text)
                textAlign = Paint.Align.CENTER
            }
        private val hintPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorOnSurfaceVariant)
                textSize = context.resources.getDimension(R.dimen.touchpad_surface_hint_text)
                textAlign = Paint.Align.CENTER
            }

        var label: String = ""
            set(value) {
                field = value
                invalidate()
            }

        var hint: String = ""
            set(value) {
                field = value
                invalidate()
            }

        init {
            alpha = ACCEPTING_ALPHA
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!accepting) return false
            // Pre-layout (width/height = 0) would normalise to int16 saturation and poison every
            // subsequent delta.
            val laidOut = width > 0 && height > 0
            if (!laidOut) return false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> onFingerDown(event)
                MotionEvent.ACTION_MOVE -> onFingersMoved(event)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL ->
                    // performClick stays in onTouchEvent: the ClickableViewAccessibility check
                    // only recognises the call when it is here.
                    if (onFingerUp(event)) performClick()
                else -> return false
            }
            return true
        }

        private fun onFingerDown(event: MotionEvent) {
            // Opt out of vsync coalescing so the first MOVE's delta isn't a full input-frame
            // larger than subsequent ones (cause of the first-touch jump).
            requestUnbufferedDispatch(event)
            val index = event.actionIndex
            val pointerId = event.getPointerId(index)
            val firstFingerLanded =
                fingers.fingerDown(pointerId, event.getX(index), event.getY(index), width, height, event.eventTime)
            if (firstFingerLanded) listener?.onTouchActivityChanged(true)
            emit()
        }

        private fun onFingersMoved(event: MotionEvent) {
            var changed = false
            for (i in 0 until event.pointerCount) {
                val pointerId = event.getPointerId(i)
                val moved = fingers.fingerMoved(pointerId, event.getX(i), event.getY(i), width, height, event.eventTime)
                changed = changed || moved
            }
            if (changed) emit()
        }

        /** Answers whether that lift ended a tap the view should report as a click. */
        private fun onFingerUp(event: MotionEvent): Boolean {
            val pointerId = event.getPointerId(event.actionIndex)
            val lastFingerLifted = fingers.fingerUp(pointerId)
            emit()

            if (!lastFingerLifted) return false
            listener?.onTouchActivityChanged(false)
            return event.actionMasked != MotionEvent.ACTION_CANCEL
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private fun emit() {
            listener?.onTouchpadStateChanged(fingers.state)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(inset, inset, width - inset, height - inset, bgPaint)
            canvas.drawRect(inset, inset, width - inset, height - inset, outlinePaint)

            val cx = width / 2f
            val labelY = height / 2f - labelLift
            if (label.isNotEmpty()) canvas.drawText(label, cx, labelY, labelPaint)
            if (hint.isNotEmpty()) canvas.drawText(hint, cx, labelY + hintOffset, hintPaint)

            val state = fingers.state
            if (state.finger0Active) drawFinger(canvas, state.finger0X, state.finger0Y)
            if (state.finger1Active) drawFinger(canvas, state.finger1X, state.finger1Y)
        }

        private fun drawFinger(
            canvas: Canvas,
            x: Short,
            y: Short,
        ) {
            val px = ((x.toInt() + HALF_INT16).toFloat() / NORM_INT16_SPAN) * width
            val py = ((y.toInt() + HALF_INT16).toFloat() / NORM_INT16_SPAN) * height
            canvas.drawCircle(px, py, fingerRadius, fingerPaint)
        }

        companion object {
            const val ACCEPTING_ALPHA: Float = 1.0f

            const val DIM_ALPHA: Float = 0.4f
        }
    }
