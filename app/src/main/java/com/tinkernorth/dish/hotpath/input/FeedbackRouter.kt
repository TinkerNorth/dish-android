// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes the non-rumble feedback (lightbar / trigger effects / player LEDs /
 * Moonlight trigger rumble) to the one target that can actuate it: a
 * Direct-claimed USB pad. The phone has no controller LED or trigger-motor
 * surface and the framework exposes none for its pads, so every other target
 * classification drops the event; native itself drops families without the
 * hardware, so no per-model gate is needed here.
 *
 * Session resolution reuses [resolveRumble]: the slot the (session, controller
 * index) pair is bound to is the same one for every feedback kind.
 */
@Singleton
class FeedbackRouter
    @Inject
    constructor(
        private val satellite: SatelliteConnectionManager,
        private val native: PhysicalInputNative,
    ) {
        fun dispatchLightbar(
            sessionHandle: Int,
            controllerIndex: Int,
            r: Int,
            g: Int,
            b: Int,
        ) {
            val target = resolveTarget(sessionHandle, controllerIndex)
            if (target is RumbleTarget.DirectUsb) native.sendUsbLightbar(target.deviceId, r, g, b)
        }

        fun dispatchTriggerEffects(
            sessionHandle: Int,
            controllerIndex: Int,
            blocks: ByteArray,
        ) {
            val target = resolveTarget(sessionHandle, controllerIndex)
            if (target is RumbleTarget.DirectUsb) native.sendUsbTriggerEffects(target.deviceId, blocks)
        }

        fun dispatchPlayerLeds(
            sessionHandle: Int,
            controllerIndex: Int,
            ledMask: Int,
        ) {
            val target = resolveTarget(sessionHandle, controllerIndex)
            if (target is RumbleTarget.DirectUsb) native.sendUsbPlayerLeds(target.deviceId, ledMask)
        }

        /** Moonlight path: the connection already resolved the slot. */
        fun dispatchLightbarToSlot(
            slotId: String,
            r: Int,
            g: Int,
            b: Int,
        ) {
            val target = classifyTarget(slotId)
            if (target is RumbleTarget.DirectUsb) native.sendUsbLightbar(target.deviceId, r, g, b)
        }

        fun dispatchTriggerRumbleToSlot(
            slotId: String,
            leftMagnitude: Int,
            rightMagnitude: Int,
        ) {
            val target = classifyTarget(slotId)
            if (target is RumbleTarget.DirectUsb) {
                native.sendUsbTriggerRumble(target.deviceId, leftMagnitude, rightMagnitude)
            }
        }

        private fun resolveTarget(
            sessionHandle: Int,
            controllerIndex: Int,
        ): RumbleTarget {
            val snapshot =
                satellite.connections.value.values.map { conn ->
                    RumbleConnectionSnapshot(
                        handle = conn.handle,
                        connected = conn.state.value == SatelliteSessionState.Live,
                        slots = conn.slots.value,
                    )
                }
            return resolveRumble(snapshot, sessionHandle, controllerIndex)
        }
    }
