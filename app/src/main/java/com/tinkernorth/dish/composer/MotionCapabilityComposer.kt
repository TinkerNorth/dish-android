// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.abstracts.AbstractComposer
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.sensor.PhoneMotionAvailability
import com.tinkernorth.dish.source.store.MotionEnabledStore
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
 *   - what the on-screen motion pill renders, including the
 *     "your Xbox virtual pad has no IMU surface on the host" warning state
 *     ([MotionCapability.hostHasSinkForType]).
 *
 * Four orthogonal facts combined into [MotionCapability.effective] /
 * [MotionCapability.toCapBits] / the pill mapping:
 *
 *   - [MotionCapability.hasGyro] — the hardware exists. Virtual slot: the
 *     phone has a gyroscope (see [PhoneMotionAvailability]). Physical slot:
 *     the pad has a per-device gyroscope (see
 *     [com.tinkernorth.dish.source.sensor.PhysicalMotionProbe], cached on
 *     [PhysicalGamepadRegistry.Device.hasGyro]).
 *   - [MotionCapability.carriesOnConnection] — the bound connection has a
 *     `MSG_MOTION` channel. Satellite does; Bluetooth-HID does not.
 *   - [MotionCapability.userEnabled] — the user wants motion on for this
 *     slot. Sourced from [MotionEnabledStore.isEnabled], which collapses
 *     an absent entry onto the product default (`DEFAULT_ENABLED = true`).
 *   - [MotionCapability.hostHasSinkForType] — the receiving host's backend
 *     has an IMU surface for the slot's controller type. Derived from
 *     [com.tinkernorth.dish.composer.ConnectionSummary.satelliteControllerTypes]:
 *     PlayStation = yes (DS4 emulation on Windows/Linux carries gyro/accel),
 *     Xbox = no (XInput has no IMU surface; XUSB_REPORT has no gyro fields).
 *     Universal across the Windows + Linux satellite platforms — no wire
 *     protocol bump needed to learn it.
 *
 * **Why a composer:** these are pure derivations from
 * [PhoneMotionAvailability], [PhysicalGamepadRegistry], [ConnectionHub]
 * `bindings` + `connections`, and [MotionEnabledStore]. No new lifecycle,
 * no events — exactly the shape [AbstractComposer] exists for. Threading
 * the same `MotionCapability` value through every consumer keeps "is
 * motion on for this slot" un-divergeable.
 */
