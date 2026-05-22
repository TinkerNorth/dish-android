// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.abstracts.AbstractComposer
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.sensor.PhoneMotionAvailability
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-controller motion capability — the single source of truth that drives:
 *
 *   - the `CAP_MOTION` bit at `MSG_CONTROLLER_ADD` time (so the receiver's web
 *     UI is not lied to about which pads stream IMU);
 *   - whether [com.tinkernorth.dish.source.sensor.PhoneMotionSource] /
 *     [com.tinkernorth.dish.source.sensor.PhysicalMotionSource] register
 *     sensor listeners at all (gyro listeners are a measurable battery cost);
 *   - what the on-screen motion pill renders.
 *
 * Three orthogonal facts, combined into [MotionCapability.effective]:
 *
 *   - [MotionCapability.hasGyro] — the hardware exists. Virtual slot: the
 *     phone has a gyroscope (see [PhoneMotionAvailability]). Physical slot:
 *     the pad has a per-device gyroscope (see
 *     [com.tinkernorth.dish.source.sensor.PhysicalMotionProbe], cached on
 *     [PhysicalGamepadRegistry.Device.hasGyro]).
 *   - [MotionCapability.carriesOnConnection] — the bound connection has a
 *     `MSG_MOTION` channel. Satellite does; Bluetooth-HID does not.
 *   - [MotionCapability.userEnabled] — the user wants motion on for this
 *     slot. Defaults to true; flipped by the per-slot toggle wired in a
 *     subsequent PR (the `MotionEnabledStore`).
 *
 * **Why a composer:** these are pure derivations from
 * [PhoneMotionAvailability], [PhysicalGamepadRegistry], [ConnectionHub]
 * `bindings` + `connections`. No new lifecycle, no events — exactly the shape
 * [AbstractComposer] exists for. Threading the same `MotionCapability` value
 * through every consumer keeps "is motion on for this slot" un-divergeable.
 */
data class MotionCapability(
    /** Hardware reports a gyroscope on the device backing this slot. */
    val hasGyro: Boolean,
    /** Bound connection is a satellite session (BT-HID has no `MSG_MOTION`). */
    val carriesOnConnection: Boolean,
    /**
     * User has motion enabled for this slot. Stub-true until the
     * `MotionEnabledStore` lands in the next PR; the field is here from the
     * start so downstream call sites can already read it.
     */
    val userEnabled: Boolean = true,
) {
    /**
     * True iff motion samples should both be captured AND would actually reach
     * the satellite for this slot. The same boolean drives the wire `CAP_MOTION`
     * bit (paired with [userEnabled] so that flipping the user toggle drops the
     * advertisement) and the sensor-listener gate.
     */
    val effective: Boolean get() = hasGyro && carriesOnConnection && userEnabled

    /**
     * The `CAP_MOTION` (0x0004) bit the dish should advertise at
     * `MSG_CONTROLLER_ADD` for this slot. Note that this is **not** gated on
     * [carriesOnConnection]: the capability describes the *dish*, not the
     * link, and we want a satellite re-connect to recover motion without a
     * re-handshake. It IS gated on [userEnabled] because flipping the toggle
     * is a meaningful change to what the dish will emit.
     */
    fun toCapBits(): Int = if (hasGyro && userEnabled) CAP_MOTION_BIT else 0

    companion object {
        /** Mirror of `satellite/src/core/types.h::CAP_MOTION`. */
        const val CAP_MOTION_BIT: Int = 0x0004

        /** Initial value before any upstream resolves — nothing is capable yet. */
        val Off = MotionCapability(hasGyro = false, carriesOnConnection = false)
    }
}

/**
 * `Map<slotId, MotionCapability>` for every slot the user can currently see —
 * the virtual touch slot (always present) plus every attached physical pad.
 *
 * A slot present in the map means we have a coherent capability snapshot for
 * it. A slot absent from the map means the registry doesn't see it (e.g. a
 * physical pad in the disconnect-grace window after a USB jiggle).
 */
@Singleton
class MotionCapabilityComposer
    @Inject
    constructor(
        private val phoneAvailability: PhoneMotionAvailability,
        private val registry: PhysicalGamepadRegistry,
        private val hub: ConnectionHub,
        scope: CoroutineScope,
    ) : AbstractComposer<Map<String, MotionCapability>>(scope, emptyMap()) {
        override fun upstream(): Flow<Map<String, MotionCapability>> =
            combine(
                phoneAvailability.state,
                registry.devices,
                hub.bindings,
                hub.connections,
            ) { phoneHasGyro, devices, bindings, summaries ->
                val byId = summaries.associateBy { it.id }
                val out = HashMap<String, MotionCapability>(devices.size + 1)

                // Virtual slot — always present so the touch overlay always
                // has a capability entry to read, even when no satellite is up.
                out[VIRTUAL_SLOT_ID] =
                    MotionCapability(
                        hasGyro = phoneHasGyro,
                        carriesOnConnection = carriesMotion(VIRTUAL_SLOT_ID, bindings, byId),
                    )

                // Physical pads.
                for ((deviceId, device) in devices) {
                    val slotId = deviceId.toString()
                    out[slotId] =
                        MotionCapability(
                            hasGyro = device.hasGyro,
                            carriesOnConnection = carriesMotion(slotId, bindings, byId),
                        )
                }
                out
            }

        /**
         * Resolve `slotId → bound-connection-summary → kind == SATELLITE &&
         * live`. Returns false for any unbound or non-satellite slot. The
         * `Connected` check is deliberate: a satellite that is `Connecting` or
         * `Unstable` can't currently sink motion, so the pill reading should
         * reflect that, AND the registration cap bit should still claim
         * `CAP_MOTION` (the dish is capable, the link is just temporarily
         * down) — `toCapBits()` does not consult `carriesOnConnection`.
         */
        private fun carriesMotion(
            slotId: String,
            bindings: Map<String, String>,
            summariesById: Map<String, ConnectionSummary>,
        ): Boolean {
            val cid = bindings[slotId] ?: return false
            val summary = summariesById[cid] ?: return false
            return summary.kind == ConnectionKind.SATELLITE &&
                summary.live == LinkState.Connected
        }
    }
