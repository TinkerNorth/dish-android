// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneBatterySourceTest {
    private val context = mockk<Context>(relaxed = true)
    private val emitted = mutableListOf<BatterySample>()
    private val emit = PhoneBatterySource.Emit { level, status -> emitted += BatterySample(level, status) }
    private val scope = TestScope(UnconfinedTestDispatcher())

    @Before
    fun setUp() {
        mockkStatic(Log::class, ContextCompat::class)
        every { Log.d(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `a level over a scale of 100 is the level`() {
        assertEquals(57, phoneBatteryLevel(rawLevel = 57, scale = 100))
    }

    @Test
    fun `a scale other than 100 is normalised`() {
        assertEquals(50, phoneBatteryLevel(rawLevel = 2000, scale = 4000))
    }

    @Test
    fun `a level past the scale clamps to 100`() {
        assertEquals(100, phoneBatteryLevel(rawLevel = 120, scale = 100))
    }

    @Test
    fun `an absent level reads unknown`() {
        assertEquals(BatteryValidator.LEVEL_UNKNOWN, phoneBatteryLevel(rawLevel = -1, scale = 100))
    }

    @Test
    fun `an absent or zero scale reads unknown`() {
        assertEquals(BatteryValidator.LEVEL_UNKNOWN, phoneBatteryLevel(rawLevel = 50, scale = -1))
        assertEquals(BatteryValidator.LEVEL_UNKNOWN, phoneBatteryLevel(rawLevel = 50, scale = 0))
    }

    @Test
    fun `charging and full map to their wire codes`() {
        assertEquals(BatteryValidator.STATUS_CHARGING, phoneBatteryStatus(BatteryManager.BATTERY_STATUS_CHARGING))
        assertEquals(BatteryValidator.STATUS_FULL, phoneBatteryStatus(BatteryManager.BATTERY_STATUS_FULL))
    }

    @Test
    fun `not charging reads as discharging`() {
        assertEquals(BatteryValidator.STATUS_DISCHARGING, phoneBatteryStatus(BatteryManager.BATTERY_STATUS_NOT_CHARGING))
        assertEquals(BatteryValidator.STATUS_DISCHARGING, phoneBatteryStatus(BatteryManager.BATTERY_STATUS_DISCHARGING))
    }

    @Test
    fun `an unknown platform status reads unknown`() {
        assertEquals(BatteryValidator.STATUS_UNKNOWN, phoneBatteryStatus(BatteryManager.BATTERY_STATUS_UNKNOWN))
        assertEquals(BatteryValidator.STATUS_UNKNOWN, phoneBatteryStatus(99))
    }

    private fun batteryIntent(
        status: Int,
        level: Int = 50,
        scale: Int = 100,
    ): Intent {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.getIntExtra(BatteryManager.EXTRA_LEVEL, any()) } returns level
        every { intent.getIntExtra(BatteryManager.EXTRA_SCALE, any()) } returns scale
        every { intent.getIntExtra(BatteryManager.EXTRA_STATUS, any()) } returns status
        return intent
    }

    // The registration hands back the sticky broadcast; the poll's own read is stubbed empty so
    // only the receiver path produces samples.
    private fun startWithSticky(sticky: Intent?): BroadcastReceiver {
        val receiver = slot<BroadcastReceiver>()
        every { ContextCompat.registerReceiver(context, capture(receiver), any(), any()) } returns sticky
        every { context.registerReceiver(null, any<IntentFilter>()) } returns null
        PhoneBatterySource(context).start(scope, emit)
        return receiver.captured
    }

    @Test
    fun `a charging broadcast repeating the sticky status is not re-emitted`() {
        val receiver = startWithSticky(batteryIntent(BatteryManager.BATTERY_STATUS_CHARGING))

        receiver.onReceive(context, batteryIntent(BatteryManager.BATTERY_STATUS_CHARGING))

        assertEquals(emptyList<BatterySample>(), emitted)
    }

    @Test
    fun `a charging broadcast with a new status is forwarded once`() {
        val receiver = startWithSticky(batteryIntent(BatteryManager.BATTERY_STATUS_CHARGING))

        receiver.onReceive(context, batteryIntent(BatteryManager.BATTERY_STATUS_DISCHARGING, level = 80))
        receiver.onReceive(context, batteryIntent(BatteryManager.BATTERY_STATUS_DISCHARGING, level = 79))

        assertEquals(listOf(BatterySample(80, BatteryValidator.STATUS_DISCHARGING)), emitted)
    }

    @Test
    fun `without a sticky broadcast the first status is a transition`() {
        val receiver = startWithSticky(sticky = null)

        receiver.onReceive(context, batteryIntent(BatteryManager.BATTERY_STATUS_CHARGING))

        assertEquals(listOf(BatterySample(50, BatteryValidator.STATUS_CHARGING)), emitted)
    }

    @Test
    fun `a broadcast without an intent is ignored`() {
        val receiver = startWithSticky(sticky = null)

        receiver.onReceive(context, null)

        assertEquals(emptyList<BatterySample>(), emitted)
    }

    @Test
    fun `readBattery is null when the platform holds no sticky broadcast`() {
        every { context.registerReceiver(null, any<IntentFilter>()) } returns null

        assertNull(PhoneBatterySource(context).readBattery())
    }

    @Test
    fun `readBattery decodes the sticky broadcast`() {
        every { context.registerReceiver(null, any<IntentFilter>()) } returns
            batteryIntent(BatteryManager.BATTERY_STATUS_FULL, level = 100)

        assertEquals(BatterySample(100, BatteryValidator.STATUS_FULL), PhoneBatterySource(context).readBattery())
    }
}