data class MotionCapability(
    /** Hardware reports a gyroscope on the device backing this slot. */
    val hasGyro: Boolean,
    /** Bound connection is a satellite session (BT-HID has no `MSG_MOTION`). */
    val carriesOnConnection: Boolean,
    /**
     * User has motion enabled for this slot. Sourced from
     * [MotionEnabledStore.isEnabled], so an absent slot in that store
     * collapses to [MotionEnabledStore.DEFAULT_ENABLED] (true).
     */
    val userEnabled: Boolean = true,
    /**
     * Receiver's backend has an IMU surface for the slot's chosen
     * controller type. Always true for PlayStation-typed slots (DS4
     * emulation on Windows ViGEm + Linux uinput both carry gyro/accel),
     * false for Xbox-typed slots (XInput has no IMU). Defaulted true so
     * a slot with no type yet — or a non-satellite connection — doesn't
     * spuriously raise the "no host sink" warning.
     */
    val hostHasSinkForType: Boolean = true,
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
     * is a meaningful change to what the dish will emit. It is NOT gated on
     * [hostHasSinkForType] either — the dish is honest about what IT will
     * stream, regardless of whether the host can sink it; the
     * `motionSinkSupportedForType` field on the receiver's snapshot is the
     * canonical "host can sink" signal.
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
        private val motionEnabledStore: MotionEnabledStore,
        scope: CoroutineScope,
    ) : AbstractComposer<Map<String, MotionCapability>>(scope, emptyMap()) {
        override fun upstream(): Flow<Map<String, MotionCapability>> =
            combine(
                phoneAvailability.state,
                registry.devices,
                hub.bindings,
                hub.connections,
                motionEnabledStore.state,
            ) { phoneHasGyro, devices, bindings, summaries, enabledMap ->
                val byId = summaries.associateBy { it.id }
                val out = HashMap<String, MotionCapability>(devices.size + 1)

                // Virtual slot — always present so the touch overlay always
                // has a capability entry to read, even when no satellite is up.
                out[VIRTUAL_SLOT_ID] =
                    MotionCapability(
                        hasGyro = phoneHasGyro,
                        carriesOnConnection = carriesMotion(VIRTUAL_SLOT_ID, bindings, byId),
                        userEnabled = enabledMap[VIRTUAL_SLOT_ID] ?: MotionEnabledStore.DEFAULT_ENABLED,
                        hostHasSinkForType = hostSinkForType(VIRTUAL_SLOT_ID, bindings, byId),
                    )

                // Physical pads.
                for ((deviceId, device) in devices) {
                    val slotId = deviceId.toString()
                    out[slotId] =
                        MotionCapability(
                            hasGyro = device.hasGyro,
                            carriesOnConnection = carriesMotion(slotId, bindings, byId),
                            userEnabled = enabledMap[slotId] ?: MotionEnabledStore.DEFAULT_ENABLED,
                            hostHasSinkForType = hostSinkForType(slotId, bindings, byId),
                        )
                }
                out
            }

        /**
         * Resolve the [MotionCapability] for [slotId]. Returns
         * [MotionCapability.Off] when the slot has not been seen yet (e.g.
         * a controller-add fires before the composer's first emission). Use
         * this from [com.tinkernorth.dish.source.connection.SatelliteConnection]
         * at registration time — it's a synchronous read of the latest
         * derived map, so no suspension is required on the registration
         * thread.
         */
        fun capabilityFor(slotId: String): MotionCapability =
            state.value[slotId] ?: MotionCapability.Off

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

        /**
         * Resolve whether the **host** has a motion sink for this slot's
         * chosen controller type.
         *
         * The dish never actually sees the receiver's backend; the answer is
         * a heuristic on the controller type, which is a known invariant of
         * the available backends:
         *
         *  - **Xbox-typed slot** ⇒ false. XInput / Xbox 360 emulation has
         *    no IMU surface anywhere (no gyro fields in `XUSB_REPORT`;
         *    `vigem_adapter.cpp::submitMotion` short-circuits for non-DS4
         *    serials; the Linux uinput motion node is only created in
         *    `pluginDeviceDS4`).
         *  - **PlayStation-typed slot** ⇒ true. The DS4_REPORT_EX path on
         *    Windows ViGEm and the INPUT_PROP_ACCELEROMETER node on Linux
         *    uinput both deliver motion.
         *  - **Unknown type** (no entry in
         *    [ConnectionSummary.satelliteControllerTypes]) ⇒ true.
         *    Conservatively assume motion CAN sink so we don't raise a
         *    false-alarm warning before the type even propagates.
         *
         * macOS satellite is a non-issue here: it returns
         * `ACK_ERR_BACKEND_UNAVAIL` at `MSG_CONTROLLER_ADD` time so the slot
         * never reaches `registered` — the pill resolves on `carriesOnConnection`
         * (false) long before `hostHasSinkForType` matters.
         *
         * Non-satellite bindings (Bluetooth-HID) also short-circuit to true
         * — the pill state machine already handles those with `NOT_FORWARDED`,
         * which takes precedence over `NO_HOST_SINK`.
         */
        private fun hostSinkForType(
            slotId: String,
            bindings: Map<String, String>,
            summariesById: Map<String, ConnectionSummary>,
        ): Boolean {
            val cid = bindings[slotId] ?: return true // unbound — irrelevant
            val summary = summariesById[cid] ?: return true
            if (summary.kind != ConnectionKind.SATELLITE) return true // BT-HID, irrelevant
            val type = summary.satelliteControllerTypes[slotId] ?: return true // unknown — assume yes
            return type == CONTROLLER_TYPE_PLAYSTATION
        }
    }
