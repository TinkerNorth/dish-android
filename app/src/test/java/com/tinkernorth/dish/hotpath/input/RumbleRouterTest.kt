// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import android.content.Context
import android.os.Vibrator
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.source.store.RumbleEnabledStore
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The JVM unit-test stub reports SDK_INT = 0, so the router — and therefore these
// doubles/verifications — drive the legacy single-vibrator path (Context.VIBRATOR_SERVICE,
// Vibrator.vibrate(Long)). Production RumbleRouter suppresses the same platform
// deprecations at each guarded call site; mirror that convention for the test.
@Suppress("DEPRECATION")
class RumbleRouterTest {
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
    ) = RumbleConnectionSnapshot(handle = handle, connected = connected, slots = slots)

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
    ) {
        val native = mockk<PhysicalInputNative>(relaxed = true)

        // SDK_INT is 0 under the JVM stub, so the router takes the legacy single-vibrator phone path.
        val vibrator = mockk<Vibrator>(relaxed = true) { every { hasVibrator() } returns true }
        val rumbleEnabled =
            mockk<RumbleEnabledStore> { every { isEnabled(any()) } returns rumbleOn }

        private val context =
            mockk<Context>(relaxed = true) {
                every { getSystemService(Context.VIBRATOR_SERVICE) } returns vibrator
            }
        private val connection =
            mockk<SatelliteConnection> {
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
                scope = CoroutineScope(Dispatchers.Unconfined),
                rumbleEnabled = rumbleEnabled,
            )
    }

    @Test
    fun `dispatch suppresses the phone vibrator when the virtual slot is rumble-off`() {
        val h = DispatchHarness(slotId = VIRTUAL_SLOT_ID, controllerIndex = 0, rumbleOn = false)

        h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 500, weakMagnitude = 500, durationMs = 100)

        verify { h.rumbleEnabled.isEnabled(VIRTUAL_SLOT_ID) }
        verify(exactly = 0) { h.vibrator.vibrate(any<Long>()) }
        verify(exactly = 0) { h.native.sendUsbRumble(any(), any(), any()) }
    }

    @Test
    fun `dispatch actuates the phone vibrator when the virtual slot is rumble-on`() {
        val h = DispatchHarness(slotId = VIRTUAL_SLOT_ID, controllerIndex = 0, rumbleOn = true)

        h.router.dispatch(sessionHandle = 7, controllerIndex = 0, strongMagnitude = 500, weakMagnitude = 500, durationMs = 100)

        verify { h.rumbleEnabled.isEnabled(VIRTUAL_SLOT_ID) }
        verify { h.vibrator.vibrate(any<Long>()) }
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
        verify(exactly = 0) { h.vibrator.vibrate(any<Long>()) }
        verify(exactly = 0) { h.native.sendUsbRumble(any(), any(), any()) }
    }
}
