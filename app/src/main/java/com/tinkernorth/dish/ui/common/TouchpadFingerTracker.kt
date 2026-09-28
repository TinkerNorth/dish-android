// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import android.view.MotionEvent

// What one lift did to the gesture: a finger is still down, the last one left, or the platform
// took the whole gesture away.
internal enum class TouchpadLift {
    FINGERS_REMAIN,
    LAST_FINGER_LIFTED,
    CANCELLED,
}

// The on-screen touchpad's two finger slots, kept off the View so the slot bookkeeping and the
// int16 normalisation are pinned on the JVM. Android pointer ids are stable across one DOWN..UP but
// can be reused after a lift, so each maps to a slot of its own and keeps it until its UP.
internal class TouchpadFingerTracker {
    val state = TouchpadSurfaceView.TouchpadState()

    // A finger on the surface presses the pad click as well; off, the click is the caller's.
    var clickWhenTouched: Boolean = true
        set(value) {
            field = value
            refreshClick()
        }

    private val slotForPointerId = HashMap<Int, Int>(POINTER_MAP_CAPACITY)
    private var nextTrackingId = 0

    /** True when this finger is the first on the surface. */
    fun fingerDown(
        pointerId: Int,
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        eventTimeMs: Long,
    ): Boolean {
        val wasIdle = !state.anyFingerDown()
        assignSlot(pointerId)
        writePointer(pointerId, x, y, width, height, eventTimeMs)
        refreshClick()
        return wasIdle && state.anyFingerDown()
    }

    /** True when the pointer holds a slot, so the frame changed. */
    fun fingerMoved(
        pointerId: Int,
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        eventTimeMs: Long,
    ): Boolean {
        val isOneOfOurs = slotForPointerId.containsKey(pointerId)
        if (!isOneOfOurs) return false
        writePointer(pointerId, x, y, width, height, eventTimeMs)
        return true
    }

    /**
     * An UP, a POINTER_UP or a CANCEL, as the view receives it. A CANCEL names one pointer but
     * ends the whole gesture, so it lifts every finger and the click, not only that pointer's.
     */
    fun fingerLifted(
        actionMasked: Int,
        pointerId: Int,
    ): TouchpadLift =
        if (actionMasked == MotionEvent.ACTION_CANCEL) cancelGesture() else liftFinger(pointerId)

    private fun cancelGesture(): TouchpadLift {
        liftAll()
        return TouchpadLift.CANCELLED
    }

    private fun liftFinger(pointerId: Int): TouchpadLift {
        val surfaceClear = fingerUp(pointerId)
        return if (surfaceClear) TouchpadLift.LAST_FINGER_LIFTED else TouchpadLift.FINGERS_REMAIN
    }

    /** True when the surface is clear after this lift. */
    fun fingerUp(pointerId: Int): Boolean {
        releaseSlot(pointerId)
        refreshClick()
        return !state.anyFingerDown()
    }

    /** One clean lift of every finger and the click, for ownership yanked mid-gesture. */
    fun liftAll() {
        slotForPointerId.clear()
        state.finger0Active = false
        state.finger1Active = false
        state.finger0X = 0
        state.finger0Y = 0
        state.finger1X = 0
        state.finger1Y = 0
        state.buttonPressed = false
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
        val id = (nextTrackingId++ and TRACKING_ID_WRAP_MASK)
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

    private fun writePointer(
        pointerId: Int,
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        eventTimeMs: Long,
    ) {
        val slot = slotForPointerId[pointerId] ?: return
        // +Y down on the wire matches Android's +Y-down convention, so no flip.
        val xNorm = spanToWire(x, width.toFloat())
        val yNorm = spanToWire(y, height.toFloat())
        if (slot == 0) {
            state.finger0X = xNorm
            state.finger0Y = yNorm
        } else {
            state.finger1X = xNorm
            state.finger1Y = yNorm
        }
        state.eventTimeMs = eventTimeMs
    }

    private fun refreshClick() {
        state.buttonPressed = clickWhenTouched && state.anyFingerDown()
    }

    private companion object {
        const val POINTER_MAP_CAPACITY = 4
    }
}
