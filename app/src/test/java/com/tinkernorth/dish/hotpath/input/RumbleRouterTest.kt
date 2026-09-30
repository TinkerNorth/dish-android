// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.source.store.FeedbackActivityStore
import com.tinkernorth.dish.source.store.FeedbackKind
import com.tinkernorth.dish.source.store.RumbleEnabledStore
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RumbleRouterTest {
    private val dispatcher = StandardTestDispatcher()

    private fun slot(index: Int) = SatelliteConnection.SlotBinding(controllerIndex = index, controllerType = 0, registered = true)

    @Test
    fun `resolveSlotId returns the slot whose controller index matches`() {
        val slots = mapOf(VIRTUAL_SLOT_ID to slot(0), "1234" to slot(1), "-1000" to slot(2))
        assertEquals(VIRTUAL_SLOT_ID, resolveSlotId(slots, 0))
        assertEquals("1234", resolveSlotId(slots, 1))
        assertEquals("-1000", resolveSlotId(slots, 2))
    }

    @Test
    fun `resolveSlotId returns null when no slot matches or map is empty`() {
        assertNull(resolveSlotId(mapOf(VIRTUAL_SLOT_ID to slot(0)), 3))
        assertNull(resolveSlotId(emptyMap(), 0))
    }

    @Test
    fun `classifyTarget routes the virtual slot to the phone`() {
        assertEquals(RumbleTarget.Phone, classifyTarget(VIRTUAL_SLOT_ID))
    }

    @Test
    fun `classifyTarget routes a framework device id to its own actuator`() {
        assertEquals(RumbleTarget.Framework(1234), classifyTarget("1234"))
        assertEquals(RumbleTarget.Framework(0), classifyTarget("0"))
    }

    @Test
    fun `classifyTarget routes a negative synthetic id to the USB-direct path`() {
        assertEquals(RumbleTarget.DirectUsb(-1000), classifyTarget("-1000"))
        assertEquals(RumbleTarget.DirectUsb(-1), classifyTarget("-1"))
    }

    @Test
    fun `classifyTarget yields None for an unparseable slot id`() {
        assertEquals(RumbleTarget.None, classifyTarget("not-an-int"))
        assertEquals(RumbleTarget.None, classifyTarget(""))
    }

    @Test
    fun `combinedRumblePlan separates strong and weak across two actuators`() {
        assertEquals(listOf(0 to 200, 1 to 100), combinedRumblePlan(vibratorCount = 2, strongAmp = 200, weakAmp = 100))
    }

    @Test
    fun `combinedRumblePlan drops a zero-strong actuator on a dual target`() {
        assertEquals(listOf(1 to 100), combinedRumblePlan(vibratorCount = 2, strongAmp = 0, weakAmp = 100))
    }

    @Test
    fun `combinedRumblePlan drops a zero-weak actuator on a dual target`() {
        assertEquals(listOf(0 to 200), combinedRumblePlan(vibratorCount = 2, strongAmp = 200, weakAmp = 0))
    }

    @Test
    fun `combinedRumblePlan yields nothing when both amplitudes are zero on a dual target`() {
        assertEquals(emptyList<Pair<Int, Int>>(), combinedRumblePlan(vibratorCount = 2, strongAmp = 0, weakAmp = 0))
    }

    @Test
    fun `combinedRumblePlan folds a strong-dominant effect onto a single actuator`() {
        assertEquals(listOf(0 to 200), combinedRumblePlan(vibratorCount = 1, strongAmp = 200, weakAmp = 50))
    }

    @Test
    fun `combinedRumblePlan folds a weak-dominant effect onto a single actuator`() {
        assertEquals(listOf(0 to 180), combinedRumblePlan(vibratorCount = 1, strongAmp = 40, weakAmp = 180))
    }

    @Test
    fun `combinedRumblePlan drives the single actuator when only weak is set`() {
        assertEquals(listOf(0 to 90), combinedRumblePlan(vibratorCount = 1, strongAmp = 0, weakAmp = 90))
    }

    @Test
    fun `combinedRumblePlan yields nothing for a single actuator with no amplitude`() {
        assertEquals(emptyList<Pair<Int, Int>>(), combinedRumblePlan(vibratorCount = 1, strongAmp = 0, weakAmp = 0))
    }

    @Test
    fun `combinedRumblePlan yields nothing when there are no actuators`() {
        assertEquals(emptyList<Pair<Int, Int>>(), combinedRumblePlan(vibratorCount = 0, strongAmp = 200, weakAmp = 100))
    }

    private fun conn(
        handle: Int,
        connected: Boolean = true,
        slots: Map<String, SatelliteConnection.SlotBinding> = emptyMap(),
    ) = RumbleConnectionSnapshot(connectionId = "c$handle", handle = handle, connected = connected, slots = slots)

    @Test
    fun `resolveRumble routes to the framework slot bound at the controller index`() {
        val snapshot = listOf(conn(handle = 7, slots = mapOf("1234" to slot(0))))
        assertEquals(RumbleTarget.Framework(1234), resolveRumble(snapshot, sessionHandle = 7, controllerIndex = 0))
    }

    @Test
    fun `resolveRumble routes the virtual slot to the phone`() {
        val snapshot = listOf(conn(handle = 7, slots = mapOf(VIRTUAL_SLOT_ID to slot(0))))
        assertEquals(RumbleTarget.Phone, resolveRumble(snapshot, sessionHandle = 7, controllerIndex = 0))
    }

    @Test
    fun `resolveRumble routes a synthetic slot to the USB-direct path`() {
        val snapshot = listOf(conn(handle = 7, slots = mapOf("-1000" to slot(2))))
        assertEquals(RumbleTarget.DirectUsb(-1000), resolveRumble(snapshot, sessionHandle = 7, controllerIndex = 2))
    }

    @Test
    fun `resolveRumble yields None when no connection has the session handle`() {
        val snapshot = listOf(conn(handle = 7, slots = mapOf("1234" to slot(0))))
        assertEquals(RumbleTarget.None, resolveRumble(snapshot, sessionHandle = 99, controllerIndex = 0))
        assertEquals(RumbleTarget.None, resolveRumble(emptyList(), sessionHandle = 7, controllerIndex = 0))
    }

    @Test
    fun `resolveRumble yields None for a negative session handle`() {
        val snapshot = listOf(conn(handle = -1, slots = mapOf("1234" to slot(0))))
        assertEquals(RumbleTarget.None, resolveRumble(snapshot, sessionHandle = -1, controllerIndex = 0))
    }

    @Test
    fun `resolveRumble yields None when the matched connection has no slot at the index`() {
        val snapshot = listOf(conn(handle = 7, slots = mapOf("1234" to slot(0))))
        assertEquals(RumbleTarget.None, resolveRumble(snapshot, sessionHandle = 7, controllerIndex = 5))
    }

    @Test
    fun `resolveRumble prefers the connected connection when two share a handle`() {
        val stale = conn(handle = 7, connected = false, slots = mapOf("1111" to slot(0)))
        val live = conn(handle = 7, connected = true, slots = mapOf("2222" to slot(0)))
        // Stale listed first: the connected match must still win, not first-match.
        assertEquals(RumbleTarget.Framework(2222), resolveRumble(listOf(stale, live), sessionHandle = 7, controllerIndex = 0))
        assertEquals(RumbleTarget.Framework(2222), resolveRumble(listOf(live, stale), sessionHandle = 7, controllerIndex = 0))
    }

    @Test
    fun `resolveRumble falls back to the first match when no sharing connection is connected`() {
        val first = conn(handle = 7, connected = false, slots = mapOf("1111" to slot(0)))
        val second = conn(handle = 7, connected = false, slots = mapOf("2222" to slot(0)))
        assertEquals(RumbleTarget.Framework(1111), resolveRumble(listOf(first, second), sessionHandle = 7, controllerIndex = 0))
    }

    @Test
    fun `isRumbleStop is true when both magnitudes are zero or duration is zero`() {
        assertTrue(isRumbleStop(strongMagnitude = 0, weakMagnitude = 0, durationMs = 100))
        assertTrue(isRumbleStop(strongMagnitude = 500, weakMagnitude = 500, durationMs = 0))
    }

    @Test
    fun `isRumbleStop is false when there is a positive magnitude and duration`() {
        assertFalse(isRumbleStop(strongMagnitude = 500, weakMagnitude = 0, durationMs = 100))
        assertFalse(isRumbleStop(strongMagnitude = 0, weakMagnitude = 500, durationMs = 100))
    }

    private class DispatchHarness(
        slotId: String,
        controllerIndex: Int,
        rumbleOn: Boolean,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
        sdkInt: Int = Build.VERSION_CODES.N,
    ) {
        val native = mockk<PhysicalInputNative>(relaxed = true)

        // The phone's single vibrator below 31 and its vibrator manager from 31.
        val vibrator = mockk<Vibrator>(relaxed = true) { every { hasVibrator() } returns true }
        val vibratorManager = mockk<VibratorManager>(relaxed = true) { every { vibratorIds } returns intArrayOf(PHONE_MOTOR) }
        val rumbleEnabled =
            mockk<RumbleEnabledStore> { every { isEnabled(any()) } returns rumbleOn }
        val feedbackActivity = FeedbackActivityStore()

        private val context =
            mockk<Context>(relaxed = true) {
                every { getSystemService(Vibrator::class.java) } returns vibrator
                every { getSystemService(Context.VIBRATOR_MANAGER_SERVICE) } returns vibratorManager
            }
        private val connection =
            mockk<SatelliteConnection> {
                every { id } returns "a"
                every { handle } returns 7
                every { state } returns MutableStateFlow(SatelliteSessionState.Live)
                every { slots } returns
                    MutableStateFlow(
                        mapOf(slotId to SatelliteConnection.SlotBinding(controllerIndex, controllerType = 0, registered = true)),
                    )
            }
        private val satellite =
            mockk<SatelliteConnectionManager> {
                every { connections } returns MutableStateFlow(mapOf("a" to connection))
            }

        val router =
            RumbleRouter(
                context = context,
                satellite = satellite,
                native = native,
                scope = scope,
                rumbleEnabled = rumbleEnabled,
                feedbackActivity = feedbackActivity,
                sdkInt = sdkInt,
            )
    }

    @Test
    fun `dispatch suppresses the phone vibrator when the virtual slot is rumble-off`() {
        val h = DispatchHarness(slotId = VIRTUAL_SLOT_ID, controllerIndex = 0, rumbleOn = false)

        h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 500, weakMagnitude = 500, durationMs = 100)

        verify { h.rumbleEnabled.isEnabled(VIRTUAL_SLOT_ID) }
        verify { h.vibrator wasNot Called }
        verify(exactly = 0) { h.native.sendUsbRumble(any(), any(), any()) }
    }

    @Test
    fun `dispatch actuates the phone vibrator when the virtual slot is rumble-on`() {
        val h = DispatchHarness(slotId = VIRTUAL_SLOT_ID, controllerIndex = 0, rumbleOn = true)

        h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 500, weakMagnitude = 500, durationMs = 100)

        verify { h.rumbleEnabled.isEnabled(VIRTUAL_SLOT_ID) }
        verifyLegacyVibrate(h.vibrator, calls = 1)
    }

    @Test
    fun `dispatch suppresses USB rumble when the direct slot is rumble-off`() {
        val h = DispatchHarness(slotId = "-1000", controllerIndex = 0, rumbleOn = false)

        h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 500, weakMagnitude = 500, durationMs = 100)

        verify { h.rumbleEnabled.isEnabled("-1000") }
        verify(exactly = 0) { h.native.sendUsbRumble(any(), any(), any()) }
    }

    @Test
    fun `dispatch actuates USB rumble when the direct slot is rumble-on`() {
        val h = DispatchHarness(slotId = "-1000", controllerIndex = 0, rumbleOn = true)

        h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 500, weakMagnitude = 250, durationMs = 100)

        verify { h.rumbleEnabled.isEnabled("-1000") }
        verify { h.native.sendUsbRumble(-1000, 500, 250) }
    }

    @Test
    fun `dispatch consults the gate with the framework device id and suppresses when off`() {
        val h = DispatchHarness(slotId = "1234", controllerIndex = 0, rumbleOn = false)

        h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 500, weakMagnitude = 500, durationMs = 100)

        // slotIdOf maps a framework target to its device id string; with the gate off nothing actuates.
        verify { h.rumbleEnabled.isEnabled("1234") }
        verify { h.vibrator wasNot Called }
        verify(exactly = 0) { h.native.sendUsbRumble(any(), any(), any()) }
    }

    // ---- the stop path ----

    @Test
    fun `dispatch with zero magnitudes cancels the phone vibrator`() {
        val h = DispatchHarness(slotId = VIRTUAL_SLOT_ID, controllerIndex = 0, rumbleOn = true)

        h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 0, weakMagnitude = 0, durationMs = 100)

        verify { h.vibrator.cancel() }
        verifyLegacyVibrate(h.vibrator, calls = 0)
    }

    @Test
    fun `dispatch with zero duration sends a USB stop and drops the pending stop job`() =
        runTest(dispatcher) {
            val h = DispatchHarness(slotId = "-1000", controllerIndex = 0, rumbleOn = true, scope = this)
            h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 500, weakMagnitude = 250, durationMs = 100)

            h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 500, weakMagnitude = 250, durationMs = 0)

            verify(exactly = 1) { h.native.sendUsbRumble(-1000, 0, 0) }
            dispatcher.scheduler.advanceTimeBy(RUMBLE_MAX_MS.toLong())
            dispatcher.scheduler.runCurrent()
            verify(exactly = 1) { h.native.sendUsbRumble(-1000, 0, 0) }
        }

    // ---- the scheduled USB stop ----

    @Test
    fun `usb rumble sends a zero after the clamped duration`() =
        runTest(dispatcher) {
            val h = DispatchHarness(slotId = "-1000", controllerIndex = 0, rumbleOn = true, scope = this)

            h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 500, weakMagnitude = 250, durationMs = 5_000)

            dispatcher.scheduler.advanceTimeBy(RUMBLE_MAX_MS - 1L)
            dispatcher.scheduler.runCurrent()
            verify(exactly = 0) { h.native.sendUsbRumble(-1000, 0, 0) }
            dispatcher.scheduler.advanceTimeBy(1L)
            dispatcher.scheduler.runCurrent()
            verify(exactly = 1) { h.native.sendUsbRumble(-1000, 0, 0) }
        }

    @Test
    fun `a second usb rumble cancels the first pending stop`() =
        runTest(dispatcher) {
            val h = DispatchHarness(slotId = "-1000", controllerIndex = 0, rumbleOn = true, scope = this)
            h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 500, weakMagnitude = 250, durationMs = 100)
            dispatcher.scheduler.advanceTimeBy(50L)
            dispatcher.scheduler.runCurrent()

            h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 500, weakMagnitude = 250, durationMs = 100)

            dispatcher.scheduler.advanceTimeBy(60L)
            dispatcher.scheduler.runCurrent()
            verify(exactly = 0) { h.native.sendUsbRumble(-1000, 0, 0) }
            dispatcher.scheduler.advanceTimeBy(40L)
            dispatcher.scheduler.runCurrent()
            verify(exactly = 1) { h.native.sendUsbRumble(-1000, 0, 0) }
        }

    // ---- activity, the bench entry and the slot-addressed entry ----

    @Test
    fun `dispatch records rumble activity even when the slot is rumble-off`() {
        val h = DispatchHarness(slotId = VIRTUAL_SLOT_ID, controllerIndex = 0, rumbleOn = false)

        h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 500, weakMagnitude = 500, durationMs = 100)

        assertEquals(FeedbackKind.RUMBLE, h.feedbackActivity.snapshot()[VIRTUAL_SLOT_ID]?.lastKind)
    }

    @Test
    fun `testBuzz ignores the per-slot rumble toggle`() {
        val h = DispatchHarness(slotId = VIRTUAL_SLOT_ID, controllerIndex = 0, rumbleOn = false)

        h.router.testBuzz(VIRTUAL_SLOT_ID, strongMagnitude = 500, weakMagnitude = 500, durationMs = 100)

        verifyLegacyVibrate(h.vibrator, calls = 1)
        verify(exactly = 0) { h.rumbleEnabled.isEnabled(any()) }
    }

    @Test
    fun `testBuzz for an unparseable slot does nothing`() {
        val h = DispatchHarness(slotId = VIRTUAL_SLOT_ID, controllerIndex = 0, rumbleOn = true)

        h.router.testBuzz("not-a-slot", strongMagnitude = 500, weakMagnitude = 500, durationMs = 100)

        verify { h.vibrator wasNot Called }
        verify(exactly = 0) { h.native.sendUsbRumble(any(), any(), any()) }
    }

    @Test
    fun `testBuzz with zero amplitude does not vibrate`() {
        val h = DispatchHarness(slotId = VIRTUAL_SLOT_ID, controllerIndex = 0, rumbleOn = true)

        h.router.testBuzz(VIRTUAL_SLOT_ID, strongMagnitude = 0, weakMagnitude = 0, durationMs = 100)

        verifyLegacyVibrate(h.vibrator, calls = 0)
    }

    @Test
    fun `testBuzz still clamps the duration`() {
        val h = DispatchHarness(slotId = VIRTUAL_SLOT_ID, controllerIndex = 0, rumbleOn = true)

        h.router.testBuzz(VIRTUAL_SLOT_ID, strongMagnitude = 500, weakMagnitude = 500, durationMs = 5_000)

        verifyLegacyVibrate(h.vibrator, calls = 1, durationMs = RUMBLE_MAX_MS.toLong())
    }

    @Test
    fun `dispatchToSlot actuates a direct pad and consults the gate with its slot id`() {
        val h = DispatchHarness(slotId = "-1000", controllerIndex = 0, rumbleOn = true)

        h.router.dispatchToSlot("-1000", strongMagnitude = 500, weakMagnitude = 250, durationMs = 100)

        verify { h.rumbleEnabled.isEnabled("-1000") }
        verify { h.native.sendUsbRumble(-1000, 500, 250) }
    }

    @Test
    fun `phone rumble on a device without a vibrator does nothing`() {
        val h = DispatchHarness(slotId = VIRTUAL_SLOT_ID, controllerIndex = 0, rumbleOn = true)
        every { h.vibrator.hasVibrator() } returns false

        h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 500, weakMagnitude = 500, durationMs = 100)

        verifyLegacyVibrate(h.vibrator, calls = 0)
    }

    // ---- a framework pad's own motor ----

    @Test
    fun `framework rumble reaches the pad's own vibrator`() {
        val h = DispatchHarness(slotId = "1234", controllerIndex = 0, rumbleOn = true)
        val motor = mockk<Vibrator>(relaxed = true) { every { hasVibrator() } returns true }

        withListedDevice(1234, padWithMotor(motor)) {
            h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 500, weakMagnitude = 250, durationMs = 100)
        }

        verifyLegacyVibrate(motor, calls = 1, durationMs = 100L)
        verify { h.vibrator wasNot Called }
    }

    @Test
    fun `a framework stop cancels the pad's own vibrator`() {
        val h = DispatchHarness(slotId = "1234", controllerIndex = 0, rumbleOn = true)
        val motor = mockk<Vibrator>(relaxed = true)

        withListedDevice(1234, padWithMotor(motor)) {
            h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 0, weakMagnitude = 0, durationMs = 100)
        }

        verify { motor.cancel() }
    }

    @Test
    fun `framework rumble for a device Android no longer lists is dropped`() {
        val h = DispatchHarness(slotId = "1234", controllerIndex = 0, rumbleOn = true)

        withListedDevice(1234, null) {
            h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 500, weakMagnitude = 250, durationMs = 100)
            h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 0, weakMagnitude = 0, durationMs = 100)
        }

        verify { h.vibrator wasNot Called }
        verify(exactly = 0) { h.native.sendUsbRumble(any(), any(), any()) }
    }

    private inline fun withListedDevice(
        deviceId: Int,
        device: InputDevice?,
        block: () -> Unit,
    ) {
        mockkStatic(InputDevice::class)
        try {
            every { InputDevice.getDevice(deviceId) } returns device
            block()
        } finally {
            unmockkStatic(InputDevice::class)
        }
    }

    // ---- which vibrator API each release takes ----

    @Test
    fun `on API 25 the phone takes the legacy vibrate`() {
        val h = DispatchHarness(slotId = VIRTUAL_SLOT_ID, controllerIndex = 0, rumbleOn = true, sdkInt = Build.VERSION_CODES.N_MR1)

        withOneShotEffects {
            h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = FULL, weakMagnitude = HALF, durationMs = 100)
        }

        verifyLegacyVibrate(h.vibrator, calls = 1, durationMs = 100L)
        verify(exactly = 0) { h.vibrator.vibrate(any<VibrationEffect>()) }
    }

    @Test
    fun `from API 26 the phone vibrates a one-shot effect at the louder motor's amplitude`() {
        val h = DispatchHarness(slotId = VIRTUAL_SLOT_ID, controllerIndex = 0, rumbleOn = true, sdkInt = Build.VERSION_CODES.O)

        withOneShotEffects {
            h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = FULL, weakMagnitude = HALF, durationMs = 100)
        }

        verify { h.vibrator.vibrate(strongEffect) }
        verifyLegacyVibrate(h.vibrator, calls = 0)
    }

    @Test
    fun `through API 30 the phone keeps its single vibrator`() {
        val h = DispatchHarness(slotId = VIRTUAL_SLOT_ID, controllerIndex = 0, rumbleOn = true, sdkInt = Build.VERSION_CODES.R)

        withOneShotEffects {
            h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = FULL, weakMagnitude = HALF, durationMs = 100)
        }

        verify { h.vibrator.vibrate(strongEffect) }
        verify { h.vibratorManager wasNot Called }
    }

    @Test
    fun `from API 31 the phone drives its vibrator manager`() {
        val h = DispatchHarness(slotId = VIRTUAL_SLOT_ID, controllerIndex = 0, rumbleOn = true, sdkInt = Build.VERSION_CODES.S)

        withOneShotEffects {
            h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = FULL, weakMagnitude = HALF, durationMs = 100)
        }

        verify { combination.addVibrator(PHONE_MOTOR, strongEffect) }
        verify { h.vibratorManager.vibrate(combined) }
        verify { h.vibrator wasNot Called }
    }

    @Test
    fun `from API 31 a phone stop cancels the vibrator manager`() {
        val h = DispatchHarness(slotId = VIRTUAL_SLOT_ID, controllerIndex = 0, rumbleOn = true, sdkInt = Build.VERSION_CODES.S)

        h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 0, weakMagnitude = 0, durationMs = 100)

        verify { h.vibratorManager.cancel() }
        verify { h.vibrator wasNot Called }
    }

    @Test
    fun `through API 30 a framework pad rumbles its own legacy vibrator with an effect`() {
        val h = DispatchHarness(slotId = "1234", controllerIndex = 0, rumbleOn = true, sdkInt = Build.VERSION_CODES.R)
        val motor = mockk<Vibrator>(relaxed = true) { every { hasVibrator() } returns true }
        val pad = padWithMotor(motor)

        withOneShotEffects {
            withListedDevice(1234, pad) {
                h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = FULL, weakMagnitude = HALF, durationMs = 100)
            }
        }

        verify { motor.vibrate(strongEffect) }
        verify(exactly = 0) { pad.vibratorManager }
    }

    @Test
    fun `from API 31 a framework pad splits the motors across its own vibrator manager`() {
        val h = DispatchHarness(slotId = "1234", controllerIndex = 0, rumbleOn = true, sdkInt = Build.VERSION_CODES.S)
        val padManager = mockk<VibratorManager>(relaxed = true) { every { vibratorIds } returns intArrayOf(PAD_STRONG, PAD_WEAK) }
        val pad = mockk<InputDevice> { every { vibratorManager } returns padManager }

        withOneShotEffects {
            withListedDevice(1234, pad) {
                h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = FULL, weakMagnitude = HALF, durationMs = 100)
            }
        }

        verify { combination.addVibrator(PAD_STRONG, strongEffect) }
        verify { combination.addVibrator(PAD_WEAK, weakEffect) }
        verify { padManager.vibrate(combined) }
    }

    @Test
    fun `from API 31 a framework stop cancels the pad's vibrator manager`() {
        val h = DispatchHarness(slotId = "1234", controllerIndex = 0, rumbleOn = true, sdkInt = Build.VERSION_CODES.S)
        val padManager = mockk<VibratorManager>(relaxed = true)
        val pad = mockk<InputDevice> { every { vibratorManager } returns padManager }

        withListedDevice(1234, pad) {
            h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 0, weakMagnitude = 0, durationMs = 100)
        }

        verify { padManager.cancel() }
    }

    private val strongEffect = mockk<VibrationEffect>()
    private val weakEffect = mockk<VibrationEffect>()
    private val combined = mockk<CombinedVibration>()
    private val combination =
        mockk<CombinedVibration.ParallelCombination> {
            every { addVibrator(any(), any()) } returns this
            every { combine() } returns combined
        }

    // The platform's effect factories are stubs on the JVM; these hand back effects a test can
    // name, one per amplitude, at the 100 ms every case here asks for.
    private inline fun withOneShotEffects(block: () -> Unit) {
        mockkStatic(VibrationEffect::class, CombinedVibration::class)
        try {
            every { VibrationEffect.createOneShot(100L, rumbleMagnitudeTo255(FULL)) } returns strongEffect
            every { VibrationEffect.createOneShot(100L, rumbleMagnitudeTo255(HALF)) } returns weakEffect
            every { CombinedVibration.startParallel() } returns combination
            block()
        } finally {
            unmockkStatic(VibrationEffect::class, CombinedVibration::class)
        }
    }

    private fun padWithMotor(motor: Vibrator): InputDevice = mockk<InputDevice>().also { stubLegacyVibrator(it, motor) }

    private companion object {
        const val PHONE_MOTOR = 1
        const val PAD_STRONG = 10
        const val PAD_WEAK = 11
        const val FULL = 65_535
        const val HALF = 32_768
    }
}
