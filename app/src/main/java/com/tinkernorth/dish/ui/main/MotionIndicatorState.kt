// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.sensor.PhoneMotionSource

/**
 * The phone-motion state shown by the touch-overlay's motion pill.
 *
 * The touch overlay forwards the phone's gyro + accelerometer as an
 * `MSG_MOTION` stream (Task 1.1, see [PhoneMotionSource]). The user must
 * be able to tell — with **no ambiguity** — why motion is or isn't going
 * out. Every "not sent" reason is a distinct enum value so the pill can
 * point the user at the right next step (enable the toggle, swap to PS,
 * switch to satellite, etc.) instead of a single vague "paused."
 *
 *  - [STREAMING]       gyro + satellite + connected + user-enabled — samples on the wire.
 *  - [STALLED]         started but no recent samples (OEM sensor pause, defective gyro).
 *  - [PAUSED]          hardware ready, but the source is stopped for lifecycle / link reasons.
 *  - [USER_DISABLED]   the user toggled motion off for this slot. Distinct from PAUSED —
 *                      the limit is a choice the user made, with a clear path back.
 *  - [NOT_FORWARDED]   bound connection is Bluetooth-HID, which has no `MSG_MOTION`
 *                      channel. Honest about the limit instead of falsely streaming.
 *  - [NO_HOST_SINK]    the slot's controller type has no IMU surface on the host's
 *                      backend (Xbox 360 virtual pad). Tells the user to switch the
 *                      slot to PlayStation if they want gyro to land.
 *  - [BACKEND_BROKEN]  the satellite's backend told us (via the motion-status byte on
 *                      the controller-add ACK) that it accepted the controller but
 *                      could *not* create the per-serial IMU sink — typically a Linux
 *                      uinput kernel rejection. Distinguishes "your bytes go nowhere
 *                      because the receiver is misconfigured" from "your bytes go
 *                      somewhere and the game just hasn't subscribed yet."
 *  - [UNAVAILABLE]     the phone itself has no gyroscope — nothing motion-related will
 *                      ever be sent regardless of toggles or connections.
 *
 * Keeping these eight cases distinct is the whole point of the enum.
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
     * OEM that pauses sensors aggressively, or a defective gyro.
     */
    STALLED(R.string.motion_stalled, R.color.colorWarning),

    /**
     * The user turned motion off for this slot. Distinct from PAUSED so
     * the user sees "you turned this off" with a one-line hint at the
     * switch, not a vague "paused" label that looks like a bug.
     */
    USER_DISABLED(R.string.motion_user_disabled, R.color.colorMuted),

    NOT_FORWARDED(R.string.motion_not_forwarded, R.color.colorMuted),

    /**
     * The slot's controller type (Xbox) has no motion surface on the
     * host's backend. The dish would otherwise stream — wire bytes would
     * be accepted, then silently dropped at the virtual gamepad layer.
     * Pill calls this out so the user knows to switch to PlayStation for
     * gyro to land. macOS satellites (no backend at all) never reach
     * this state because controller-add fails with `ACK_ERR_BACKEND_UNAVAIL`
     * before the slot becomes registered.
     */
    NO_HOST_SINK(R.string.motion_no_host_sink, R.color.colorMuted),

    /**
     * The receiver explicitly told us (via the motion-status byte on the
     * controller-add ACK — see `ACK_MOTION_FLAG_BACKEND_OK` on the
     * satellite) that it accepted the controller but its backend could
     * NOT create the per-serial IMU sink. Distinct from [NO_HOST_SINK]
     * (which is type-level — the backend would never sink motion for
     * this kind of pad) and from a missing ACK / pre-extension satellite
     * (which surfaces as STREAMING / STALLED / PAUSED instead). The
     * fix is operator-side on the satellite (typically a kernel /
     * `/dev/uinput` permission issue on Linux), so the pill leans
     * informational rather than action-oriented.
     */
    BACKEND_BROKEN(R.string.motion_backend_broken, R.color.colorWarning),

    UNAVAILABLE(R.string.motion_unavailable, R.color.colorMuted),
    ;

    /** True for the states whose meaning warrants the one-line explanation. */
    val hasDetail: Boolean
        get() =
            this == UNAVAILABLE ||
                this == NOT_FORWARDED ||
                this == STALLED ||
                this == USER_DISABLED ||
                this == NO_HOST_SINK ||
                this == BACKEND_BROKEN

    companion object {
        /**
         * Pure decision from the facts the overlay knows to the pill state.
         *
         *  - [isAvailable]   — phone has a gyroscope (`PhoneMotionSource.isAvailable`).
         *  - [userEnabled]   — user's per-slot motion toggle (`MotionEnabledStore`).
         *  - [connectionCarriesMotion] — bound connection kind has `MSG_MOTION`
         *    (satellite yes, Bluetooth-HID no).
         *  - [hostHasSinkForType] — the slot's controller type has an IMU surface
         *    on the receiving host's backend (the dish-side heuristic: PS=yes,
         *    Xbox=no — universally true for the shipping backends).
         *  - [satelliteBackendOk] — the satellite's *own* answer about whether its
         *    backend created the per-serial IMU sink. Null when no extended ACK
         *    has been observed (pre-extension satellite, or pre-registration).
         *    A null here is the "defer to the heuristic" signal; a non-null wins.
         *  - [isStreaming]   — the source is currently started.
         *  - [connectionConnected] — the bound connection is Connected (not just
         *    Connecting / Unstable).
         *  - [isStalled]     — the source is started but no gyro callback in the
         *    last 1500ms.
         *
         * Precedence (top to bottom — first match wins):
         *   1. No gyroscope → UNAVAILABLE
         *   2. User toggled off → USER_DISABLED (user's choice gets a clear label)
         *   3. Bluetooth-HID → NOT_FORWARDED (link can't carry motion)
         *   4. Host backend has no sink for type → NO_HOST_SINK
         *   5. Satellite reported backend NOT ok → BACKEND_BROKEN
         *   6. Streaming + connected + stalled → STALLED
         *   7. Streaming + connected → STREAMING
         *   8. Otherwise → PAUSED
         *
         * The order is deliberate.
         *
         * USER_DISABLED comes BEFORE NOT_FORWARDED so a user who has toggled off
         * on a BT connection sees "you turned this off" (actionable) rather than
         * "this connection can't carry motion" (also true but less actionable in
         * the moment).
         *
         * NO_HOST_SINK comes BEFORE BACKEND_BROKEN because the type-level fact
         * ("Xbox virtual pads can never sink motion") is the higher-order reason
         * — telling the user the kernel rejected an IMU sink would be technically
         * correct but useless when the slot type has no sink at all.
         *
         * BACKEND_BROKEN comes BEFORE STALLED / STREAMING because if the receiver
         * has *said* the sink is broken, any "streaming" reading on the dish
         * would be a lie — the bytes never land. The pill should read what is
         * actually happening at the receiver, not what the dish is *trying* to do.
         */
        @Suppress("LongParameterList")
        fun of(
            isAvailable: Boolean,
            isStreaming: Boolean,
            connectionCarriesMotion: Boolean,
            connectionConnected: Boolean,
            userEnabled: Boolean = true,
            hostHasSinkForType: Boolean = true,
            satelliteBackendOk: Boolean? = null,
            isStalled: Boolean = false,
        ): MotionIndicatorState =
            when {
                !isAvailable -> UNAVAILABLE
                !userEnabled -> USER_DISABLED
                !connectionCarriesMotion -> NOT_FORWARDED
                !hostHasSinkForType -> NO_HOST_SINK
                satelliteBackendOk == false -> BACKEND_BROKEN
                isStreaming && connectionConnected && isStalled -> STALLED
                isStreaming && connectionConnected -> STREAMING
                else -> PAUSED
            }
    }
}
