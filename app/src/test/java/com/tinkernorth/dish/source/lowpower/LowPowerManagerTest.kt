// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.lowpower

import android.content.Context
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tinkernorth.dish.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LowPowerManager.refreshStatus] and the "Bound · N" status
 * copy. The state-machine itself (timer → countdown → ACTIVE → exit) is
 * tightly coupled to [android.os.Handler] and [android.os.CountDownTimer] and
 * isn't easily testable on plain JVM — these tests pin only the parts that
 * apply to the dim status line.
 *
 * We drive [LowPowerManager] into the ACTIVE state by calling the package-
 * private `enter()` via reflection so we don't have to wait out the 15-second
 * inactivity timer. The status string is read off the mocked
 * `tvLowPowerStatus`.
 */
class LowPowerManagerTest {
    private lateinit var window: Window
    private lateinit var statusView: TextView
    private lateinit var lpm: LowPowerManager

    @Before
    fun setUp() {
        // The constructor wires two Handlers via Looper.getMainLooper(),
        // which is stubbed out by the AGP unit-test classpath. Returning a
        // relaxed mock keeps Handler() from throwing during init; the
        // tests never actually post to that handler.
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)

        // Window.attributes is touched by enter()/exit() to drop and restore
        // screen brightness. We hand it back a relaxed mock so those property
        // assignments don't throw.
        val params = WindowManager.LayoutParams()
        window =
            mockk(relaxed = true) {
                every { attributes } returns params
            }
        statusView = mockk(relaxed = true)
        // updateStatus() resolves the status copy from string/plurals resources
        // via statusView.context. Stub the chain so the assertions can match
        // the localized format the production code emits.
        val context = mockk<Context>(relaxed = true)
        val resources = mockk<Resources>(relaxed = true)
        every { statusView.context } returns context
        every { context.resources } returns resources
        every { context.getString(R.string.low_power_status_idle) } returns "Idle"
        every {
            resources.getQuantityString(R.plurals.low_power_status_bound, any<Int>(), any<Int>())
        } answers {
            val count = secondArg<Int>()
            val noun = if (count == 1) "controller" else "controllers"
            "Bound · $count $noun"
        }
        val countdownBanner = mockk<LinearLayout>(relaxed = true)
        val countdownSeconds = mockk<TextView>(relaxed = true)
        val overlay = mockk<FrameLayout>(relaxed = true)
        val timeView = mockk<TextView>(relaxed = true)

        lpm = LowPowerManager(window)
        lpm.views =
            LowPowerManager.Views(
                llCountdownBanner = countdownBanner,
                tvCountdownSeconds = countdownSeconds,
                flLowPowerOverlay = overlay,
                tvLowPowerTime = timeView,
                tvLowPowerStatus = statusView,
            )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Skip the 15-second inactivity timer + 5-second countdown by forcing
     * the manager into [LowPowerManager.State.ACTIVE] via reflection and
     * calling the private `updateStatus()`. We can't use `enter()` directly
     * here because it posts to a Handler whose Looper is stubbed out in
     * unit tests — the post throws and the activity-side dim path never
     * makes that call from a unit test anyway, so we bypass it.
     */
    private fun enterDimManually() {
        // `state` is now a property delegating to the [_state] StateFlow —
        // there is no `state` backing field to reflect into. Push directly
        // into _state so both the legacy getter and the new stateFlow stay
        // in lockstep.
        setStateDirect(LowPowerManager.State.ACTIVE)
        val update = LowPowerManager::class.java.getDeclaredMethod("updateStatus")
        update.isAccessible = true
        update.invoke(lpm)
    }

    private fun setStateDirect(state: LowPowerManager.State) {
        // `_state` now lives on the [com.tinkernorth.dish.architecture.abstracts.AbstractStateSource] base
        // class — reflect through the superclass.
        val field = LowPowerManager::class.java.superclass.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(lpm) as kotlinx.coroutines.flow.MutableStateFlow<LowPowerManager.State>
        flow.value = state
    }

