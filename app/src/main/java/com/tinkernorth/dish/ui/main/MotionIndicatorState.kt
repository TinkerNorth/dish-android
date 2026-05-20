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
 *                     only kind that carries `MSG_MOTION`) that is actually
 *                     CONNECTED — so
 *                     [com.tinkernorth.dish.data.network.PhoneMotionSource]
 *                     is started and samples are reaching the wire.
 *  - [PAUSED]         the phone has a gyroscope but motion is not flowing
 *                     right now: either the source is stopped (overlay
 *                     backgrounded — gyro listeners released to save battery),
 *                     or it is running but the satellite connection is not
 *                     CONNECTED, so the samples are dropped before the wire.
 *                     Distinct from [UNAVAILABLE]: the hardware is there, it
 *                     is just idle right now.
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

    /**
     * Source is started, connection is up, but no gyro samples have arrived
     * in the stall window. The hardware exists but isn't ticking — e.g. an
     * OEM that pauses sensors aggressively, or a defective gyro. Surfaced as
     * the "paused (stalled)" detail rather than a separate enum case so the
     * pill colour stays warning-amber.
     */
    STALLED(R.string.motion_stalled, R.color.colorWarning),
    NOT_FORWARDED(R.string.motion_not_forwarded, R.color.colorMuted),
    UNAVAILABLE(R.string.motion_unavailable, R.color.colorMuted),
    ;

    /** True for the states whose meaning warrants the one-line explanation. */
    val hasDetail: Boolean get() = this == UNAVAILABLE || this == NOT_FORWARDED || this == STALLED

    companion object {
        /**
         * Pure mapping from the four facts the overlay knows to the indicator
         * state:
         *
         *  - [isAvailable]   — whether the phone has a gyroscope
         *    ([com.tinkernorth.dish.data.network.PhoneMotionSource.isAvailable]).
         *  - [isStreaming]   — whether the source is currently started
         *    ([com.tinkernorth.dish.data.network.PhoneMotionSource.isStreaming]).
         *  - [connectionCarriesMotion] — whether the bound connection kind
         *    has an `MSG_MOTION` channel (satellite does, Bluetooth-HID does
         *    not).
         *  - [connectionConnected] — whether the bound connection is actually
         *    CONNECTED. A satellite connection that is reconnecting or idle
         *    has `sendMotion` drop every packet, so a "started" source over a
         *    down connection is not really streaming.
         *
         * Precedence: no gyroscope wins outright ([UNAVAILABLE]); then a
         * connection that can't carry motion ([NOT_FORWARDED]) — the source
         * may be "started" but its emits are dropped, so this must not read
         * as [STREAMING]; then [STREAMING] only when the source is started
         * **and** the connection is up; otherwise [PAUSED].
         */
        fun of(
            isAvailable: Boolean,
            isStreaming: Boolean,
            connectionCarriesMotion: Boolean,
            connectionConnected: Boolean,
            isStalled: Boolean = false,
        ): MotionIndicatorState =
            when {
                !isAvailable -> UNAVAILABLE
                !connectionCarriesMotion -> NOT_FORWARDED
                isStreaming && connectionConnected && isStalled -> STALLED
                isStreaming && connectionConnected -> STREAMING
                else -> PAUSED
            }
    }
}
