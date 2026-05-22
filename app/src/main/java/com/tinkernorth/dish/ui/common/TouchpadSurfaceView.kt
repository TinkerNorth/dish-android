// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * Custom view that captures up to two simultaneous finger contacts and a
 * tap-to-click on the surface, mirroring the DS4 / DualSense touchpad.
 *
 * Two routing modes change *only* the visual styling and on-screen hint;
 * the wire payload (`MSG_TOUCHPAD`) is identical regardless. The receiver
 * applies its configured `touchpadMode` to decide whether to feed a
 * virtual DS4 surface, synthesize a relative mouse pointer, or drop the
 * sample. See `satellite/src/core/session_service.cpp::handleTouchpadData`.
 *
 *   [Mode.Pad]   — the surface is drawn as a stylised DS4 touchpad. Best
 *                  when the receiver's mode is set to "Pad".
 *   [Mode.Mouse] — the surface is drawn as a generic trackpad with a
 *                  cursor-style cue. Best when the receiver's mode is set
 *                  to "Mouse".
 *
 * The view never decides routing — the user picks on the connection card
 * (the satellite advertises which modes it supports, the client pushes the
 * selection). This view's only job is honest signalling about *what the
 * sample will do downstream*, and lossless capture of the touches.
 *
 * Coordinates emitted on the listener are pre-normalized int16
 * (`-32768..32767`) so the wire payload encoder doesn't have to rescale.
 * Y is flipped to "down is positive" before normalisation — matches the
 * satellite wire convention.
 *
 * Per-finger tracking IDs are monotonic per controller, wrap freely; a new
 * id is assigned each time a finger lands on an empty slot. Continuous-
 * contact frames share an id; lift + re-touch bumps it. The receiver uses
 * this id to decide whether to interpolate cursor motion across the
 * frame.
 */
class TouchpadSurfaceView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        enum class Mode { Pad, Mouse }

        /**
         * Snapshot of the live touchpad state. The view mutates a single
         * instance in place; the resend loop reads fields directly. Fields
         * are individually `@Volatile`-safe under reader-only access from
         * another thread — a torn snapshot only impacts one tick's
         * accuracy, which the next tick corrects (the same trade-off
         * `GamepadTouchView.GamepadState` makes).
         */
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
        )

        interface Listener {
            fun onTouchpadStateChanged(state: TouchpadState)
        }

        var listener: Listener? = null

        var mode: Mode = Mode.Pad
            set(value) {
                field = value
                invalidate()
            }

        private val state = TouchpadState()

        // Pointer-id-to-slot mapping. Android pointer ids are stable across a
        // single contact (DOWN → UP), but can be reused after lift. We assign
        // each new contact to slot 0 or slot 1 and keep the mapping until UP.
        private val slotForPointerId = HashMap<Int, Int>(4)
        private var nextTrackingId: Int = 0

        // Paints used for the surface visualization. Mode-specific styling lives
        // in [onDraw]; allocations stay constructor-time so the input path is
        // allocation-free.
        private val bgPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(40, 0xFF, 0xFF, 0xFF) // subtle on dark theme
                style = Paint.Style.FILL
            }
        private val outlinePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(180, 0x4F, 0xE3, 0xFF) // colorPrimary-ish
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
        private val fingerPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 0x4F, 0xE3, 0xFF)
                style = Paint.Style.FILL
            }
        private val hintPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(140, 0xFF, 0xFF, 0xFF)
                textSize = 36f
                textAlign = Paint.Align.CENTER
            }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // Same dispatch table as `GamepadTouchView` — handle every
            // multi-touch action that introduces or retires a pointer, plus
            // ACTION_MOVE for in-flight tracking. Fall through to `false` on
            // anything else so the gesture dispatcher can still drive a
            // long-press for the button affordance.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val index = event.actionIndex
                    val pointerId = event.getPointerId(index)
                    assignSlot(pointerId)
                    writePointerToState(event, index, pointerId)
                    updateButton(event)
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
                    updateButton(event)
                    emit()
                }
                else -> return false
            }
            return true
        }

        private fun assignSlot(pointerId: Int) {
            if (slotForPointerId.containsKey(pointerId)) return
            val slot =
                when {
                    !state.finger0Active -> 0
                    !state.finger1Active -> 1
                    else -> return // surface full — ignore further touches
                }
            slotForPointerId[pointerId] = slot
            // Bump tracking id on each fresh contact: the receiver compares
            // ids frame-to-frame to decide whether to interpolate; a stale
            // id across a lift would smear cursor motion across the gap.
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
            // Normalise to int16 (-32768..32767). +Y down on the wire (matches
            // the satellite convention; Android +Y is also down, no flip
            // needed). Clamp to view bounds before mapping so a small over-
            // shoot off the left edge doesn't go +32767.
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
        }

        private fun updateButton(event: MotionEvent) {
            // The DS4 / DualSense touchpad has a single clicky surface — we
            // model "button pressed" as: any finger is currently down with a
            // forceful press (`pressure > 0.75f`) OR the user has invoked
            // the on-screen click affordance (long-press triggers it via
            // dispatcher; for now we keep it simple and tie to high-pressure
            // contact only). Most touchscreens report `pressure == 1.0f` so
            // this defaults to "button held while touching" — close enough
            // to a DS4 hold-and-drag for now; future work can split this
            // out behind a button surface like GamepadTouchView's L3/R3.
            var anyPressed = false
            for (i in 0 until event.pointerCount) {
                if (event.getPressure(i) > BUTTON_PRESSURE_THRESHOLD) {
                    anyPressed = true
                    break
                }
            }
            state.buttonPressed = anyPressed
        }

        private fun emit() {
            listener?.onTouchpadStateChanged(state)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Surface fill + outline — same regardless of mode.
            canvas.drawRect(8f, 8f, width - 8f, height - 8f, bgPaint)
            canvas.drawRect(8f, 8f, width - 8f, height - 8f, outlinePaint)

            // Mode label hint — only difference between Pad and Mouse visuals
            // for v1. A future iteration can show cursor trails for Mouse and
            // a stylised DS4 touchpad layout for Pad.
            val hint =
                when (mode) {
                    Mode.Pad -> "PAD — touchpad → virtual DS4"
                    Mode.Mouse -> "MOUSE — touchpad → host pointer"
                }
            canvas.drawText(hint, width / 2f, 60f, hintPaint)

            // Draw any active fingers.
            if (state.finger0Active) drawFinger(canvas, state.finger0X, state.finger0Y)
            if (state.finger1Active) drawFinger(canvas, state.finger1X, state.finger1Y)
        }

        private fun drawFinger(
            canvas: Canvas,
            x: Short,
            y: Short,
        ) {
            // Reverse the int16 normalisation to draw the dot at the right pixel.
            val px = ((x.toInt() + 32768).toFloat() / 65535f) * width
            val py = ((y.toInt() + 32768).toFloat() / 65535f) * height
            canvas.drawCircle(px, py, 32f, fingerPaint)
        }

        companion object {
            // Most capacitive touchscreens normalise pressure to 1.0f; a
            // dedicated stylus or force-touch panel might report fractional
            // values. The threshold is set high enough that a regular tap
            // does not accidentally trigger the click button on phones that
            // do report fractional pressure.
            private const val BUTTON_PRESSURE_THRESHOLD = 0.75f
        }
    }