    /**
     * Returns every value that has been assigned to `tvLowPowerStatus.text`
     * since the manager was constructed, in order. Used to assert the
     * latest copy after each `updateStatus()` call. We capture into a list
     * (not a single [io.mockk.CapturingSlot]) because mockk's slot only
     * retains the latest invocation and we want to assert on history too.
     */
    private fun statusTextHistory(): List<String> {
        val captured = mutableListOf<CharSequence>()
        verify(atLeast = 0) { statusView.text = capture(captured) }
        return captured.map { it.toString() }
    }

    private fun lastStatusText(): String = statusTextHistory().last()

    // ── Copy ────────────────────────────────────────────────────────────────

    @Test
    fun `single controller uses singular noun`() {
        lpm.activeControllerCount = { 1 }
        enterDimManually()
        assertEquals("Bound · 1 controller", lastStatusText())
    }

    @Test
    fun `multiple controllers use plural noun`() {
        lpm.activeControllerCount = { 3 }
        enterDimManually()
        assertEquals("Bound · 3 controllers", lastStatusText())
    }

    @Test
    fun `zero controllers shows the Idle fallback`() {
        // dim engaging at active=0 is now a race (WakeStateController only
        // engages dim while shouldKeepScreenOn=true → count>0) but the
        // updateStatus path still needs to print something deterministic.
        lpm.activeControllerCount = { 0 }
        enterDimManually()
        assertEquals("Idle", lastStatusText())
    }

    @Test
    fun `Bound copy replaces the old Streaming wording`() {
        // Regression: the prior copy was "Streaming · N controllers". The bug
        // report calling for "Bound" was about matching the bind/unbind
        // vocabulary on the dashboard — make sure we don't quietly revert.
        lpm.activeControllerCount = { 2 }
        enterDimManually()
        val text = lastStatusText()
        assertEquals(false, text.contains("Streaming"))
        assertEquals(true, text.startsWith("Bound"))
    }

    // ── refreshStatus reactivity ───────────────────────────────────────────

    @Test
    fun `refreshStatus while IDLE is a no-op`() {
        // No call to enterDimManually, so state is IDLE. refreshStatus must
        // not touch the status view; otherwise the dim overlay would render
        // its text while invisible and we'd burn redraws.
        lpm.activeControllerCount = { 1 }
        lpm.refreshStatus()
        verify(exactly = 0) { statusView.text = any() }
    }

    @Test
    fun `refreshStatus while ACTIVE re-runs updateStatus with the latest count`() {
        var current = 1
        lpm.activeControllerCount = { current }
        enterDimManually()
        assertEquals("Bound · 1 controller", lastStatusText())

        // Flip the count and tell the manager to re-read.
        current = 4
        lpm.refreshStatus()

        // The history contains the initial value then the refreshed value;
        // assert both, so we know the refresh actually re-derived from the
        // latest count rather than reusing a stale snapshot.
        val history = statusTextHistory()
        assertEquals(listOf("Bound · 1 controller", "Bound · 4 controllers"), history)
    }

    @Test
    fun `refreshStatus survives cancel-and-reenter without leaking the old count`() {
        lpm.activeControllerCount = { 2 }
        enterDimManually()
        // Drop straight back to IDLE via reflection — calling cancel() would
        // touch the Handler we couldn't reliably mock.
        setStateDirect(LowPowerManager.State.IDLE)

        // Now while IDLE, count drops to 1. refreshStatus must NOT update the
        // status view because the overlay is gone.
        lpm.activeControllerCount = { 1 }
        lpm.refreshStatus()

        // statusView.text was set exactly once — from the initial enter().
        verify(exactly = 1) { statusView.text = any() }
    }

    @Test
    fun `null views short-circuits gracefully`() {
        lpm.views = null
        lpm.activeControllerCount = { 1 }
        // Calling refreshStatus before the dim overlay is wired up must not
        // crash — the host activity may briefly construct the manager before
        // setting views (e.g. between super.onCreate and setContentView).
        lpm.refreshStatus()
        // Nothing to verify; the test passes if this didn't throw.
        @Suppress("UNUSED_VARIABLE")
        val unused = View.NO_ID
    }

