// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import com.tinkernorth.dish.core.net.moonlight.MoonlightControlProtocol
import com.tinkernorth.dish.core.net.moonlight.MoonlightControlSession
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType
import com.tinkernorth.dish.core.net.moonlight.MoonlightEvent
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The connection's TelemetrySink face: satellite-scaled samples in, Moonlight
 * control packets out, gated on the host's MOTION_EVENT requests and diffed
 * into per-pointer touch events.
 */
class MoonlightConnectionTelemetryTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var conn: MoonlightConnection
    private lateinit var session: MoonlightControlSession

    @Before
    fun setUp() {
        conn =
            MoonlightConnection(
                id = "moonlight:uid:abc",
                host = MoonlightHost(name = "PC", address = "10.0.0.5", uniqueId = "abc"),
                scope = TestScope(dispatcher),
                ioDispatcher = dispatcher,
            )
        session = mockk(relaxed = true)
        every { session.state } returns MoonlightControlSession.State.CONNECTED
        conn.acquirePad("slot-a", MoonlightEmulatedType.PLAYSTATION, 0xFF, 0x10FFFF)
        conn.markLive(session, appId = null, appName = null)
    }

    @Test
    fun `motion is dropped until the host requests it, then split per type`() {
        conn.sendMotion("slot-a", 100, 200, 300, 400, 500, 600, 1000)
        verify(exactly = 0) { session.sendControllerMotion(any(), any(), any(), any(), any()) }

        conn.dispatchFeedback(
            MoonlightEvent.MotionRequest(
                controllerNumber = 0,
                reportRateHz = 100,
                motionType = MoonlightControlProtocol.MOTION_TYPE_GYRO,
            ),
        )
        assertTrue(conn.motionWanted("slot-a"))
        conn.sendMotion("slot-a", 16384, 0, 0, 0, 0, 8192, 1000)
        // Only the gyro subscription fires; accel was never requested.
        verify(exactly = 1) {
            session.sendControllerMotion(
                controllerNumber = 0,
                motionType = MoonlightControlProtocol.MOTION_TYPE_GYRO,
                x = match { kotlin.math.abs(it - 1000.03f) < 0.5f }, // 16384 wire = half scale = ~1000 deg/s
                y = 0f,
                z = 0f,
            )
        }
        verify(exactly = 0) {
            session.sendControllerMotion(any(), MoonlightControlProtocol.MOTION_TYPE_ACCEL, any(), any(), any())
        }

        // Rate 0 stops the stream again.
        conn.dispatchFeedback(
            MoonlightEvent.MotionRequest(
                controllerNumber = 0,
                reportRateHz = 0,
                motionType = MoonlightControlProtocol.MOTION_TYPE_GYRO,
            ),
        )
        assertFalse(conn.motionWanted("slot-a"))
    }

    @Test
    fun `battery samples translate to the Moonlight state vocabulary`() {
        conn.sendBattery("slot-a", level = 73, status = 2)
        verify(exactly = 1) {
            session.sendControllerBattery(
                controllerNumber = 0,
                batteryState = MoonlightControlProtocol.BATTERY_CHARGING,
                percentage = 73,
            )
        }
        conn.sendBattery("slot-a", level = 0xFF, status = 0)
        verify(exactly = 1) {
            session.sendControllerBattery(
                controllerNumber = 0,
                batteryState = MoonlightControlProtocol.BATTERY_STATE_UNKNOWN,
                percentage = MoonlightControlProtocol.BATTERY_PERCENTAGE_UNKNOWN,
            )
        }
    }

    @Test
    fun `an unknown slot sends nothing`() {
        conn.sendBattery("slot-zz", level = 50, status = 1)
        verify(exactly = 0) { session.sendControllerBattery(any(), any(), any()) }
    }

    @Test
    fun `touch frames become DOWN then UP events with normalized coordinates`() {
        conn.sendTouchpad(
            slotId = "slot-a",
            finger0Active = true,
            finger1Active = false,
            buttonPressed = false,
            rightPressed = false,
            middlePressed = false,
            finger0TrackingId = 4,
            finger0X = 0,
            finger0Y = Short.MAX_VALUE,
            finger1TrackingId = 0,
            finger1X = 0,
            finger1Y = 0,
            eventTimeMs = 0L,
            scrollDelta = 0,
        )
        verify(exactly = 1) {
            session.sendControllerTouch(
                controllerNumber = 0,
                eventType = MoonlightControlProtocol.TOUCH_EVENT_DOWN,
                pointerId = 4,
                x = match { kotlin.math.abs(it - 0.5f) < 0.001f },
                y = match { kotlin.math.abs(it - 1.0f) < 0.001f },
                pressure = 1.0f,
            )
        }
        conn.sendTouchpad(
            slotId = "slot-a",
            finger0Active = false,
            finger1Active = false,
            buttonPressed = false,
            rightPressed = false,
            middlePressed = false,
            finger0TrackingId = 0,
            finger0X = 0,
            finger0Y = 0,
            finger1TrackingId = 0,
            finger1X = 0,
            finger1Y = 0,
            eventTimeMs = 0L,
            scrollDelta = 0,
        )
        verify(exactly = 1) {
            session.sendControllerTouch(
                controllerNumber = 0,
                eventType = MoonlightControlProtocol.TOUCH_EVENT_UP,
                pointerId = 4,
                x = any(),
                y = any(),
                pressure = 0.0f,
            )
        }
    }

    @Test
    fun `a touchpad click edge replays the pad frame with BTN_TOUCHPAD merged`() {
        // A pad report first, so there is a frame to replay.
        conn.sendControllerState(
            0,
            buttons = MoonlightControlProtocol.BTN_A,
            leftTrigger = 0,
            rightTrigger = 0,
            leftX = 1,
            leftY = 2,
            rightX = 3,
            rightY = 4,
        )
        conn.sendTouchpad(
            slotId = "slot-a",
            finger0Active = false,
            finger1Active = false,
            buttonPressed = true,
            rightPressed = false,
            middlePressed = false,
            finger0TrackingId = 0,
            finger0X = 0,
            finger0Y = 0,
            finger1TrackingId = 0,
            finger1X = 0,
            finger1Y = 0,
            eventTimeMs = 0L,
            scrollDelta = 0,
        )
        verify(exactly = 1) {
            session.sendControllerState(
                controllerNumber = 0,
                activeMask = any(),
                buttons = MoonlightControlProtocol.BTN_A or MoonlightControlProtocol.BTN_TOUCHPAD,
                leftTrigger = 0,
                rightTrigger = 0,
                leftStickX = 1,
                leftStickY = 2,
                rightStickX = 3,
                rightStickY = 4,
            )
        }
        // The click keeps riding later pad reports until released.
        conn.sendControllerState(0, buttons = 0, leftTrigger = 0, rightTrigger = 0, leftX = 0, leftY = 0, rightX = 0, rightY = 0)
        verify(exactly = 1) {
            session.sendControllerState(
                controllerNumber = 0,
                activeMask = any(),
                buttons = MoonlightControlProtocol.BTN_TOUCHPAD,
                leftTrigger = 0,
                rightTrigger = 0,
                leftStickX = 0,
                leftStickY = 0,
                rightStickX = 0,
                rightStickY = 0,
            )
        }
    }
}
