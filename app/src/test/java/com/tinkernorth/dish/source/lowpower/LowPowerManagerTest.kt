// SPDX-License-Identifier: LGPL-3.0-or-later

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

class LowPowerManagerTest {
    private lateinit var window: Window
    private lateinit var statusView: TextView
    private lateinit var streamingHint: LinearLayout
    private lateinit var lpm: LowPowerManager

    @Before
    fun setUp() {
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)

        val params = WindowManager.LayoutParams()
        window =
            mockk(relaxed = true) {
                every { attributes } returns params
            }
        statusView = mockk(relaxed = true)
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
        streamingHint = mockk(relaxed = true)

        lpm = LowPowerManager(window)
        lpm.views =
            LowPowerManager.Views(
                llCountdownBanner = countdownBanner,
                tvCountdownSeconds = countdownSeconds,
                flLowPowerOverlay = overlay,
                tvLowPowerTime = timeView,
                tvLowPowerStatus = statusView,
                llStreamingHint = streamingHint,
            )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun enterDimManually() {
        setStateDirect(LowPowerManager.State.ACTIVE)
        val update = LowPowerManager::class.java.getDeclaredMethod("updateStatus")
        update.isAccessible = true
        update.invoke(lpm)
    }

    private fun setStateDirect(state: LowPowerManager.State) {
        val superClass = LowPowerManager::class.java.superclass!!
        val field = superClass.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(lpm) as kotlinx.coroutines.flow.MutableStateFlow<LowPowerManager.State>
        flow.value = state
    }

    private fun statusTextHistory(): List<String> {
        val captured = mutableListOf<CharSequence>()
        verify(atLeast = 0) { statusView.text = capture(captured) }
        return captured.map { it.toString() }
    }

    private fun lastStatusText(): String = statusTextHistory().last()

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
        lpm.activeControllerCount = { 0 }
        enterDimManually()
        assertEquals("Idle", lastStatusText())
    }

    @Test
    fun `Bound copy replaces the old Streaming wording`() {
        lpm.activeControllerCount = { 2 }
        enterDimManually()
        val text = lastStatusText()
        assertEquals(false, text.contains("Streaming"))
        assertEquals(true, text.startsWith("Bound"))
    }

    @Test
    fun `refreshStatus while IDLE is a no-op`() {
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

        current = 4
        lpm.refreshStatus()

        val history = statusTextHistory()
        assertEquals(listOf("Bound · 1 controller", "Bound · 4 controllers"), history)
    }

    @Test
    fun `refreshStatus survives cancel-and-reenter without leaking the old count`() {
        lpm.activeControllerCount = { 2 }
        enterDimManually()
        setStateDirect(LowPowerManager.State.IDLE)

        lpm.activeControllerCount = { 1 }
        lpm.refreshStatus()

        verify(exactly = 1) { statusView.text = any() }
    }

    @Test
    fun `null views short-circuits gracefully`() {
        lpm.views = null
        lpm.activeControllerCount = { 1 }
        lpm.refreshStatus()
        @Suppress("UNUSED_VARIABLE")
        val unused = View.NO_ID
    }

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
        val inactivityHandler = mockk<Handler>(relaxed = true)
        val clockHandler = mockk<Handler>(relaxed = true)
        setPrivateField("inactivityHandler", inactivityHandler)
        setPrivateField("clockHandler", clockHandler)

        setStateDirect(LowPowerManager.State.ACTIVE)

        lpm.onUserInteraction()

        assertEquals(LowPowerManager.State.IDLE, lpm.state.value)
        verify { inactivityHandler.postDelayed(any(), 15_000L) }
    }

    @Test
    fun `dismissing the countdown still re-arms the inactivity timer`() {
        val inactivityHandler = mockk<Handler>(relaxed = true)
        setPrivateField("inactivityHandler", inactivityHandler)

        setStateDirect(LowPowerManager.State.COUNTDOWN)

        lpm.onUserInteraction()

        assertEquals(LowPowerManager.State.IDLE, lpm.state.value)
        verify { inactivityHandler.postDelayed(any(), 15_000L) }
    }

    @Test
    fun `streaming hint shows when wake state goes active while idle`() {
        val inactivityHandler = mockk<Handler>(relaxed = true)
        setPrivateField("inactivityHandler", inactivityHandler)

        lpm.onLockStateChanged(active = true)

        verify { streamingHint.visibility = View.VISIBLE }
    }

    @Test
    fun `streaming hint hides when wake state clears`() {
        val inactivityHandler = mockk<Handler>(relaxed = true)
        setPrivateField("inactivityHandler", inactivityHandler)
        lpm.onLockStateChanged(active = true)

        lpm.onLockStateChanged(active = false)

        val captured = mutableListOf<Int>()
        verify(atLeast = 1) { streamingHint.visibility = capture(captured) }
        assertEquals(View.GONE, captured.last())
    }

    @Test
    fun `streaming hint stays hidden while countdown banner is showing`() {
        val inactivityHandler = mockk<Handler>(relaxed = true)
        setPrivateField("inactivityHandler", inactivityHandler)
        setStateDirect(LowPowerManager.State.COUNTDOWN)

        lpm.onLockStateChanged(active = true)

        verify { streamingHint.visibility = View.GONE }
    }

    @Test
    fun `streaming hint reappears after user wakes from dim while still streaming`() {
        val inactivityHandler = mockk<Handler>(relaxed = true)
        val clockHandler = mockk<Handler>(relaxed = true)
        setPrivateField("inactivityHandler", inactivityHandler)
        setPrivateField("clockHandler", clockHandler)
        lpm.onLockStateChanged(active = true)
        setStateDirect(LowPowerManager.State.ACTIVE)

        lpm.onUserInteraction()

        val captured = mutableListOf<Int>()
        verify(atLeast = 1) { streamingHint.visibility = capture(captured) }
        assertEquals(View.VISIBLE, captured.last())
    }
}
