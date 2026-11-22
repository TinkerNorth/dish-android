// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.lights.FrameworkLightGateway
import com.tinkernorth.dish.source.lights.LightSource
import com.tinkernorth.dish.source.store.FeedbackActivityStore
import com.tinkernorth.dish.source.store.FeedbackKind
import com.tinkernorth.dish.source.store.NO_FEEDBACK_VALUE
import com.tinkernorth.dish.source.store.RumbleEnabledStore
import com.tinkernorth.dish.source.store.VirtualPadFeedbackStore
import com.tinkernorth.dish.source.store.feedbackPair
import com.tinkernorth.dish.source.store.feedbackRgb
import com.tinkernorth.dish.source.store.rgbBlue
import com.tinkernorth.dish.source.store.rgbGreen
import com.tinkernorth.dish.source.store.rgbRed
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
 * The inspector's bench borrows the same surfaces through the test and end-of-test entries. The
 * game's last value on each state surface has one home per target, which takes the game's values
 * while a test holds that surface and is what the test's end puts back: [DirectSurfaces] for a
 * Direct pad, [FrameworkLightGateway] for a framework pad's light bar, and [VirtualPadFeedbackStore]
 * for the on-screen pad, whose skin the bench never borrows.
 *
 * Session resolution goes through the same [FeedbackRoutes] as rumble: the slot the
 * (session, controller index) pair is bound to is the same one for every feedback kind.
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
        private val routes = FeedbackRouteCache(satellite)
        private val direct = DirectSurfacesBySlot()

        fun dispatchLightbar(
            sessionHandle: Int,
            controllerIndex: Int,
            r: Int,
            g: Int,
            b: Int,
        ) {
            val route = routes.current().routeFor(sessionHandle, controllerIndex) ?: return
            noteHost(route, FeedbackKind.LIGHTBAR, feedbackRgb(r, g, b))
            landLightbar(route.target, route.slotId, route.lightSource, r, g, b)
        }

        fun dispatchTriggerEffects(
            sessionHandle: Int,
            controllerIndex: Int,
            blocks: ByteArray,
        ) {
            val route = routes.current().routeFor(sessionHandle, controllerIndex) ?: return
            noteHost(route, FeedbackKind.TRIGGER_EFFECTS, NO_FEEDBACK_VALUE)
            landTriggerEffects(route.target, route.slotId, blocks)
        }

        fun dispatchPlayerLeds(
            sessionHandle: Int,
            controllerIndex: Int,
            ledMask: Int,
        ) {
            val route = routes.current().routeFor(sessionHandle, controllerIndex) ?: return
            noteHost(route, FeedbackKind.PLAYER_LEDS, ledMask.toLong())
            landPlayerLeds(route.target, route.slotId, ledMask)
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
            val route = routes.current().routeFor(sessionHandle, controllerIndex) ?: return
            noteHost(route, FeedbackKind.MIC_LED, state.toLong())
            landMicLed(route.target, route.slotId, state)
        }

        /** The Moonlight path's light bar: it already knows the slot. */
        fun dispatchLightbarToSlot(
            slotId: String,
            source: LightSource,
            r: Int,
            g: Int,
            b: Int,
        ) {
            landLightbar(classifyTarget(slotId), slotId, source, r, g, b)
        }

        // Trigger rumble is rumble, so the slot's rumble switch covers it. Off, the host's event
        // lands as a stop rather than being dropped: a Direct pad's trigger motors hold their last
        // level until the next write, so a dropped stop could leave them running.
        fun dispatchTriggerRumbleToSlot(
            slotId: String,
            leftMagnitude: Int,
            rightMagnitude: Int,
        ) {
            feedbackActivity.note(slotId, FeedbackKind.TRIGGER_RUMBLE, feedbackPair(leftMagnitude, rightMagnitude))
            val rumbleOn = rumbleEnabled.isEnabled(slotId)
            val left = if (rumbleOn) leftMagnitude else TRIGGER_RUMBLE_STOP
            val right = if (rumbleOn) rightMagnitude else TRIGGER_RUMBLE_STOP
            testTriggerRumble(slotId, left, right)
        }

        // ---- the inspector's bench: each test holds its surface from the game, then gives it back ----

        /** The bench's light bar: held from the game while it runs and painted, never taken for the host's color. */
        fun testLightbar(
            slotId: String,
            r: Int,
            g: Int,
            b: Int,
        ) {
            when (val target = classifyTarget(slotId)) {
                is RumbleTarget.DirectUsb -> {
                    direct.of(slotId).hold(StateSurface.LIGHTBAR)
                    native.sendUsbLightbar(target.deviceId, r, g, b)
                }
                is RumbleTarget.Framework -> frameworkLights.paint(target.deviceId, r, g, b)
                RumbleTarget.Phone, RumbleTarget.None -> Unit
            }
        }

        /** The test is over: the bar shows the game's last color again, or goes dark where the game never sent one. */
        fun endLightbarTest(slotId: String) {
            when (val target = classifyTarget(slotId)) {
                is RumbleTarget.DirectUsb -> direct.of(slotId).giveBackLightbar { rgb -> writeDirectLightbar(target.deviceId, rgb) }
                is RumbleTarget.Framework -> frameworkLights.showHostColor(target.deviceId)
                RumbleTarget.Phone, RumbleTarget.None -> Unit
            }
        }

        fun testPlayerLeds(
            slotId: String,
            ledMask: Int,
        ) {
            when (val target = classifyTarget(slotId)) {
                is RumbleTarget.DirectUsb -> {
                    direct.of(slotId).hold(StateSurface.PLAYER_LEDS)
                    native.sendUsbPlayerLeds(target.deviceId, ledMask)
                }
                is RumbleTarget.Framework, RumbleTarget.Phone, RumbleTarget.None -> Unit
            }
        }

        fun endPlayerLedsTest(slotId: String) {
            when (val target = classifyTarget(slotId)) {
                is RumbleTarget.DirectUsb ->
                    direct.of(slotId).giveBackPlayerLeds { mask ->
                        native.sendUsbPlayerLeds(target.deviceId, mask)
                    }
                is RumbleTarget.Framework, RumbleTarget.Phone, RumbleTarget.None -> Unit
            }
        }

        fun testTriggerEffects(
            slotId: String,
            blocks: ByteArray,
        ) {
            when (val target = classifyTarget(slotId)) {
                is RumbleTarget.DirectUsb -> {
                    direct.of(slotId).hold(StateSurface.TRIGGER_EFFECTS)
                    native.sendUsbTriggerEffects(target.deviceId, blocks)
                }
                is RumbleTarget.Framework, RumbleTarget.Phone, RumbleTarget.None -> Unit
            }
        }

        fun endTriggerEffectsTest(slotId: String) {
            when (val target = classifyTarget(slotId)) {
                is RumbleTarget.DirectUsb ->
                    direct.of(slotId).giveBackTriggerEffects { blocks -> native.sendUsbTriggerEffects(target.deviceId, blocks) }
                is RumbleTarget.Framework, RumbleTarget.Phone, RumbleTarget.None -> Unit
            }
        }

        fun testMicLed(
            slotId: String,
            state: Int,
        ) {
            when (val target = classifyTarget(slotId)) {
                is RumbleTarget.DirectUsb -> {
                    direct.of(slotId).hold(StateSurface.MIC_LED)
                    native.sendUsbMicMuteLed(target.deviceId, state)
                }
                is RumbleTarget.Framework, RumbleTarget.Phone, RumbleTarget.None -> Unit
            }
        }

        fun endMicLedTest(slotId: String) {
            when (val target = classifyTarget(slotId)) {
                is RumbleTarget.DirectUsb -> direct.of(slotId).giveBackMicLed { state -> native.sendUsbMicMuteLed(target.deviceId, state) }
                is RumbleTarget.Framework, RumbleTarget.Phone, RumbleTarget.None -> Unit
            }
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
            route: FeedbackRoute,
            kind: FeedbackKind,
            value: Long,
        ) {
            if (route.target == RumbleTarget.None) return
            feedbackActivity.note(route.slotId, kind, value)
        }

        private fun landLightbar(
            target: RumbleTarget,
            slotId: String,
            source: LightSource,
            r: Int,
            g: Int,
            b: Int,
        ) {
            when (target) {
                is RumbleTarget.DirectUsb -> landDirectLightbar(target.deviceId, slotId, r, g, b)
                is RumbleTarget.Framework -> frameworkLights.setColor(target.deviceId, source, r, g, b)
                RumbleTarget.Phone -> virtualFeedback.setLightbar(r, g, b)
                RumbleTarget.None -> Unit
            }
        }

        private fun landTriggerEffects(
            target: RumbleTarget,
            slotId: String,
            blocks: ByteArray,
        ) {
            when (target) {
                is RumbleTarget.DirectUsb -> landDirectTriggerEffects(target.deviceId, slotId, blocks)
                RumbleTarget.Phone ->
                    virtualFeedback.setTriggerEffects(
                        leftActive = triggerEffectActive(blocks, LEFT_BLOCK_OFFSET),
                        rightActive = triggerEffectActive(blocks, RIGHT_BLOCK_OFFSET),
                    )
                is RumbleTarget.Framework, RumbleTarget.None -> Unit
            }
        }

        private fun landPlayerLeds(
            target: RumbleTarget,
            slotId: String,
            ledMask: Int,
        ) {
            when (target) {
                is RumbleTarget.DirectUsb -> landDirectPlayerLeds(target.deviceId, slotId, ledMask)
                RumbleTarget.Phone -> virtualFeedback.setPlayerLeds(ledMask)
                is RumbleTarget.Framework, RumbleTarget.None -> Unit
            }
        }

        private fun landMicLed(
            target: RumbleTarget,
            slotId: String,
            state: Int,
        ) {
            when (target) {
                is RumbleTarget.DirectUsb -> landDirectMicLed(target.deviceId, slotId, state)
                RumbleTarget.Phone -> virtualFeedback.setMicLed(state)
                is RumbleTarget.Framework, RumbleTarget.None -> Unit
            }
        }

        // A Direct pad's surface takes the game's value only while no bench test holds it; a held
        // one keeps the value for the test's end.

        private fun landDirectLightbar(
            deviceId: Int,
            slotId: String,
            r: Int,
            g: Int,
            b: Int,
        ) {
            val toThePad = direct.of(slotId).gameLightbar(feedbackRgb(r, g, b))
            if (toThePad) native.sendUsbLightbar(deviceId, r, g, b)
        }

        private fun landDirectTriggerEffects(
            deviceId: Int,
            slotId: String,
            blocks: ByteArray,
        ) {
            val toThePad = direct.of(slotId).gameTriggerEffects(blocks)
            if (toThePad) native.sendUsbTriggerEffects(deviceId, blocks)
        }

        private fun landDirectPlayerLeds(
            deviceId: Int,
            slotId: String,
            ledMask: Int,
        ) {
            val toThePad = direct.of(slotId).gamePlayerLeds(ledMask)
            if (toThePad) native.sendUsbPlayerLeds(deviceId, ledMask)
        }

        private fun landDirectMicLed(
            deviceId: Int,
            slotId: String,
            state: Int,
        ) {
            val toThePad = direct.of(slotId).gameMicLed(state)
            if (toThePad) native.sendUsbMicMuteLed(deviceId, state)
        }

        private fun writeDirectLightbar(
            deviceId: Int,
            rgb: Long,
        ) {
            native.sendUsbLightbar(deviceId, rgbRed(rgb), rgbGreen(rgb), rgbBlue(rgb))
        }

        companion object {
            // Wire order of MSG_TRIGGER_EFFECTS blocks: left (0..10), right (11..21);
            // byte 0 of each is the DualSense effect mode, 0 = off.
            private const val LEFT_BLOCK_OFFSET = 0
            private const val RIGHT_BLOCK_OFFSET = TRIGGER_EFFECT_BLOCK_BYTES

            // Matches the Moonlight rumble hold: refreshed by the host well before expiry.
            private const val TRIGGER_RUMBLE_HOLD_MS = 1500

            private const val TRIGGER_RUMBLE_STOP = 0
        }
    }

// A block whose mode byte is present and non-zero holds an effect; a truncated array reads as off.
private fun triggerEffectActive(
    blocks: ByteArray,
    offset: Int,
): Boolean = blocks.size > offset && blocks[offset].toInt() != 0