    // ── stateFlow ────────────────────────────────────────────────────────
    //
    // We drive the manager's internal _state directly rather than calling
    // the package-private enter()/exit() — both touch Handler.postDelayed()
    // which the AGP test runner reports as "not mocked". The shape under
    // test is "state and stateFlow stay in lockstep", which is independent
    // of the timer-driven transitions.

    @Test
    fun `state flow starts at IDLE`() {
        assertEquals(LowPowerManager.State.IDLE, lpm.state.value)
    }

    @Test
    fun `state flow reflects ACTIVE when internal state advances`() {
        setStateDirect(LowPowerManager.State.ACTIVE)

        assertEquals(LowPowerManager.State.ACTIVE, lpm.state.value)
    }

    @Test
    fun `state flow reflects COUNTDOWN when internal state advances`() {
        setStateDirect(LowPowerManager.State.COUNTDOWN)

        assertEquals(LowPowerManager.State.COUNTDOWN, lpm.state.value)
    }

    @Test
    fun `state flow value reaches every enum entry`() {
        for (target in LowPowerManager.State.entries) {
            setStateDirect(target)
            assertEquals("state.value must mirror set state for $target", target, lpm.state.value)
        }
    }

    // ── re-arm after dismissal ─────────────────────────────────────────────
    //
    // Regression for: dismissing the dim overlay (ACTIVE → IDLE via touch)
    // left the inactivity timer un-posted, so the overlay never re-appeared
    // until the user touched the screen again. `WakeStateController
    // .shouldKeepScreenOn` is a StateFlow that doesn't re-emit the same value,
    // so `onLockStateChanged(true)` isn't called a second time — `exit()`
    // has to re-arm the timer itself (via onUserInteraction's ACTIVE branch).

    /**
     * Swap a private field on [lpm] with [value]. Used to install mock
     * Handlers so we can verify scheduling without a real Looper.
     */
    private fun setPrivateField(
        name: String,
        value: Any,
    ) {
        val field = LowPowerManager::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(lpm, value)
    }

    @Test
    fun `dismissing the dim re-arms the inactivity timer`() {
        // Replace the real Handlers (constructed in init with the mocked
        // Looper) with relaxed mocks so exit()'s clockHandler.removeCallbacks
        // and the post-exit inactivityHandler.postDelayed are observable.
        val inactivityHandler = mockk<Handler>(relaxed = true)
        val clockHandler = mockk<Handler>(relaxed = true)
        setPrivateField("inactivityHandler", inactivityHandler)
        setPrivateField("clockHandler", clockHandler)

        // Drive directly to ACTIVE — enter() touches a Handler we can't drive
        // through and isn't what's under test here.
        setStateDirect(LowPowerManager.State.ACTIVE)

        lpm.onUserInteraction()

        assertEquals(LowPowerManager.State.IDLE, lpm.state.value)
        // Re-arm: postDelayed scheduled at the inactivity delay (15s). The
        // bug fix lives in onUserInteraction's ACTIVE branch; without it the
        // post never happens and the overlay can't return.
        verify { inactivityHandler.postDelayed(any(), 15_000L) }
    }

    @Test
    fun `dismissing the countdown still re-arms the inactivity timer`() {
        // Sanity check that the COUNTDOWN branch is unchanged — it already
        // called resetInactivityTimer before this fix, so verify we didn't
        // accidentally regress it.
        val inactivityHandler = mockk<Handler>(relaxed = true)
        setPrivateField("inactivityHandler", inactivityHandler)

        setStateDirect(LowPowerManager.State.COUNTDOWN)

        lpm.onUserInteraction()

        assertEquals(LowPowerManager.State.IDLE, lpm.state.value)
        verify { inactivityHandler.postDelayed(any(), 15_000L) }
    }
}
