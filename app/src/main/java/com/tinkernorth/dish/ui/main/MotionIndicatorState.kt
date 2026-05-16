// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R

/**
 * The phone-motion state shown by the touch-overlay's motion pill.
 *
 * The touch overlay forwards the phone's gyro + accelerometer as an
 * `MSG_MOTION` stream (Task 1.1, see
 * [com.tinkernorth.dish.data.network.PhoneMotionSource]). The user must be
 * able to tell — with no ambiguity — whether motion is actually going out:
 *
 *  - [STREAMING]      the phone has a gyroscope, the overlay is resumed, and
 *                     the bound connection is a satellite connection (the
 *                     only kind that carries `MSG_MOTION`), so
 *                     [com.tinkernorth.dish.data.network.PhoneMotionSource]
 *                     is started and samples are being forwarded.
 *  - [PAUSED]         the phone has a gyroscope but the source is stopped
 *                     (overlay backgrounded — gyro listeners are released to
 *                     save battery). Distinct from [UNAVAILABLE]: the hardware
 *                     is there, it is just idle right now.
 *  - [NOT_FORWARDED]  the phone has a gyroscope but the bound connection is a
 *                     Bluetooth-HID connection, which has no motion channel —
 *                     so motion is captured-but-dropped, never sent. Honest
 *                     about the limit instead of falsely claiming "streaming".
 *  - [UNAVAILABLE]    the phone has no gyroscope
 *                     ([com.tinkernorth.dish.data.network.PhoneMotionSource.isAvailable]
 *                     is false); nothing motion-related will ever be sent.
 *
 * There is no user-facing motion on/off toggle in this slice, so "off"
 * collapses onto [PAUSED] (a lifecycle pause), never onto [UNAVAILABLE].
 * Keeping "no hardware", "paused", and "this connection can't carry motion"
 * apart is the whole point of this enum.
 */
enum class MotionIndicatorState(
    @param:StringRes val labelRes: Int,
    @param:ColorRes val dotColorRes: Int,
) {
    STREAMING(R.string.motion_streaming, R.color.colorSuccess),
    PAUSED(R.string.motion_paused, R.color.colorWarning),
    NOT_FORWARDED(R.string.motion_not_forwarded, R.color.colorMuted),
    UNAVAILABLE(R.string.motion_unavailable, R.color.colorMuted),
    ;

    /** True for the states whose meaning warrants the one-line explanation. */
    val hasDetail: Boolean get() = this == UNAVAILABLE || this == NOT_FORWARDED

    companion object {
        /**
         * Pure mapping from the three facts the overlay knows to the
         * indicator state:
         *
         *  - [isAvailable]   — whether the phone has a gyroscope
         *    ([com.tinkernorth.dish.data.network.PhoneMotionSource.isAvailable]).
         *  - [isStreaming]   — whether the source is currently started
         *    ([com.tinkernorth.dish.data.network.PhoneMotionSource.isStreaming]).
         *  - [connectionCarriesMotion] — whether the bound connection kind
         *    has an `MSG_MOTION` channel (satellite does, Bluetooth-HID does
         *    not).
         *
         * Precedence: no gyroscope wins outright ([UNAVAILABLE]); then a
         * connection that can't carry motion ([NOT_FORWARDED]) — the source
         * may be "started" but its emits are dropped, so this must not read
         * as [STREAMING]; then the lifecycle [isStreaming] flag.
         */
        fun of(
            isAvailable: Boolean,
            isStreaming: Boolean,
            connectionCarriesMotion: Boolean,
        ): MotionIndicatorState =
            when {
                !isAvailable -> UNAVAILABLE
                !connectionCarriesMotion -> NOT_FORWARDED
                isStreaming -> STREAMING
                else -> PAUSED
            }
    }
}
