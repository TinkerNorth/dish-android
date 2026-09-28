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

            // Takes every field of [other] in place, for a holder that must not allocate per frame.
            fun copyFrom(other: TouchpadState) {
                finger0Active = other.finger0Active
                finger1Active = other.finger1Active
                buttonPressed = other.buttonPressed
                finger0TrackingId = other.finger0TrackingId
                finger0X = other.finger0X
                finger0Y = other.finger0Y
                finger1TrackingId = other.finger1TrackingId
                finger1X = other.finger1X
                finger1Y = other.finger1Y
                eventTimeMs = other.eventTimeMs
            }
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

        private val bgPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorSurfaceDim)
                style = Paint.Style.FILL
            }
        private val outlinePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorTouchpadOutline)
                style = Paint.Style.STROKE
                strokeWidth = OUTLINE_STROKE_PX
            }
        private val fingerPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorTouchpadFinger)
                style = Paint.Style.FILL
            }
        private val labelPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorOnSurface)
                textSize = LABEL_TEXT_PX
                textAlign = Paint.Align.CENTER
            }
        private val hintPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorOnSurfaceVariant)
                textSize = HINT_TEXT_PX
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

        /** Answers whether that lift ended a gesture the view should report as a click. */
        private fun onFingerUp(event: MotionEvent): Boolean {
            val pointerId = event.getPointerId(event.actionIndex)
            val lift = fingers.fingerLifted(event.actionMasked, pointerId)
            emit()
            val gestureEnded = lift != TouchpadLift.FINGERS_REMAIN
            if (gestureEnded) listener?.onTouchActivityChanged(false)
            return lift == TouchpadLift.LAST_FINGER_LIFTED
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
            canvas.drawRect(INSET_PX, INSET_PX, width - INSET_PX, height - INSET_PX, bgPaint)
            canvas.drawRect(INSET_PX, INSET_PX, width - INSET_PX, height - INSET_PX, outlinePaint)

            val cx = width / 2f
            val labelY = height / 2f - LABEL_LIFT_PX
            if (label.isNotEmpty()) canvas.drawText(label, cx, labelY, labelPaint)
            if (hint.isNotEmpty()) canvas.drawText(hint, cx, labelY + HINT_OFFSET_PX, hintPaint)

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
            canvas.drawCircle(px, py, FINGER_RADIUS_PX, fingerPaint)
        }

        companion object {
            const val ACCEPTING_ALPHA: Float = 1.0f

            const val DIM_ALPHA: Float = 0.4f

            // Raw pixels, as this view has always drawn them. A @dimen would scale them with
            // density, which is a visual change to make with a device in hand, and lint refuses
            // a px token, so the sizes stay here as the view's own drawing constants.
            private const val INSET_PX: Float = 8f
            private const val OUTLINE_STROKE_PX: Float = 4f
            private const val LABEL_TEXT_PX: Float = 56f
            private const val HINT_TEXT_PX: Float = 28f
            private const val LABEL_LIFT_PX: Float = 8f
            private const val HINT_OFFSET_PX: Float = 44f
            private const val FINGER_RADIUS_PX: Float = 32f
        }
    }
