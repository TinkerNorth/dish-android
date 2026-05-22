// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the *receiver* told us at controller-add time about its ability to
 * actually land motion bytes for this slot. Sourced from the motion-status
 * byte on `MSG_CONTROLLER_ACK` (`ACK_MOTION_FLAG_*` on the satellite side) —
 * the dish's per-slot motion pill consumes this so an "effective on the dish,
 * dropped at the receiver" path can read [BACKEND_BROKEN] instead of falsely
 * showing STREAMING.
 *
 * The two facts are deliberately split: [sinkSupportedForType] is universal
 * across the shipping backends (PS yes, Xbox no) and corresponds to the
 * dish-side [com.tinkernorth.dish.composer.MotionCapability.hostHasSinkForType]
 * heuristic — having both in hand lets the composer cross-check its own
 * derivation against satellite truth. [backendOk] is per-serial (whether the
 * receiver's kernel actually accepted the IMU sink at plug-in time) and is
 * the diagnostic the dish couldn't compute on its own without an HTTP poll
 * of `/api/connections`.
 */
data class SatelliteMotionBackendStatus(
    /**
     * Receiver's backend has an IMU surface for this slot's chosen controller
     * type. Mirrors `ACK_MOTION_FLAG_SINK_SUPPORTED_FOR_TYPE` (bit 0) on the
     * satellite. PS = yes on every shipping backend; Xbox = no.
     */
    val sinkSupportedForType: Boolean,
    /**
     * Receiver's backend successfully created the per-serial IMU sink. Mirrors
     * `ACK_MOTION_FLAG_BACKEND_OK` (bit 1) on the satellite. False on Linux
     * uinput when the kernel rejects the `INPUT_PROP_ACCELEROMETER` device
     * (kernel too old, no `/dev/uinput` permission, `/tmp` exhausted) — the
     * dish-side pill calls this out so the user sees a real reason for
     * "motion isn't reaching the game" instead of staring at STREAMING.
     */
    val backendOk: Boolean,
) {
    /**
     * True iff motion bytes the dish streams will actually reach the
     * virtual gamepad's IMU surface on the receiver. False ⇒ the pill
     * should NOT read STREAMING regardless of the local source state.
     */
    val effective: Boolean get() = sinkSupportedForType && backendOk

    companion object {
        /**
         * Decode a packed motion-flags byte (the JNI's
         * `getLastControllerMotionFlags` return). The caller short-circuits
         * on the sentinel `-1` ("no extended ACK seen / pre-extension
         * satellite") and only calls this for `0..255`.
         */
        fun fromFlags(flags: Int): SatelliteMotionBackendStatus =
            SatelliteMotionBackendStatus(
                sinkSupportedForType = (flags and FLAG_SINK_SUPPORTED_FOR_TYPE) != 0,
                backendOk = (flags and FLAG_BACKEND_OK) != 0,
            )

        /** Mirror of `ACK_MOTION_FLAG_SINK_SUPPORTED_FOR_TYPE` on satellite. */
        const val FLAG_SINK_SUPPORTED_FOR_TYPE: Int = 0x01

        /** Mirror of `ACK_MOTION_FLAG_BACKEND_OK` on satellite. */
        const val FLAG_BACKEND_OK: Int = 0x02
    }
}

/**
 * `(connectionId, slotId) → SatelliteMotionBackendStatus` for every
 * satellite slot whose ACK carried the motion-flags byte. An absent entry
 * means "unknown" — either the slot hasn't been registered yet, or the
 * satellite is a pre-extension build that only sent the legacy 4-byte ACK
 * payload. The composer treats absent as "fall back to the dish's own
 * `hostHasSinkForType` heuristic" rather than defaulting to "broken."
 *
 * **Pattern:** [AbstractStateSource]`<Map<Pair<String, String>,
 * SatelliteMotionBackendStatus>>` — same shape as
 * [com.tinkernorth.dish.source.store.ControllerTypeStore]. Both stores are
 * per-(connection, slot) reactive caches of facts the satellite tells the
 * dish; if you grow a third one (e.g. per-slot rumble strength reported by
 * the host), copy this file.
 *
 * Cleared on slot detach / connection teardown — see
 * [com.tinkernorth.dish.source.connection.SatelliteConnection.detachSlot] /
 * [com.tinkernorth.dish.source.connection.SatelliteConnection.markDisconnected]
 * — so a slot's status doesn't survive its lifetime and mislead the pill
 * after re-bind.
 */
@Singleton
class SatelliteMotionBackendStatusStore
    @Inject
    constructor() :
    AbstractStateSource<Map<Pair<String, String>, SatelliteMotionBackendStatus>>(emptyMap()) {
        /** Current status for [connectionId]/[slotId], or null if unknown. */
        fun statusFor(
            connectionId: String,
            slotId: String,
        ): SatelliteMotionBackendStatus? = state.value[connectionId to slotId]

        /** Record [status] for [connectionId]/[slotId]. Called from the ACK-receive path. */
        fun setStatus(
            connectionId: String,
            slotId: String,
            status: SatelliteMotionBackendStatus,
        ) {
            setState { it + ((connectionId to slotId) to status) }
        }

        /**
         * Drop the entry for [connectionId]/[slotId]. Called when the slot
         * detaches (the next add will write a fresh status), or when the
         * connection tears down. Without this, a stale status survives
         * re-bind and the pill would surface yesterday's truth.
         */
        fun clear(
            connectionId: String,
            slotId: String,
        ) {
            setState {
                val key = connectionId to slotId
                if (key in it) it - key else it
            }
        }

        /**
         * Drop every entry for [connectionId]. Called at session teardown so
         * a satellite that goes away takes its stale per-slot status with it.
         */
        fun clearConnection(connectionId: String) {
            setState { current -> current.filterNot { (k, _) -> k.first == connectionId } }
        }

        /**
         * Project a `slotId → status` map for a single connection's bound
         * slots. The composer calls this to fold per-connection truth into
         * its per-slot capability map without leaking the `Pair` key to
         * downstream consumers.
         */
        fun slotStatusesFor(
            connectionId: String,
            boundSlotIds: List<String>,
        ): Map<String, SatelliteMotionBackendStatus> {
            val snapshot = state.value
            val out = mutableMapOf<String, SatelliteMotionBackendStatus>()
            for (slotId in boundSlotIds) {
                val status = snapshot[connectionId to slotId] ?: continue
                out[slotId] = status
            }
            return out
        }
    }
