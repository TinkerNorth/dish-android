// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.source.lights.FrameworkLightGateway
import com.tinkernorth.dish.source.lights.LightSource
import com.tinkernorth.dish.source.store.FeedbackActivityStore
import com.tinkernorth.dish.source.store.FeedbackKind
import com.tinkernorth.dish.source.store.RumbleEnabledStore
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
 * - A framework pad gets its light bar through the Android lights API
 *   ([FrameworkLightGateway]); the composer advertises that only where it can
 *   land (a Bluetooth DualShock 4 / DualSense on API 31+ whose driver exposes an
 *   RGB light), so the writer only ever runs for such a pad. Trigger effects,
 *   player LEDs and the mic lamp still drop for framework pads: Android exposes
 *   no API for them, and folding trigger rumble into the pad's main vibrator
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
        private val feedbackActivity: FeedbackActivityStore,
        private val frameworkLights: FrameworkLightGateway,
        private val rumbleEnabled: RumbleEnabledStore,
    ) {
        fun dispatchLightbar(
            sessionHandle: Int,
            controllerIndex: Int,
            r: Int,
            g: Int,
            b: Int,
        ) {
            val connections = connectionSnapshots()
            val target = resolveRumble(connections, sessionHandle, controllerIndex)
            noteHost(target, FeedbackKind.LIGHTBAR)
            val connectionId = connectionForHandle(connections, sessionHandle)?.connectionId.orEmpty()
            actuateLightbar(target, LightSource(connectionId, controllerIndex), r, g, b)
        }

        fun dispatchTriggerEffects(
            sessionHandle: Int,
            controllerIndex: Int,
            blocks: ByteArray,
        ) {
            val target = resolveTarget(sessionHandle, controllerIndex)
            noteHost(target, FeedbackKind.TRIGGER_EFFECTS)
            actuateTriggerEffects(target, blocks)
        }

        fun dispatchPlayerLeds(
            sessionHandle: Int,
            controllerIndex: Int,
            ledMask: Int,
        ) {
            val target = resolveTarget(sessionHandle, controllerIndex)
            noteHost(target, FeedbackKind.PLAYER_LEDS)
            actuatePlayerLeds(target, ledMask)
        }

        /**
         * Mic-mute lamp (MSG_MIC_LED): 0 off, 1 on, 2 pulse, already validated
         * natively. Resolves like every other feedback kind. A Direct-claimed
         * DualSense gets the real lamp (and, with it, its own microphone
         * amplifier muted, which is what the hardware couples). The phone
         * renders it as the accent ring on the on-screen pad's mute button,
         * and only the ring: the pill's face is the local mute state, which
         * this lamp has no say over (a host driving the lamp out of phase
         * must not make a muted mic look live). Framework pads drop it for
         * the usual reason (no controller-LED API).
         */
        fun dispatchMicLed(
            sessionHandle: Int,
            controllerIndex: Int,
            state: Int,
        ) {
            val target = resolveTarget(sessionHandle, controllerIndex)
            noteHost(target, FeedbackKind.MIC_LED)
            actuateMicLed(target, state)
        }

        /** Slot-addressed entry points: the Moonlight path and the inspector's test bench already know the slot. */
        fun dispatchLightbarToSlot(
            slotId: String,
            source: LightSource,
            r: Int,
            g: Int,
            b: Int,
        ) {
            actuateLightbar(classifyTarget(slotId), source, r, g, b)
        }

        /** The bench's light bar: the same actuation, never taken for the host's color. */
        fun testLightbar(
            slotId: String,
            r: Int,
            g: Int,
            b: Int,
        ) {
            when (val target = classifyTarget(slotId)) {
                is RumbleTarget.DirectUsb -> native.sendUsbLightbar(target.deviceId, r, g, b)
                is RumbleTarget.Framework -> frameworkLights.paint(target.deviceId, r, g, b)
                else -> Unit
            }
        }

        // A framework pad gets its host's color back. The phone keeps no host color for a Direct
        // pad, so the bench turns that bar off.
        fun endLightbarTest(slotId: String) {
            when (val target = classifyTarget(slotId)) {
                is RumbleTarget.DirectUsb ->
                    native.sendUsbLightbar(target.deviceId, LIGHTBAR_CHANNEL_OFF, LIGHTBAR_CHANNEL_OFF, LIGHTBAR_CHANNEL_OFF)
                is RumbleTarget.Framework -> frameworkLights.showHostColor(target.deviceId)
                else -> Unit
            }
        }

        fun dispatchTriggerEffectsToSlot(
            slotId: String,
            blocks: ByteArray,
        ) {
            actuateTriggerEffects(classifyTarget(slotId), blocks)
        }

        fun dispatchPlayerLedsToSlot(
            slotId: String,
            ledMask: Int,
        ) {
            actuatePlayerLeds(classifyTarget(slotId), ledMask)
        }

        fun dispatchMicLedToSlot(
            slotId: String,
            state: Int,
        ) {
            actuateMicLed(classifyTarget(slotId), state)
        }

        // Trigger rumble is rumble, so the slot's rumble switch covers it. Off, the host's event
        // lands as a stop rather than being dropped: a Direct pad's trigger motors hold their last
        // level until the next write, so a dropped stop could leave them running.
        fun dispatchTriggerRumbleToSlot(
            slotId: String,
            leftMagnitude: Int,
            rightMagnitude: Int,
        ) {
            feedbackActivity.note(slotId, FeedbackKind.TRIGGER_RUMBLE)
            val rumbleOn = rumbleEnabled.isEnabled(slotId)
            val left = if (rumbleOn) leftMagnitude else TRIGGER_RUMBLE_STOP
            val right = if (rumbleOn) rightMagnitude else TRIGGER_RUMBLE_STOP
            testTriggerRumble(slotId, left, right)
        }

        /** The bench's entry: the same actuation without counting it as host feedback. */
        fun testTriggerRumble(
            slotId: String,
            leftMagnitude: Int,
            rightMagnitude: Int,
        ) {
            when (val target = classifyTarget(slotId)) {
                is RumbleTarget.DirectUsb ->
                    rumble.driveDirectTriggers(target.deviceId, leftMagnitude, rightMagnitude, TRIGGER_RUMBLE_HOLD_MS)
                // The phone IS the virtual pad's motors: fold the trigger pair through
                // the rumble path (left -> strong, right -> weak) so the delivery
                // toggle, the stop-on-zero rule and the duration clamp all apply.
                RumbleTarget.Phone ->
                    rumble.dispatchToSlot(VIRTUAL_SLOT_ID, leftMagnitude, rightMagnitude, TRIGGER_RUMBLE_HOLD_MS)
                else -> Unit
            }
        }

        private fun noteHost(
            target: RumbleTarget,
            kind: FeedbackKind,
        ) {
            val slotId =
                when (target) {
                    RumbleTarget.Phone -> VIRTUAL_SLOT_ID
                    is RumbleTarget.Framework -> target.deviceId.toString()
                    is RumbleTarget.DirectUsb -> target.deviceId.toString()
                    RumbleTarget.None -> return
                }
            feedbackActivity.note(slotId, kind)
        }

        private fun actuateLightbar(
            target: RumbleTarget,
            source: LightSource,
            r: Int,
            g: Int,
            b: Int,
        ) {
            when (target) {
                is RumbleTarget.DirectUsb -> native.sendUsbLightbar(target.deviceId, r, g, b)
                is RumbleTarget.Framework -> frameworkLights.setColor(target.deviceId, source, r, g, b)
                RumbleTarget.Phone -> virtualFeedback.setLightbar(r, g, b)
                RumbleTarget.None -> Unit
            }
        }

        private fun actuateTriggerEffects(
            target: RumbleTarget,
            blocks: ByteArray,
        ) {
            when (target) {
                is RumbleTarget.DirectUsb -> native.sendUsbTriggerEffects(target.deviceId, blocks)
                RumbleTarget.Phone ->
                    virtualFeedback.setTriggerEffects(
                        leftActive = triggerEffectActive(blocks, LEFT_BLOCK_OFFSET),
                        rightActive = triggerEffectActive(blocks, RIGHT_BLOCK_OFFSET),
                    )
                else -> Unit
            }
        }

        private fun actuatePlayerLeds(
            target: RumbleTarget,
            ledMask: Int,
        ) {
            when (target) {
                is RumbleTarget.DirectUsb -> native.sendUsbPlayerLeds(target.deviceId, ledMask)
                RumbleTarget.Phone -> virtualFeedback.setPlayerLeds(ledMask)
                else -> Unit
            }
        }

        private fun actuateMicLed(
            target: RumbleTarget,
            state: Int,
        ) {
            when (target) {
                is RumbleTarget.DirectUsb -> native.sendUsbMicMuteLed(target.deviceId, state)
                RumbleTarget.Phone -> virtualFeedback.setMicLed(state)
                else -> Unit
            }
        }

        private fun resolveTarget(
            sessionHandle: Int,
            controllerIndex: Int,
        ): RumbleTarget = resolveRumble(connectionSnapshots(), sessionHandle, controllerIndex)

        private fun connectionSnapshots(): List<RumbleConnectionSnapshot> =
            satellite.connections.value.values.map { conn ->
                RumbleConnectionSnapshot(
                    connectionId = conn.id,
                    handle = conn.handle,
                    connected = conn.state.value == SatelliteSessionState.Live,
                    slots = conn.slots.value,
                )
            }

        companion object {
            // Wire order of MSG_TRIGGER_EFFECTS blocks: left (0..10), right (11..21);
            // byte 0 of each is the DualSense effect mode, 0 = off.
            private const val LEFT_BLOCK_OFFSET = 0
            private const val RIGHT_BLOCK_OFFSET = 11

            // Matches the Moonlight rumble hold: refreshed by the host well before expiry.
            private const val TRIGGER_RUMBLE_HOLD_MS = 1500

            private const val TRIGGER_RUMBLE_STOP = 0

            private const val LIGHTBAR_CHANNEL_OFF = 0
        }
    }

// A block whose mode byte is present and non-zero holds an effect; a truncated array reads as off.
private fun triggerEffectActive(
    blocks: ByteArray,
    offset: Int,
): Boolean = blocks.size > offset && blocks[offset].toInt() != 0
