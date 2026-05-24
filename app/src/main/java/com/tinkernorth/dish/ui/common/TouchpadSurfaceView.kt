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
 * Custom view that captures up to two simultaneous finger contacts on a
 * trackpad surface and emits a [TouchpadState] snapshot to its [Listener].
 *
 * **Click semantics — explicit, not heuristic.** The view's [clickWhenTouched]
 * property decides whether a finger contact also asserts the touchpad's
 * "click" button (the clicky-pad button on a DS4/DualSense, or the left
 * mouse button in MOUSE mode):
 *
 *   - [clickWhenTouched] `= true`  — every contact sets `state.buttonPressed`
 *     while the finger is down. Used for the "Click + Move" pad in the
 *     touchpad overlay, the one you drag for text selection or drag-and-drop.
 *   - [clickWhenTouched] `= false` — touches never assert the button.
 *     Used for the pure "Move" pad in the touchpad overlay, the one you
 *     drag for plain cursor navigation.
 *
 * The previous implementation used `event.getPressure() > 0.75f` as a
 * proxy for "the user is pressing hard enough to mean a click." Most
 * Android touchscreens report `pressure == 1.0f` for every contact (no
 * force sensor), so the heuristic collapsed to "every touch = button
 * held," which meant the trackpad couldn't be used for plain cursor
 * navigation without accidentally text-selecting or dragging windows.
 * Splitting into two surfaces gates the click on intent, not on a
 * proxy the hardware can't deliver.
 *
 * **Mutual exclusion across paired surfaces.** When two of these are
 * mounted side-by-side (the touchpad overlay's Click + Move pair), the
 * activity sets [accepting] `= false` on the inactive pad while the
 * active pad has a finger down. Touches on a non-accepting surface are
 * ignored at [onTouchEvent], the surface paints itself dim, and the
 * wire never sees a sample from the locked pad — the receiver only
 * tracks finger 0 in mouse mode, so two simultaneously emitting pads
 * would race.
 *
 * Coordinates emitted on the listener are pre-normalised int16
 * (`-32768..32767`) so the wire payload encoder doesn't have to rescale.
 * +Y is DOWN on the wire (matches the satellite convention and Android's
 * own +Y-down convention; no flip).
 *
 * Per-finger tracking IDs are monotonic per surface, wrap freely at 256
 * (the wire byte width); a new id is assigned each time a finger lands on
 * an empty slot. The receiver uses this id to decide whether to
 * interpolate cursor motion across the frame or treat a contact change as
 * a re-anchor.
 */
class TouchpadSurfaceView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
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
            /**
             * Android `MotionEvent.getEventTime()` of the most-recent position
             * write — i.e. the timestamp at which the current finger
             * coordinates were sampled by the touch sensor. The wire packet
             * carries this so the satellite can compute dt between consecutive
             * samples and time-scale the relative-mouse delta (fixes the
             * first-touch-jump that happens because Android delivers the
             * first MOVE event up to a full input-frame after touchdown).
             *
             * Resends carry the SAME value as the last position write — when
             * the satellite sees two consecutive samples with identical
             * eventTimeMs it treats them as duplicate (dx=0). Position
             * changes always bump this field via [writePointerToState].
             */
            var eventTimeMs: Long = 0L,
        ) {
            /** True iff at least one finger is currently in contact with this surface. */
            fun anyFingerDown(): Boolean = finger0Active || finger1Active
        }

        interface Listener {
            /**
             * Fires on every state change — touch down, move, lift. The
             * receiving activity is expected to (a) cache the snapshot for
             * its resend loop and (b) decide whether to forward it on the
             * wire (e.g. only the active pad of a mutually-exclusive pair).
             */
            fun onTouchpadStateChanged(state: TouchpadState)

            /**
             * Fires when this surface transitions from "no fingers" to "any
             * fingers down" (`active=true`) or back (`active=false`). The
             * activity uses this to flip the mutual-exclusion lock without
             * having to inspect each state change.
             */
            fun onTouchActivityChanged(active: Boolean) = Unit
        }

        var listener: Listener? = null

        /**
         * Whether a finger contact on this surface also asserts the
         * touchpad's clicky-pad button (mouse button 1 in MOUSE mode, the
         * DS4 trackpad-click in PAD mode). See the class docstring for the
         * rationale behind making this an explicit per-surface property
         * instead of the previous pressure heuristic.
         */
        var clickWhenTouched: Boolean = true
            set(value) {
                field = value
                // Live update: if the user toggles this while a finger is
                // already down (e.g. via the in-app picker), the next emit
                // should reflect the new mode. Recompute and republish.
                state.buttonPressed = value && state.anyFingerDown()
                if (state.anyFingerDown()) emit()
            }

        /**
         * When `false`, the surface ignores every touch event and paints a
         * dim veil so the user can see they're locked out. The paired-pad
         * coordinator (in the overlay activity) flips this so only one of
         * two side-by-side surfaces is live at a time — preventing both
         * pads from racing to write `finger0` on the wire, which only one
         * can usefully own.
         *
         * Toggling this back to `true` while a finger is down has no
         * effect — Android won't synthesise a new ACTION_DOWN for an
         * already-tracking pointer. Toggling it to `false` while a finger
         * is down forces an immediate release (so the receiver gets a
         * clean lift signal and the click button drops if it was set).
         */
        var accepting: Boolean = true
            set(value) {
                if (field == value) return
                field = value
                if (!value && state.anyFingerDown()) {
                    // Yank: clear all touch state, fire one final emit so
                    // the receiver sees the lift, and toggle the
                    // activity-changed callback off.
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

        /**
         * Big centred label drawn on the surface so the user knows which pad
         * does what without leaving the overlay. Activity sets this from a
         * string resource after inflation (e.g. "Click + Move", "Move").
         */
        var label: String = ""
            set(value) {
                field = value
                invalidate()
            }

        /**
         * One-line hint under the label (e.g. "Drag to click and move",
         * "Drag to move cursor"). Optional — empty string hides it.
         */
        var hint: String = ""
            set(value) {
                field = value
                invalidate()
            }

        init {
            alpha = ACCEPTING_ALPHA
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // Dim/locked surface — pass the event back up so the activity can
            // route it elsewhere or let the OS handle it. Returning false on
            // ACTION_DOWN means we won't see any subsequent events for this
            // gesture, which is the desired "locked out" behaviour.
            if (!accepting) return false
            // Defensive: a touch arriving before the view has been laid out
            // (width/height = 0) would normalise to int16 saturation in
            // writePointerToState, sending a bogus first position to the
            // receiver and poisoning every subsequent delta. Reject the
            // gesture; the user's next touch (post-layout) starts fresh.
            if (width <= 0 || height <= 0) return false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    // Opt this gesture out of the Android input dispatcher's
                    // default vsync coalescing. Without this, MOVE events
                    // for this gesture are batched to display refresh rate
                    // (~16 ms on 60 Hz) — and the FIRST MOVE's delta
                    // therefore covers a full input-frame of finger travel,
                    // ~4× larger than every subsequent ~4 ms-per-frame
                    // delta. The receiver's relative-mouse integration
                    // turns that into the visible first-touch jump.
                    //
                    // Unbuffered dispatch tells the system to deliver each
                    // touch sensor sample as it arrives (~4–8 ms on most
                    // modern phones), so the first MOVE's delta is the same
                    // size as every other MOVE's delta and the cursor moves
                    // uniformly from the start. Same trick stylus drawing
                    // apps use for low-latency ink. Added in API 21.
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
                        // performClick() satisfies the View accessibility
                        // contract — when an a11y service (TalkBack) routes
                        // a tap through the accessibility shadow, View.dispatch
                        // invokes performClick() and our override returns
                        // true so the service knows the tap was handled.
                        // The actual touch-driven gesture state is already
                        // settled above (releaseSlot + emit); performClick
                        // is the per-gesture commit notification, called
                        // once after the last finger lifts.
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
            // Capture the sensor timestamp of THIS sample. Resends will reuse
            // it (cached state, no fresh write); the receiver uses the
            // dt between consecutive samples to time-scale the cursor delta
            // so the first MOVE event after touchdown (which Android
            // delivers up to a full input-frame later) doesn't produce a
            // visibly larger cursor jump than subsequent per-frame samples.
            state.eventTimeMs = event.eventTime
        }

        /**
         * Recompute [TouchpadState.buttonPressed] from the surface's mode and
         * the live finger state. Called whenever finger state changes; the
         * click is asserted iff this surface is in "click" mode AND a finger
         * is currently down.
         */
        private fun updateButton() {
            state.buttonPressed = clickWhenTouched && state.anyFingerDown()
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

            // Centred label + hint. Drawn before the finger dots so an
            // active touch visually sits on top of the label.
            val cx = width / 2f
            val labelY = height / 2f - 8f
            if (label.isNotEmpty()) canvas.drawText(label, cx, labelY, labelPaint)
            if (hint.isNotEmpty()) canvas.drawText(hint, cx, labelY + 44f, hintPaint)

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
            /**
             * Full opacity when the surface is the active pad of a pair (or
             * when it's mounted alone). 1.0 is intentional — even a small
             * dim on the active pad reads as "broken" because the user is
             * actively interacting with it.
             */
            const val ACCEPTING_ALPHA: Float = 1.0f

            /**
             * Reduced opacity when the surface is locked out by its pair.
             * 0.4 is dim enough to read as "not the one in use" but bright
             * enough that the label is still legible — the user can still
             * see what the locked pad would do, so the choice between
             * pads stays obvious mid-session.
             */
            const val DIM_ALPHA: Float = 0.4f
        }
    }
