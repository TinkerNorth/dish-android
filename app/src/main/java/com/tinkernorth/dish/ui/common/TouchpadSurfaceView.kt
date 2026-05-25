// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

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
        }

        interface Listener {
            fun onTouchpadStateChanged(state: TouchpadState)

            fun onTouchActivityChanged(active: Boolean) = Unit
        }

        var listener: Listener? = null

        var clickWhenTouched: Boolean = true
            set(value) {
                field = value
                state.buttonPressed = value && state.anyFingerDown()
                if (state.anyFingerDown()) emit()
            }

        var accepting: Boolean = true
            set(value) {
                if (field == value) return
                field = value
                if (!value && state.anyFingerDown()) {
                    // Force a clean lift so the receiver doesn't get stuck with held bits when ownership is yanked mid-gesture.
                    slotForPointerId.clear()
                    state.finger0Active = false
                    state.finger1Active = false
                    state.finger0X = 0
                    state.finger0Y = 0
                    state.finger1X = 0
                    state.finger1Y = 0
                    state.buttonPressed = false
                    listener?.onTouchActivityChanged(false)
                    emit()
                }
                alpha = if (value) ACCEPTING_ALPHA else DIM_ALPHA
                invalidate()
            }

        private val state = TouchpadState()

        // Android pointer ids are stable across a single DOWN→UP but can be reused after
        // lift, so we map to our own slot 0/1 and keep it until UP.
        private val slotForPointerId = HashMap<Int, Int>(4)
        private var nextTrackingId: Int = 0

        private val bgPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(40, 0xFF, 0xFF, 0xFF)
                style = Paint.Style.FILL
            }
        private val outlinePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(180, 0x4F, 0xE3, 0xFF)
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
        private val fingerPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 0x4F, 0xE3, 0xFF)
                style = Paint.Style.FILL
            }
        private val labelPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 0xFF, 0xFF, 0xFF)
                textSize = 56f
                textAlign = Paint.Align.CENTER
            }
        private val hintPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(140, 0xFF, 0xFF, 0xFF)
                textSize = 28f
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
            // Pre-layout (width/height = 0) would normalise to int16 saturation and poison every subsequent delta.
            if (width <= 0 || height <= 0) return false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    // Opt out of vsync coalescing so the first MOVE's delta isn't a full
                    // input-frame larger than subsequent ones (cause of the first-touch jump).
                    requestUnbufferedDispatch(event)
                    val wasIdle = !state.anyFingerDown()
                    val index = event.actionIndex
                    val pointerId = event.getPointerId(index)
                    assignSlot(pointerId)
                    writePointerToState(event, index, pointerId)
                    updateButton()
                    if (wasIdle && state.anyFingerDown()) {
                        listener?.onTouchActivityChanged(true)
                    }
                    emit()
                }
                MotionEvent.ACTION_MOVE -> {
                    var changed = false
                    for (i in 0 until event.pointerCount) {
                        val pid = event.getPointerId(i)
                        if (slotForPointerId.containsKey(pid)) {
                            writePointerToState(event, i, pid)
                            changed = true
                        }
                    }
                    if (changed) emit()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    val index = event.actionIndex
                    val pointerId = event.getPointerId(index)
                    releaseSlot(pointerId)
                    updateButton()
                    emit()
                    if (!state.anyFingerDown()) {
                        listener?.onTouchActivityChanged(false)
                        if (event.actionMasked != MotionEvent.ACTION_CANCEL) {
                            performClick()
                        }
                    }
                }
                else -> return false
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private fun assignSlot(pointerId: Int) {
            if (slotForPointerId.containsKey(pointerId)) return
            val slot =
                when {
                    !state.finger0Active -> 0
                    !state.finger1Active -> 1
                    else -> return
                }
            slotForPointerId[pointerId] = slot
            // Bump tracking id per fresh contact: a stale id across a lift would smear cursor motion across the gap on the receiver.
            val id = (nextTrackingId++ and 0xFF)
            if (slot == 0) {
                state.finger0Active = true
                state.finger0TrackingId = id
            } else {
                state.finger1Active = true
                state.finger1TrackingId = id
            }
        }

        private fun releaseSlot(pointerId: Int) {
            val slot = slotForPointerId.remove(pointerId) ?: return
            if (slot == 0) {
                state.finger0Active = false
                state.finger0X = 0
                state.finger0Y = 0
            } else {
                state.finger1Active = false
                state.finger1X = 0
                state.finger1Y = 0
            }
        }

        private fun writePointerToState(
            event: MotionEvent,
            index: Int,
            pointerId: Int,
        ) {
            val slot = slotForPointerId[pointerId] ?: return
            val w = width.coerceAtLeast(1)
            val h = height.coerceAtLeast(1)
            // +Y down on the wire matches Android's +Y-down convention, so no flip.
            val xNorm =
                ((event.getX(index).coerceIn(0f, w.toFloat()) / w) * 65535f - 32768f)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            val yNorm =
                ((event.getY(index).coerceIn(0f, h.toFloat()) / h) * 65535f - 32768f)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            if (slot == 0) {
                state.finger0X = xNorm
                state.finger0Y = yNorm
            } else {
                state.finger1X = xNorm
                state.finger1Y = yNorm
            }
            state.eventTimeMs = event.eventTime
        }

        private fun updateButton() {
            state.buttonPressed = clickWhenTouched && state.anyFingerDown()
        }

        private fun emit() {
            listener?.onTouchpadStateChanged(state)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(8f, 8f, width - 8f, height - 8f, bgPaint)
            canvas.drawRect(8f, 8f, width - 8f, height - 8f, outlinePaint)

            val cx = width / 2f
            val labelY = height / 2f - 8f
            if (label.isNotEmpty()) canvas.drawText(label, cx, labelY, labelPaint)
            if (hint.isNotEmpty()) canvas.drawText(hint, cx, labelY + 44f, hintPaint)

            if (state.finger0Active) drawFinger(canvas, state.finger0X, state.finger0Y)
            if (state.finger1Active) drawFinger(canvas, state.finger1X, state.finger1Y)
        }

        private fun drawFinger(
            canvas: Canvas,
            x: Short,
            y: Short,
        ) {
            val px = ((x.toInt() + 32768).toFloat() / 65535f) * width
            val py = ((y.toInt() + 32768).toFloat() / 65535f) * height
            canvas.drawCircle(px, py, 32f, fingerPaint)
        }

        companion object {
            const val ACCEPTING_ALPHA: Float = 1.0f

            const val DIM_ALPHA: Float = 0.4f
        }
    }
