// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.source.store.VirtualPadFeedbackStore
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes the non-rumble feedback (lightbar / trigger effects / player LEDs /
 * Moonlight trigger rumble) to whatever the bound slot can actuate:
 *
 * - A Direct-claimed USB pad gets the real thing on its OUT endpoint (native
 *   drops families without the hardware, so no per-model gate is needed here).
 * - The virtual pad renders lights on its skin ([VirtualPadFeedbackStore]) and
 *   folds trigger rumble into the phone vibrator through the rumble path, so
 *   the per-slot rumble toggle and stop rules keep applying.
 * - Framework pads drop everything: Android exposes no controller LED or
 *   trigger-motor API, and folding trigger rumble into the pad's main vibrator
 *   would fight the real rumble stream.
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
        private val virtualFeedback: VirtualPadFeedbackStore,
        private val rumble: RumbleRouter,
    ) {
        fun dispatchLightbar(
            sessionHandle: Int,
            controllerIndex: Int,
            r: Int,
            g: Int,
            b: Int,
        ) {
            when (val target = resolveTarget(sessionHandle, controllerIndex)) {
                is RumbleTarget.DirectUsb -> native.sendUsbLightbar(target.deviceId, r, g, b)
                RumbleTarget.Phone -> virtualFeedback.setLightbar(r, g, b)
                else -> Unit
            }
        }

        fun dispatchTriggerEffects(
            sessionHandle: Int,
            controllerIndex: Int,
            blocks: ByteArray,
        ) {
            when (val target = resolveTarget(sessionHandle, controllerIndex)) {
                is RumbleTarget.DirectUsb -> native.sendUsbTriggerEffects(target.deviceId, blocks)
                RumbleTarget.Phone ->
                    virtualFeedback.setTriggerEffects(
                        leftActive = triggerEffectActive(blocks, LEFT_BLOCK_OFFSET),
                        rightActive = triggerEffectActive(blocks, RIGHT_BLOCK_OFFSET),
                    )
                else -> Unit
            }
        }

        fun dispatchPlayerLeds(
            sessionHandle: Int,
            controllerIndex: Int,
            ledMask: Int,
        ) {
            when (val target = resolveTarget(sessionHandle, controllerIndex)) {
                is RumbleTarget.DirectUsb -> native.sendUsbPlayerLeds(target.deviceId, ledMask)
                RumbleTarget.Phone -> virtualFeedback.setPlayerLeds(ledMask)
                else -> Unit
            }
        }

        /**
         * Mic-mute lamp (MSG_MIC_LED): 0 off, 1 on, 2 pulse, already validated
         * natively. Resolves like every other feedback kind; both sinks land
         * with the playback wave, which owns the DualSense mute-LED output
         * report and the virtual pad's mute-button rendering. Framework pads
         * drop it for the usual reason (no controller-LED API), so the drop is
         * the finished behaviour for that arm, not a stub.
         *
         * [state] is unused only because both sinks are still TODO below; the
         * playback wave consumes it and drops the suppression with them.
         */
        @Suppress("UnusedParameter")
        fun dispatchMicLed(
            sessionHandle: Int,
            controllerIndex: Int,
            state: Int,
        ) {
            when (resolveTarget(sessionHandle, controllerIndex)) {
                // TODO(AND-4): usb_parsers buildMicMuteLedReport, via a
                //  sendUsbMicMuteLed triad next to sendUsbPlayerLeds.
                is RumbleTarget.DirectUsb -> Unit
                // TODO(AND-4): VirtualPadFeedbackStore.setMicLed(state).
                RumbleTarget.Phone -> Unit
                else -> Unit
            }
        }

        /** Moonlight path: the connection already resolved the slot. */
        fun dispatchLightbarToSlot(
            slotId: String,
            r: Int,
            g: Int,
            b: Int,
        ) {
            when (val target = classifyTarget(slotId)) {
                is RumbleTarget.DirectUsb -> native.sendUsbLightbar(target.deviceId, r, g, b)
                RumbleTarget.Phone -> virtualFeedback.setLightbar(r, g, b)
                else -> Unit
            }
        }

        fun dispatchTriggerRumbleToSlot(
            slotId: String,
            leftMagnitude: Int,
            rightMagnitude: Int,
        ) {
            when (val target = classifyTarget(slotId)) {
                is RumbleTarget.DirectUsb ->
                    native.sendUsbTriggerRumble(target.deviceId, leftMagnitude, rightMagnitude)
                // The phone IS the virtual pad's motors: fold the trigger pair through
                // the rumble path (left -> strong, right -> weak) so the delivery
                // toggle, the stop-on-zero rule and the duration clamp all apply.
                RumbleTarget.Phone ->
                    rumble.dispatchToSlot(VIRTUAL_SLOT_ID, leftMagnitude, rightMagnitude, TRIGGER_RUMBLE_HOLD_MS)
                else -> Unit
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

        companion object {
            // Wire order of MSG_TRIGGER_EFFECTS blocks: left (0..10), right (11..21);
            // byte 0 of each is the DualSense effect mode, 0 = off.
            private const val LEFT_BLOCK_OFFSET = 0
            private const val RIGHT_BLOCK_OFFSET = 11

            // Matches the Moonlight rumble hold: refreshed by the host well before expiry.
            private const val TRIGGER_RUMBLE_HOLD_MS = 1500

            private fun triggerEffectActive(
                blocks: ByteArray,
                offset: Int,
            ): Boolean = blocks.size > offset && blocks[offset].toInt() != 0
        }
    }
